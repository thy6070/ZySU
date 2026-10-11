"""Run host regressions against the actual SELinux avtab mutation functions.

Small kernel API doubles exercise serialized sizes and repeated xperm edits;
these checks do not replace a kernel build or on-device policy validation.
"""

import argparse
import os
from pathlib import Path
import re
import shutil
import sys

sys.dont_write_bytecode = True
from test_sumh_vfs_helpers import COMMON, ROOT, function, run_test


SOURCE = "kernel/selinux/sepolicy.c"


def implementation(name):
    # Include the opening brace so function() cannot match a forward declaration.
    text = (ROOT / SOURCE).read_text(encoding="utf-8")
    marker = re.search(r"static [^;{]*\b" + re.escape(name) + r"\([^;{]*\)\s*\{", text)
    if marker is None:
        raise ValueError(f"implementation of {name} not found")
    return function(SOURCE, marker.group())


AVTAB = r"""
typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned short uint16_t;
typedef unsigned int u32;
#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))
#define AVTAB_ALLOWED 0x0001
#define AVTAB_AUDITALLOW 0x0002
#define AVTAB_AUDITDENY 0x0004
#define AVTAB_XPERMS_ALLOWED 0x0100
#define AVTAB_XPERMS_AUDITALLOW 0x0200
#define AVTAB_XPERMS_DONTAUDIT 0x0400
#define AVTAB_XPERMS 0x0700
#define AVTAB_XPERMS_IOCTLFUNCTION 0x01
#define AVTAB_XPERMS_IOCTLDRIVER 0x02
#define GFP_KERNEL 0
#define pr_warn(...) ((void)0)
#define pr_err(...) ((void)0)
struct avtab_key { u16 source_type, target_type, target_class, specified; };
struct avtab_extended_perms {
    u8 specified, driver;
    union { u32 p[8]; } perms;
};
struct avtab_datum { union { u32 data; struct avtab_extended_perms *xperms; } u; };
struct avtab_node { struct avtab_key key; struct avtab_datum datum; struct avtab_node *next; };
struct avtab { struct avtab_node **htable; u32 nel, nslot; };
struct hashtab_node { void *datum; struct hashtab_node *next; };
struct hashtab { struct hashtab_node **htable; u32 size; };
struct symtab { struct hashtab table; };
struct policydb { struct avtab te_avtab; size_t len; struct symtab p_types, p_classes; };
struct type_datum { u32 value; bool attribute; };
struct class_datum { u32 value; };
#define ksu_hashtab_for_each(table, node) \
    for (u32 slot = 0; slot < (table).size; slot++) \
        for ((node) = (table).htable[slot]; (node); (node) = (node)->next)
static struct avtab_node pool[16], *buckets[1], *removed_buckets[1], *last_destroyed;
static struct avtab_extended_perms xpool[16], allocation;
static int used, insert_fail, alloc_fail, destroyed, destroyed_xperms;
static void reset(struct policydb *db) {
    memset(db, 0, sizeof(*db));
    memset(pool, 0, sizeof(pool));
    buckets[0] = NULL; db->te_avtab.htable = buckets; db->te_avtab.nslot = 1;
    used = insert_fail = alloc_fail = destroyed = destroyed_xperms = 0;
    last_destroyed = NULL;
}
static bool same_key(const struct avtab_key *a, const struct avtab_key *b) {
    return a->source_type == b->source_type && a->target_type == b->target_type &&
           a->target_class == b->target_class;
}
static struct avtab_node *avtab_search_node(struct avtab *tab, struct avtab_key *key) {
    for (struct avtab_node *n = tab->htable[0]; n; n = n->next)
        if (same_key(&n->key, key) && (n->key.specified & key->specified)) return n;
    return NULL;
}
static struct avtab_node *avtab_search_node_next(struct avtab_node *node, u16 specified) {
    for (struct avtab_node *n = node->next; n; n = n->next)
        if (same_key(&n->key, &node->key) && (n->key.specified & specified)) return n;
    return NULL;
}
static struct avtab_node *avtab_insert_nonunique(struct avtab *tab,
                                                struct avtab_key *key,
                                                struct avtab_datum *datum) {
    if (insert_fail || used == ARRAY_SIZE(pool)) return NULL;
    struct avtab_node *node = &pool[used];
    node->key = *key; node->datum = *datum;
    /* The real avtab insertion owns a copy, not the caller's stack xperms. */
    if (key->specified & AVTAB_XPERMS) {
        xpool[used] = *datum->u.xperms;
        node->datum.u.xperms = &xpool[used];
    }
    used++; node->next = tab->htable[0]; tab->htable[0] = node; tab->nel++;
    return node;
}
static int avtab_alloc(struct avtab *tab, u32 count) {
    (void)count;
    if (alloc_fail) return -12;
    removed_buckets[0] = NULL;
    tab->htable = removed_buckets; tab->nslot = 1; tab->nel = 0;
    return 0;
}
static void avtab_destroy(struct avtab *tab) {
    for (u32 i = 0; i < tab->nslot; i++) {
        for (struct avtab_node *n = tab->htable[i]; n; n = n->next) {
            destroyed++; last_destroyed = n;
            if ((n->key.specified & AVTAB_XPERMS) && n->datum.u.xperms) destroyed_xperms++;
        }
    }
}
static void *kzalloc(size_t size, int flags) {
    (void)size; (void)flags; memset(&allocation, 0, sizeof(allocation)); return &allocation;
}
"""


def removal_source():
    body = r"""
int main(void) {
    struct policydb db;
    struct avtab_node nodes[3], missing = {0};
    struct avtab_extended_perms xp = {0};
    const u16 effects[] = { AVTAB_ALLOWED, AVTAB_XPERMS_ALLOWED,
                           AVTAB_XPERMS_AUDITALLOW, AVTAB_XPERMS_DONTAUDIT };
    for (u32 e = 0; e < ARRAY_SIZE(effects); e++) {
        for (int position = 0; position < 3; position++) {
            reset(&db); memset(nodes, 0, sizeof(nodes));
            for (int i = 0; i < 3; i++) {
                nodes[i].key.specified = effects[e];
                if (effects[e] & AVTAB_XPERMS) nodes[i].datum.u.xperms = &xp;
                if (i < 2) nodes[i].next = &nodes[i + 1];
            }
            db.te_avtab.htable[0] = nodes; db.te_avtab.nel = 3;
            /* Start with an already serialized policy, independently of insertion accounting. */
            db.len = 1000;
            CHECK(remove_avtab_node(&db, &nodes[position]));
            CHECK(db.len == (e ? 958 : 988) && db.te_avtab.nel == 2);
            CHECK(destroyed == 1 && last_destroyed == &nodes[position]);
            CHECK(destroyed_xperms == (e ? 1 : 0) && nodes[position].next == NULL);
            struct avtab_node *cursor = db.te_avtab.htable[0];
            for (int i = 0; i < 3; i++) {
                if (i == position) continue;
                CHECK(cursor == &nodes[i]); cursor = cursor->next;
            }
            CHECK(cursor == NULL);
            size_t before = db.len;
            CHECK(!remove_avtab_node(&db, &missing));
            CHECK(db.len == before && db.te_avtab.nel == 2 && destroyed == 1);
            alloc_fail = 1; cursor = db.te_avtab.htable[0];
            CHECK(!remove_avtab_node(&db, cursor));
            CHECK(db.te_avtab.htable[0] == cursor && db.len == before);
            CHECK(db.te_avtab.nel == 2 && destroyed == 1);
        }
    }
    return 0;
}
"""
    return COMMON + AVTAB + implementation("remove_avtab_node") + body


def xperm_source():
    text = (ROOT / SOURCE).read_text(encoding="utf-8")
    macros = "\n".join(line for line in text.splitlines()
                       if re.match(r"#define (ioctl_driver|ioctl_func|xperm_\w+)\(", line))
    body = r"""
static struct avtab_node *lookup(struct policydb *db, int effect, u8 kind, u8 driver) {
    struct avtab_key key = { 1, 2, 3, effect };
    struct avtab_node *node = avtab_search_node(&db->te_avtab, &key);
    while (node) {
        if (node->datum.u.xperms->specified == kind && node->datum.u.xperms->driver == driver)
            return node;
        node = avtab_search_node_next(node, effect);
    }
    return NULL;
}
int main(void) {
    struct policydb db;
    struct type_datum src = {1, false}, tgt = {2, false};
    struct class_datum cls = {3};
    const int effects[] = { AVTAB_XPERMS_ALLOWED, AVTAB_XPERMS_AUDITALLOW, AVTAB_XPERMS_DONTAUDIT };
    reset(&db);
    for (u32 e = 0; e < ARRAY_SIZE(effects); e++) {
        add_xperm_rule_raw(&db, &src, &tgt, &cls, 0x541f, 0x5421, effects[e], false);
        struct avtab_node *node = lookup(&db, effects[e], AVTAB_XPERMS_IOCTLFUNCTION, 0x54);
        CHECK(node != NULL);
        CHECK(node->datum.u.xperms->perms.p[0] == 0x80000000U);
        CHECK(node->datum.u.xperms->perms.p[1] == 3);
        add_xperm_rule_raw(&db, &src, &tgt, &cls, 0x5480 + e, 0x5480 + e, effects[e], false);
        /* Every prior range must survive, including additions in different bitmap words. */
        CHECK(node->datum.u.xperms->perms.p[0] == 0x80000000U);
        CHECK(node->datum.u.xperms->perms.p[1] == 3);
        CHECK(node->datum.u.xperms->perms.p[4] == (1U << e));
        size_t before = db.len; u32 count = db.te_avtab.nel;
        add_xperm_rule_raw(&db, &src, &tgt, &cls, 0x5480 + e, 0x5480 + e, effects[e], false);
        CHECK(db.len == before && db.te_avtab.nel == count);
        add_xperm_rule_raw(&db, &src, &tgt, &cls, 0x5505, 0x5505, effects[e], false);
        struct avtab_node *other = lookup(&db, effects[e], AVTAB_XPERMS_IOCTLFUNCTION, 0x55);
        CHECK(other && other != node && other->datum.u.xperms->perms.p[0] == 0x20);
        CHECK(other->datum.u.xperms->perms.p[4] == 0);
    }
    CHECK(db.te_avtab.nel == 6);
    for (u32 e = 0; e < ARRAY_SIZE(effects); e++) {
        struct avtab_node *n = lookup(&db, effects[e], AVTAB_XPERMS_IOCTLFUNCTION, 0x54);
        CHECK(n->datum.u.xperms->perms.p[4] == (1U << e));
    }
    /* Driver ranges have a different kind from function ranges, even for driver zero. */
    add_xperm_rule_raw(&db, &src, &tgt, &cls, 0x0007, 0x0007, effects[0], false);
    add_xperm_rule_raw(&db, &src, &tgt, &cls, 0x1f00, 0x20ff, effects[0], false);
    add_xperm_rule_raw(&db, &src, &tgt, &cls, 0x8000, 0x81ff, effects[0], false);
    struct avtab_node *drivers = lookup(&db, effects[0], AVTAB_XPERMS_IOCTLDRIVER, 0);
    struct avtab_node *zero = lookup(&db, effects[0], AVTAB_XPERMS_IOCTLFUNCTION, 0);
    CHECK(drivers && zero && drivers != zero && db.te_avtab.nel == 8);
    CHECK(drivers->datum.u.xperms->perms.p[0] == 0x80000000U);
    CHECK(drivers->datum.u.xperms->perms.p[1] == 1 && drivers->datum.u.xperms->perms.p[4] == 3);
    CHECK(zero->datum.u.xperms->perms.p[0] == 0x80 && zero->datum.u.xperms->perms.p[4] == 0);
    size_t before = db.len;
    insert_fail = 1;
    add_xperm_rule_raw(&db, &src, &tgt, &cls, 0x6001, 0x6001, effects[0], false);
    CHECK(!lookup(&db, effects[0], AVTAB_XPERMS_IOCTLFUNCTION, 0x60));
    CHECK(db.len == before && db.te_avtab.nel == 8);
    return 0;
}
"""
    return (COMMON + AVTAB + macros + "\n" + implementation("get_avtab_node")
            + implementation("add_xperm_rule_raw") + body)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cc", default=os.environ.get("CC") or shutil.which("clang") or shutil.which("cc"))
    args = parser.parse_args()
    if not args.cc:
        parser.error("a C compiler is required; pass --cc")
    compiler = shutil.which(args.cc) or str(Path(args.cc).resolve())
    run_test(compiler, "sepolicy-avtab-removal", removal_source())
    run_test(compiler, "sepolicy-xperm-merge", xperm_source())
