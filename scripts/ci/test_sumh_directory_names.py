#!/usr/bin/env python3
"""Exercise SUMH directory deduplication against the production C helpers.

The host doubles cover name matching, first-entry precedence, list ordering and
allocation failures. Kernel RB-tree balancing and concurrent VFS behavior still
require a kernel build and device validation.
"""

import argparse
import os
from pathlib import Path
import shutil
import sys

sys.dont_write_bytecode = True
from test_sumh_vfs_helpers import COMMON, ROOT, function, run_test


PREAMBLE = r"""
typedef unsigned long long u64;
typedef long long loff_t;
typedef unsigned int dev_t;
typedef unsigned short umode_t;
#define NAME_MAX 255
#define GFP_KERNEL 1
#define GFP_ATOMIC 2
#define DT_DIR 4
#define DT_REG 8
#define DT_CHR 2
#define S_IFMT 0170000
#define LOOKUP_FOLLOW 1
#define STATX_TYPE 1
#define AT_STATX_SYNC_AS_STAT 0
#define S_ISCHR(m) (((m) & S_IFMT) == 0020000)
#define container_of(p, t, m) ((t *)((char *)(p) - __builtin_offsetof(t, m)))
struct list_head { struct list_head *next, *prev; };
static void INIT_LIST_HEAD(struct list_head *head) { head->next = head->prev = head; }
static void list_add(struct list_head *item, struct list_head *head) {
    item->next = head->next; item->prev = head;
    head->next->prev = item; head->next = item;
}
static void list_add_tail(struct list_head *item, struct list_head *head) {
    item->next = head; item->prev = head->prev;
    head->prev->next = item; head->prev = item;
}
#define list_for_each_entry(item, head, member) \
    for (struct list_head *cursor = (head)->next; \
         cursor != (head) && ((item) = container_of(cursor, __typeof__(*(item)), member), 1); \
         cursor = cursor->next)
struct rb_node { struct rb_node *rb_left, *rb_right; };
struct rb_root { struct rb_node *rb_node; };
#define RB_ROOT ((struct rb_root) { NULL })
#define rb_entry(p, t, m) container_of(p, t, m)
static void rb_link_node(struct rb_node *node, struct rb_node *parent,
                         struct rb_node **link) {
    (void)parent; node->rb_left = node->rb_right = NULL; *link = node;
}
/* Preserve the binary-search topology; kernel-owned balancing is not under test. */
static void rb_insert_color(struct rb_node *node, struct rb_root *root) {
    (void)node; (void)root;
}
static int memcmp(const void *left, const void *right, size_t length) {
    const unsigned char *a = left, *b = right;
    for (size_t i = 0; i < length; i++) if (a[i] != b[i]) return a[i] - b[i];
    return 0;
}
static char *strchr(const char *text, int needle) {
    do { if (*text == needle) return (char *)text; } while (*text++);
    return NULL;
}
struct allocation { unsigned char bytes[1024]; };
static struct allocation allocations[64];
static int alloc_count, alloc_attempts, fail_at, frees, rcu_depth, bad_alloc;
static void *kmalloc(size_t bytes, int flags) {
    alloc_attempts++;
    if (rcu_depth && flags != GFP_ATOMIC) bad_alloc++;
    if (alloc_attempts == fail_at || bytes > sizeof(allocations[0]) || alloc_count == 64)
        return NULL;
    return allocations[alloc_count++].bytes;
}
static void kfree(void *memory) { if (memory) frees++; }
static char *kstrndup(const char *text, size_t len, int flags) {
    char *copy = kmalloc(len + 1, flags);
    if (copy) { memcpy(copy, text, len); copy[len] = 0; }
    return copy;
}
static void rcu_read_lock(void) { rcu_depth++; }
static void rcu_read_unlock(void) { rcu_depth--; }
"""


def helpers():
    types = (ROOT / "kernel/sumh/internal/sumh_types.h").read_text(encoding="utf-8")
    start = types.index("struct sumh_name_list {")
    structure = types[start:types.index("};", start) + 2]
    source = "\n".join(
        function("kernel/sumh/internal/sumh_name_index.h", marker)
        for marker in (
            "static inline int sumh_name_compare",
            "static inline bool sumh_name_index_contains",
            "static inline void sumh_name_index_add",
            "static inline void sumh_name_index_init",
        )
    )
    count = r"""
static int list_count(struct list_head *head) {
    struct sumh_name_list *item; int count = 0;
    list_for_each_entry(item, head, list) count++;
    return count;
}
static struct sumh_name_list *at(struct list_head *head, int index) {
    struct sumh_name_list *item;
    list_for_each_entry(item, head, list) if (!index--) return item;
    return NULL;
}
"""
    return COMMON + PREAMBLE + structure + source + count


def index_source():
    body = r"""
int main(void) {
    struct list_head head;
    struct rb_root names;
    struct sumh_name_list first = { .name = "alpha", .ino = 17 };
    struct sumh_name_list duplicate = { .name = "alpha", .ino = 99 };
    struct sumh_name_list prefix = { .name = "alphabet" };
    struct sumh_name_list high_byte = { .name = "\200leaf" };
    static struct sumh_name_list many[128];
    static char labels[128][4];
    const char bounded[] = { 'a', 'l', 'p', 'h', 'a', 'X' };
    INIT_LIST_HEAD(&head);
    list_add_tail(&first.list, &head);
    list_add_tail(&duplicate.list, &head);
    list_add_tail(&prefix.list, &head);
    list_add_tail(&high_byte.list, &head);
    sumh_name_index_init(&names, &head);
    CHECK(list_count(&head) == 4 && at(&head, 0) == &first && at(&head, 1) == &duplicate);
    CHECK(at(&head, 2) == &prefix && at(&head, 3) == &high_byte);
    CHECK(first.name_len == 5 && prefix.name_len == 8 && duplicate.name_len == 5);
    CHECK(sumh_name_index_contains(&names, bounded, 5));
    CHECK(!sumh_name_index_contains(&names, bounded, 6));
    CHECK(!sumh_name_index_contains(&names, "alph", 4));
    CHECK(!sumh_name_index_contains(&names, "alphabets", 9));
    CHECK(sumh_name_index_contains(&names, "alphabet", 8));
    CHECK(sumh_name_index_contains(&names, "\200leaf", 5));
    CHECK(!sumh_name_index_contains(&names, "\177leaf", 5));
    CHECK(rb_entry(names.rb_node, struct sumh_name_list, name_node)->ino == 17);
    /* Rebuild an index from a previously indexed list, without retaining its root. */
    sumh_name_index_init(&names, &head);
    CHECK(sumh_name_index_contains(&names, bounded, 5) && list_count(&head) == 4);
    for (int reverse = 0; reverse <= 1; reverse++) {
        INIT_LIST_HEAD(&head);
        for (int i = 0; i < 128; i++) {
            int index = reverse ? 127 - i : i;
            labels[index][0] = 'n';
            labels[index][1] = 'A' + index / 16;
            labels[index][2] = 'A' + index % 16;
            labels[index][3] = 0;
            many[index].name = labels[index];
            list_add_tail(&many[index].list, &head);
        }
        sumh_name_index_init(&names, &head);
        CHECK(list_count(&head) == 128 && at(&head, 0) == &many[reverse ? 127 : 0]);
        for (int i = 0; i < 128; i++)
            CHECK(sumh_name_index_contains(&names, labels[i], 3));
        CHECK(!sumh_name_index_contains(&names, "nZZ", 3));
    }
    CHECK(!alloc_attempts);
    return 0;
}
"""
    return helpers() + body


def merge_source():
    preamble = r"""
struct dir_context {
    bool (*actor)(struct dir_context *, const char *, int, loff_t, u64, unsigned int);
};
struct sumh_merge_ctx {
    struct dir_context ctx; struct list_head *head; struct rb_root *names;
    const char *dir_path; dev_t dir_dev;
};
struct path { int unused; };
struct kstat { umode_t mode; unsigned int rdev; };
static char *kasprintf(int flags, const char *format, ...) {
    (void)flags; (void)format; return NULL;
}
static int kern_path(const char *name, int flags, struct path *path) {
    (void)name; (void)flags; (void)path; return -1;
}
static int sumh_vfs_getattr_unprojected(struct path *path, struct kstat *stat,
                                     int mask, int flags) {
    (void)path; (void)stat; (void)mask; (void)flags; return -1;
}
static void path_put(struct path *path) { (void)path; }
static u64 sumh_vnode_source_ino(dev_t dev, u64 ino) { return dev + ino; }
"""
    body = r"""
int main(void) {
    struct list_head head;
    struct rb_root names;
    struct sumh_name_list first = { .name = "alpha", .ino = 17, .type = DT_DIR };
    struct sumh_merge_ctx ctx = { .head = &head, .names = &names, .dir_dev = 100 };
    const char bounded[] = { 'a', 'l', 'p', 'h', 'a', 'X' };
    INIT_LIST_HEAD(&head); list_add(&first.list, &head);
    sumh_name_index_init(&names, &head);
    CHECK(sumh_merge_filldir(&ctx.ctx, bounded, 5, 0, 99, DT_REG));
    CHECK(list_count(&head) == 1 && first.ino == 17 && first.type == DT_DIR);
    CHECK(!alloc_attempts);
    CHECK(sumh_merge_filldir(&ctx.ctx, bounded, 6, 0, 42, DT_REG));
    CHECK(list_count(&head) == 2 && !strcmp(at(&head, 0)->name, "alphaX"));
    CHECK(at(&head, 0)->ino == 142 && at(&head, 1) == &first && alloc_attempts == 2);
    CHECK(sumh_merge_filldir(&ctx.ctx, bounded, 6, 0, 88, DT_DIR));
    CHECK(alloc_attempts == 2 && at(&head, 0)->ino == 142);
    CHECK(sumh_merge_filldir(&ctx.ctx, ".", 1, 0, 1, DT_DIR));
    CHECK(sumh_merge_filldir(&ctx.ctx, "..", 2, 0, 1, DT_DIR));
    CHECK(sumh_merge_filldir(&ctx.ctx, ".replace", 8, 0, 1, DT_REG));
    CHECK(sumh_merge_filldir(&ctx.ctx, "", 0, 0, 1, DT_REG));
    CHECK(sumh_merge_filldir(&ctx.ctx, "", -1, 0, 1, DT_REG));
    CHECK(sumh_merge_filldir(&ctx.ctx, "", NAME_MAX + 1, 0, 1, DT_REG));
    CHECK(alloc_attempts == 2 && list_count(&head) == 2);
    fail_at = alloc_attempts + 1;
    CHECK(sumh_merge_filldir(&ctx.ctx, "later", 5, 0, 1, DT_REG));
    CHECK(!sumh_name_index_contains(&names, "later", 5) && list_count(&head) == 2);
    fail_at = alloc_attempts + 2;
    CHECK(sumh_merge_filldir(&ctx.ctx, "later", 5, 0, 1, DT_REG));
    CHECK(!sumh_name_index_contains(&names, "later", 5) && frees == 1);
    fail_at = 0;
    CHECK(sumh_merge_filldir(&ctx.ctx, "later", 5, 0, 1, DT_REG));
    CHECK(sumh_name_index_contains(&names, "later", 5) && list_count(&head) == 3);
    CHECK(!strcmp(at(&head, 0)->name, "later") && at(&head, 2) == &first);
    return 0;
}
"""
    actor = function("kernel/sumh/overlay/sumh_overlay.c", "static SUMH_NOCFI bool sumh_merge_filldir")
    return helpers() + preamble + actor + body


def virtual_source():
    preamble = r"""
struct sumh_entry {
    const char *src; bool source_nofollow_path_valid;
    umode_t source_nofollow_mode, source_mode;
    u64 nofollow_visible_ino, visible_ino;
};
static struct sumh_entry rules[] = {
    { .src = "/vdir/first", .source_mode = 0100000, .visible_ino = 11 },
    { .src = "/vdir/sub/one" },
    { .src = "/vdir/sub/two" },
    { .src = "/vdir/first", .source_mode = 0040000, .visible_ino = 99 },
    { .src = "/vdir/prefix", .source_nofollow_path_valid = true,
      .source_nofollow_mode = 0120000, .nofollow_visible_ino = 31 },
    { .src = "/vdir/prefix-long", .source_mode = 0100000, .visible_ino = 41 },
    { .src = "/other/path" }, { .src = NULL }, { .src = "/vdir/" },
};
#define hash_for_each_rcu(table, bucket, item, member) \
    for ((bucket) = 0; (bucket) < (int)(sizeof(rules) / sizeof(rules[0])) && \
         ((item) = &rules[bucket], 1); (bucket)++)
static u64 sumh_vnode_vpath_ino(const char *path) {
    return strcmp(path, "/vdir/sub") ? 0 : 71;
}
"""
    body = r"""
int main(void) {
    struct list_head head;
    struct sumh_name_list seed = { .name = "first", .ino = 17, .type = DT_DIR };
    INIT_LIST_HEAD(&head); list_add_tail(&seed.list, &head);
    CHECK(sumh_rule_vpath_emit("/vdir", &head) == 3);
    CHECK(list_count(&head) == 4 && at(&head, 0) == &seed);
    CHECK(seed.ino == 17 && seed.type == DT_DIR);
    CHECK(!strcmp(at(&head, 1)->name, "sub") && at(&head, 1)->ino == 71);
    CHECK(at(&head, 1)->type == DT_DIR && at(&head, 1)->name_len == 3);
    CHECK(!strcmp(at(&head, 2)->name, "prefix") && at(&head, 2)->ino == 31);
    CHECK(at(&head, 2)->type == 10 && at(&head, 3)->ino == 41);
    CHECK(!rcu_depth && !bad_alloc);
    int previous = alloc_attempts;
    CHECK(sumh_rule_vpath_emit("/vdir", &head) == 0);
    CHECK(list_count(&head) == 4 && alloc_attempts == previous);
    CHECK(!sumh_rule_vpath_emit(NULL, &head) && !sumh_rule_vpath_emit("/vdir", NULL));
    INIT_LIST_HEAD(&head); alloc_count = alloc_attempts = frees = 0;
    fail_at = 2; /* The first name copy fails; a later duplicate may still win. */
    CHECK(sumh_rule_vpath_emit("/vdir", &head) == 4);
    CHECK(!strcmp(at(&head, 0)->name, "sub") && at(&head, 1)->ino == 99);
    CHECK(frees == 2 && !rcu_depth && !bad_alloc);
    return 0;
}
"""
    emit = function("kernel/sumh/policy/sumh_path_policy.c", "int SUMH_NOCFI sumh_rule_vpath_emit")
    return helpers() + preamble + emit + body


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cc", default=os.environ.get("CC") or shutil.which("clang") or shutil.which("cc"))
    args = parser.parse_args()
    if not args.cc:
        parser.error("a C compiler is required; pass --cc")
    compiler = shutil.which(args.cc) or str(Path(args.cc).resolve())
    run_test(compiler, "directory-name-index", index_source())
    run_test(compiler, "directory-name-merge", merge_source())
    run_test(compiler, "directory-name-virtual", virtual_source())
