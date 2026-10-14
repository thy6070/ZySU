#!/usr/bin/env python3
"""Exercise SUMH kstat ownership, replacement and projection on the host.

Extracts production C functions and supplies small kernel API doubles. Covers
failure atomicity and deferred RCU retirement, not real kernel concurrency.
"""

import argparse
import os
from pathlib import Path
import re
import shutil
import sys

sys.dont_write_bytecode = True
from test_sumh_vfs_helpers import COMMON, function, run_test


def kstat_source():
    preamble = r"""
typedef unsigned int u32;
typedef unsigned long long u64;
typedef unsigned int dev_t;
typedef int atomic_t;
#define __user
#define ENOENT 2
#define EIO 5
#define ENOMEM 12
#define EFAULT 14
#define EEXIST 17
#define EINVAL 22
#define GFP_KERNEL 0
#define LOOKUP_FOLLOW 1
#define SUMH_HASH_BITS 4
#define SUMH_MAX_LEN_PATHNAME 256
#define AS_FLAGS_SUMH_SPOOF_KSTAT 2
#define READ_ONCE(value) (value)
#define GLOBAL_ROOT_UID 0
#define GLOBAL_ROOT_GID 0
#define S_IFREG 0100000
#define S_ISREG(mode) (((mode) & 0170000) == S_IFREG)
#define sumh_log(...) ((void)0)
#define ARRAY_SIZE(value) (sizeof(value) / sizeof((value)[0]))
#define container_of(ptr, type, member) \
    ((type *)((char *)(ptr) - __builtin_offsetof(type, member)))
#define hash_min(value, bits) ((value) & ((1U << (bits)) - 1))
struct hlist_node { struct hlist_node *next, **pprev; };
struct hlist_head { struct hlist_node *first; };
struct rcu_head { struct rcu_head *next; void (*func)(struct rcu_head *); };
#define hlist_for_each_entry(pos, head, member) \
    for (struct hlist_node *walk = (head)->first; \
         walk && ((pos) = container_of(walk, __typeof__(*(pos)), member), 1); \
         walk = walk->next)
#define hlist_for_each_entry_rcu(pos, head, member) \
    hlist_for_each_entry(pos, head, member)
#define hash_for_each_rcu(table, bucket, pos, member) \
    for ((bucket) = 0; (bucket) < ARRAY_SIZE(table); (bucket)++) \
        hlist_for_each_entry_rcu(pos, &(table)[bucket], member)
static struct hlist_head sumh_spoof_kstat_path[1 << SUMH_HASH_BITS];
static struct hlist_head sumh_spoof_kstat_ino[1 << SUMH_HASH_BITS];
static struct hlist_head sumh_paths[1 << SUMH_HASH_BITS];
static atomic_t sumh_spoof_kstat_count;
static int sumh_config_mutex, lock_depth, rcu_depth, bad_context;
static int atomic_read(atomic_t *value) { return *value; }
static void atomic_inc(atomic_t *value) { (*value)++; }
static void mutex_lock(int *mutex) {
    if (mutex != &sumh_config_mutex || lock_depth) bad_context++;
    lock_depth++;
}
static void mutex_unlock(int *mutex) {
    if (mutex != &sumh_config_mutex || lock_depth != 1) bad_context++;
    lock_depth--;
}
static void rcu_read_lock(void) { rcu_depth++; }
static void rcu_read_unlock(void) { if (!rcu_depth) bad_context++; rcu_depth--; }
static void hlist_add_head_rcu(struct hlist_node *node, struct hlist_head *head) {
    if (lock_depth != 1) bad_context++;
    node->next = head->first;
    if (node->next) node->next->pprev = &node->next;
    head->first = node; node->pprev = &head->first;
}
static void hlist_del_rcu(struct hlist_node *node) {
    if (lock_depth != 1 || !node->pprev) bad_context++;
    *node->pprev = node->next;
    if (node->next) node->next->pprev = node->pprev;
    node->pprev = NULL;
}
static void hlist_replace_rcu(struct hlist_node *old, struct hlist_node *node) {
    if (lock_depth != 1 || !old->pprev) bad_context++;
    node->next = old->next; node->pprev = old->pprev;
    if (node->next) node->next->pprev = &node->next;
    *node->pprev = node;
}
static struct rcu_head *retired;
static int retired_count;
static void call_rcu(struct rcu_head *head, void (*callback)(struct rcu_head *)) {
    if (lock_depth != 1) bad_context++;
    head->next = retired; head->func = callback; retired = head; retired_count++;
}
static void synchronize_rcu(void) {
    if (lock_depth || rcu_depth) bad_context++;
    while (retired) {
        struct rcu_head *head = retired; retired = head->next;
        retired_count--; head->func(head);
    }
}
static u32 full_name_hash(const void *salt, const char *name, size_t len) {
    u32 hash = 0; (void)salt;
    for (size_t i = 0; i < len; i++) hash = hash * 31 + (unsigned char)name[i];
    return hash;
}
static unsigned char allocations[128][1024] __attribute__((aligned(16)));
static bool allocated[128];
static int live, alloc_calls, alloc_fail_at;
static void *kmalloc(size_t size, int flags) {
    (void)flags;
    if (++alloc_calls == alloc_fail_at) return NULL;
    if (size > sizeof(allocations[0])) { bad_context++; return NULL; }
    for (size_t i = 0; i < ARRAY_SIZE(allocated); i++) if (!allocated[i]) {
        allocated[i] = true; live++; return allocations[i];
    }
    return NULL;
}
static void *kzalloc(size_t size, int flags) {
    void *result = kmalloc(size, flags);
    if (result) memset(result, 0, size);
    return result;
}
static void kfree(void *ptr) {
    if (!ptr) return;
    for (size_t i = 0; i < ARRAY_SIZE(allocated); i++) if (allocations[i] == ptr && allocated[i]) {
        allocated[i] = false; live--; memset(ptr, 0xa5, sizeof(allocations[i])); return;
    }
    bad_context++;
}
static char *kstrdup(const char *value, int flags) {
    size_t len = strlen(value) + 1; char *copy = kmalloc(len, flags);
    if (copy) memcpy(copy, value, len);
    return copy;
}
static int from_fault, reply_fault, copy_calls;
static int copy_from_user(void *out, const void *in, size_t size) {
    if (from_fault) return 1;
    memcpy(out, in, size); return 0;
}
static int copy_to_user(void *out, const void *in, size_t size) {
    copy_calls++;
    if (reply_fault) return 1;
    memcpy(out, in, size); return 0;
}
struct super_block { dev_t s_dev; bool native; };
struct address_space { unsigned long flags; };
struct inode { unsigned long i_ino; struct super_block *i_sb; struct address_space *i_mapping; };
struct dentry { struct inode *inode; };
struct path { struct dentry *dentry; };
struct sumh_entry {
    struct inode *source_inode; struct path source_nofollow_path;
    bool source_path_valid, source_nofollow_path_valid; struct hlist_node node;
};
struct timespec64 { long long tv_sec; long tv_nsec; };
struct kstat {
    u64 ino; dev_t dev; unsigned int nlink; long long size;
    unsigned int blksize; u64 blocks; unsigned int mode, uid, gid;
    struct timespec64 atime, mtime, ctime;
};
struct path_fixture { const char *name; struct dentry dentry; };
static struct path_fixture files[12];
static int file_count, path_refs, resolve_error, install_error, install_calls;
static int kern_path(const char *name, int flags, struct path *path) {
    if (flags != LOOKUP_FOLLOW) bad_context++;
    if (resolve_error) return resolve_error;
    for (int i = 0; i < file_count; i++) if (!strcmp(files[i].name, name)) {
        path->dentry = &files[i].dentry; path_refs++; return 0;
    }
    return -ENOENT;
}
static void path_put(const struct path *path) {
    (void)path; if (path_refs < 1) bad_context++; path_refs--;
}
static struct inode *d_inode(struct dentry *dentry) { return dentry ? dentry->inode : NULL; }
static int sumh_iop_install(struct inode *inode) {
    if (!inode || !path_refs) bad_context++;
    install_calls++; return install_error;
}
static void set_bit(unsigned int bit, unsigned long *flags) { *flags |= 1UL << bit; }
static int internal, view_target = 1, vnode_calls;
static dev_t sumh_system_dev = 90, vnode_dev;
static u64 vnode_ino;
static bool sumh_vfs_internal_current(void) { return internal; }
static bool sumh_policy_current_is_view_target(void) { return view_target; }
static u64 sumh_vnode_source_ino(dev_t dev, u64 ino) {
    vnode_calls++; vnode_dev = dev; vnode_ino = ino; return 9000 + ino;
}
"""
    structs = "\n".join(function(path, marker) + ";" for path, marker in (
        ("uapi/sumh.h", "struct sumh_spoof_kstat {"),
        ("kernel/sumh/core/sumh_store.h", "struct sumh_spoof_kstat_entry {"),
    ))
    methods = "\n".join(function("kernel/sumh/core/sumh_runtime.c", marker) for marker in (
        "void sumh_spoof_kstat_entry_free_rcu(",
        "struct sumh_spoof_kstat_entry *\nsumh_spoof_kstat_lookup_by_path(",
        "struct sumh_spoof_kstat_entry *sumh_spoof_kstat_lookup_by_ino(",
    ))
    methods += function("kernel/sumh/control/sumh_ioctl.c", "static int sumh_kstat_set(")
    methods += "\n".join(function("kernel/sumh/hooks/sumh_vfs_hooks.c", marker) for marker in (
        "static bool sumh_kstat_has_redirect_source(",
        "void sumh_apply_kstat_spoof(",
    ))
    body = r"""
static void request(struct sumh_spoof_kstat *rule, const char *path, unsigned long ino, unsigned long spoof) {
    memset(rule, 0, sizeof(*rule));
    if (path) memcpy(rule->target_pathname, path, strlen(path) + 1);
    rule->target_ino = ino; rule->spoofed_ino = spoof;
}
static void reset_faults(void) {
    alloc_calls = alloc_fail_at = from_fault = reply_fault = copy_calls = 0;
    resolve_error = install_error = install_calls = 0;
}
static bool clean(void) { return !bad_context && !lock_depth && !path_refs && !rcu_depth; }
static unsigned long selected(unsigned long ino, unsigned long dev) {
    struct sumh_spoof_kstat_entry *entry; unsigned long result;
    rcu_read_lock(); entry = sumh_spoof_kstat_lookup_by_ino(ino, dev);
    result = entry ? entry->spoofed_ino : 0; rcu_read_unlock(); return result;
}
static int indexed_count(void) {
    int result = 0; struct sumh_spoof_kstat_entry *entry;
    for (size_t b = 0; b < ARRAY_SIZE(sumh_spoof_kstat_ino); b++)
        hlist_for_each_entry(entry, &sumh_spoof_kstat_ino[b], ino_node) result++;
    return result;
}
static void cleanup(void) {
    mutex_lock(&sumh_config_mutex);
    for (size_t b = 0; b < ARRAY_SIZE(sumh_spoof_kstat_ino); b++) {
        while (sumh_spoof_kstat_ino[b].first) {
            struct sumh_spoof_kstat_entry *entry = container_of(sumh_spoof_kstat_ino[b].first, struct sumh_spoof_kstat_entry, ino_node);
            hlist_del_rcu(&entry->ino_node);
            if (entry->target_pathname) hlist_del_rcu(&entry->path_node);
            call_rcu(&entry->rcu, sumh_spoof_kstat_entry_free_rcu);
        }
    }
    mutex_unlock(&sumh_config_mutex); synchronize_rcu(); sumh_spoof_kstat_count = 0;
}
static struct super_block sb_a = { 7 }, sb_b = { 8 }, sb_zero = { 0 };
static struct address_space map_a, map_b, map_zero, map_new;
static struct inode inode_a = { 17, &sb_a, &map_a }, inode_b = { 17, &sb_b, &map_b };
static struct inode inode_zero = { 17, &sb_zero, &map_zero }, inode_new = { 33, &sb_b, &map_new };
static int mutation_tests(void) {
    struct sumh_spoof_kstat rule;
    struct sumh_spoof_kstat_entry *snapshot, *current;
    int before, count;
    files[0] = (struct path_fixture){ "/a", { &inode_a } };
    files[1] = (struct path_fixture){ "/b", { &inode_b } };
    files[2] = (struct path_fixture){ "/zero", { &inode_zero } };
    files[3] = (struct path_fixture){ "/alias", { &inode_a } };
    file_count = 4;

    /* Path identity includes the real device; numeric rules are wildcards. */
    request(&rule, NULL, 17, 100); CHECK(sumh_kstat_set(&rule) == 0);
    request(&rule, "/a", 999, 101); CHECK(sumh_kstat_set(&rule) == 0 && rule.target_ino == 17);
    request(&rule, "/b", 0, 102); CHECK(sumh_kstat_set(&rule) == 0);
    request(&rule, "/zero", 0, 103); CHECK(sumh_kstat_set(&rule) == 0);
    CHECK(indexed_count() == 4 && sumh_spoof_kstat_count == 4);
    CHECK(selected(17, 7) == 101 && selected(17, 8) == 102);
    CHECK(selected(17, 0) == 103 && selected(17, 99) == 100);
    CHECK(!selected(0, 0) && !selected(18, 7));
    request(&rule, NULL, 17, 104); CHECK(sumh_kstat_set(&rule) == 0);
    CHECK(selected(17, 7) == 101 && selected(17, 8) == 102);
    CHECK(selected(17, 0) == 103 && selected(17, 99) == 104);
    synchronize_rcu();
    CHECK(!sumh_spoof_kstat_lookup_by_path(NULL) && !sumh_spoof_kstat_lookup_by_path(""));
    CHECK(!sumh_spoof_kstat_lookup_by_path("/missing"));

    /* Aliases must not silently steal another path's current identity. */
    before = live; request(&rule, "/alias", 17, 999);
    CHECK(sumh_kstat_set(&rule) == -EEXIST && rule.err == -EEXIST);
    CHECK(!sumh_spoof_kstat_lookup_by_path("/alias") && selected(17, 7) == 101);
    CHECK(live == before && !retired_count && clean());

    /* UPDATE uses a present path even when userspace retains its old inode. */
    snapshot = sumh_spoof_kstat_lookup_by_path("/a");
    files[0].dentry.inode = &inode_new;
    request(&rule, "/a", 17, 105); rule.spoofed_size = 4096; rule.is_static = 1;
    CHECK(sumh_kstat_set(&rule) == 0 && rule.target_ino == 33);
    current = sumh_spoof_kstat_lookup_by_path("/a");
    CHECK(current != snapshot && current->target_ino == 33 && current->target_dev == 8);
    CHECK(current->spoofed_size == 4096 && current->is_static == 1);
    CHECK(snapshot->target_ino == 17 && snapshot->target_dev == 7 && snapshot->spoofed_ino == 101);
    CHECK(snapshot->spoofed_size == 0 && !strcmp(snapshot->target_pathname, "/a"));
    CHECK(retired_count == 1 && selected(17, 7) == 104 && selected(33, 8) == 105);
    CHECK(!selected(33, 7) && indexed_count() == 4 && sumh_spoof_kstat_count == 4);
    synchronize_rcu();

    /* Same-inode updates also publish a distinct immutable snapshot. */
    snapshot = current; rule.spoofed_size = 8192; rule.spoofed_ino = 106;
    CHECK(sumh_kstat_set(&rule) == 0);
    current = sumh_spoof_kstat_lookup_by_path("/a");
    CHECK(current != snapshot && current->spoofed_size == 8192 && current->spoofed_ino == 106);
    CHECK(snapshot->spoofed_size == 4096 && snapshot->spoofed_ino == 105);
    CHECK(retired_count == 1); synchronize_rcu();

    /* Every fallible preparation step leaves the old rule and flags intact. */
    before = live; count = indexed_count();
    for (int fault = 1; fault <= 3; fault++) {
        reset_faults(); alloc_fail_at = fault; request(&rule, "/a", 33, 777);
        CHECK(sumh_kstat_set(&rule) == -ENOMEM);
        CHECK(sumh_spoof_kstat_lookup_by_path("/a") == current);
        CHECK(selected(33, 8) == 106 && live == before && indexed_count() == count && clean());
    }
    reset_faults(); from_fault = 1;
    CHECK(sumh_kstat_set(&rule) == -EFAULT && !copy_calls);
    reset_faults(); resolve_error = -ENOENT;
    CHECK(sumh_kstat_set(&rule) == -ENOENT && rule.err == -ENOENT);
    reset_faults(); install_error = -EIO;
    CHECK(sumh_kstat_set(&rule) == -EIO && install_calls == 1);
    CHECK(map_new.flags & (1UL << AS_FLAGS_SUMH_SPOOF_KSTAT));
    reset_faults(); reply_fault = 1;
    CHECK(sumh_kstat_set(&rule) == -EFAULT);
    CHECK(sumh_spoof_kstat_lookup_by_path("/a") == current);
    CHECK(selected(33, 8) == 106 && live == before && indexed_count() == count && !retired_count && clean());

    /* A failed rebind cannot mark the new inode or retire the prior identity. */
    reset_faults(); files[0].dentry.inode = &inode_a; map_a.flags = 0; reply_fault = 1;
    CHECK(sumh_kstat_set(&rule) == -EFAULT && !map_a.flags);
    CHECK(selected(33, 8) == 106 && selected(17, 7) == 104);
    reset_faults(); install_error = -EIO;
    CHECK(sumh_kstat_set(&rule) == -EIO && !map_a.flags);
    files[0].dentry.inode = &inode_new; reset_faults();

    /* Invalid input and invalid resolved objects never alter either index. */
    request(&rule, "relative", 33, 777); CHECK(sumh_kstat_set(&rule) == -EINVAL);
    request(&rule, "/a", 33, 777); memset(rule.target_pathname, 'x', sizeof(rule.target_pathname));
    CHECK(sumh_kstat_set(&rule) == -EINVAL);
    request(&rule, NULL, 0, 777); CHECK(sumh_kstat_set(&rule) == -EINVAL);
    request(&rule, "/a", 33, 777); rule.spoofed_size = -1; CHECK(sumh_kstat_set(&rule) == -EINVAL);
    for (int field = 0; field < 3; field++) {
        request(&rule, "/a", 33, 777);
        long *nsec = field == 0 ? &rule.spoofed_atime_nsec : field == 1 ? &rule.spoofed_mtime_nsec : &rule.spoofed_ctime_nsec;
        *nsec = -1; CHECK(sumh_kstat_set(&rule) == -EINVAL);
        *nsec = 1000000000L; CHECK(sumh_kstat_set(&rule) == -EINVAL);
    }
#ifdef SUMH_TEST_LP64
    request(&rule, "/a", 33, 777); rule.spoofed_dev = 0x100000000ULL;
    CHECK(sumh_kstat_set(&rule) == -EINVAL);
    request(&rule, "/a", 33, 777); rule.spoofed_blksize = 0x100000000ULL;
    CHECK(sumh_kstat_set(&rule) == -EINVAL);
#endif
    request(&rule, "/a", 33, 777); files[0].dentry.inode = NULL;
    CHECK(sumh_kstat_set(&rule) == -EINVAL); files[0].dentry.inode = &inode_new;
    inode_new.i_mapping = NULL; CHECK(sumh_kstat_set(&rule) == -EINVAL); inode_new.i_mapping = &map_new;
    inode_new.i_sb = NULL; CHECK(sumh_kstat_set(&rule) == -EINVAL); inode_new.i_sb = &sb_b;
    inode_new.i_ino = 0; CHECK(sumh_kstat_set(&rule) == -EINVAL); inode_new.i_ino = 33;
    CHECK(selected(33, 8) == 106 && live == before && indexed_count() == count && !retired_count && clean());

    cleanup(); CHECK(!live && !indexed_count() && !retired_count && clean());
    return 0;
}
static struct kstat real_stat(void) {
    struct kstat stat = {0};
    stat.ino = 200; stat.dev = 20; stat.nlink = 3; stat.size = 100;
    stat.blksize = 4096; stat.blocks = 8; stat.mode = S_IFREG | 0644;
    stat.uid = 1000; stat.gid = 1001;
    stat.atime = (struct timespec64){ 1, 2 };
    stat.mtime = (struct timespec64){ 3, 4 };
    stat.ctime = (struct timespec64){ 5, 6 };
    return stat;
}
static bool same_stat(const struct kstat *a, const struct kstat *b) {
    /* Compare fields rather than padding, which is not part of the contract. */
    return a->ino == b->ino && a->dev == b->dev && a->nlink == b->nlink &&
        a->size == b->size && a->blksize == b->blksize && a->blocks == b->blocks &&
        a->mode == b->mode && a->uid == b->uid && a->gid == b->gid &&
        a->atime.tv_sec == b->atime.tv_sec && a->atime.tv_nsec == b->atime.tv_nsec &&
        a->mtime.tv_sec == b->mtime.tv_sec && a->mtime.tv_nsec == b->mtime.tv_nsec &&
        a->ctime.tv_sec == b->ctime.tv_sec && a->ctime.tv_nsec == b->ctime.tv_nsec;
}
static int projection_tests(void) {
    struct sumh_spoof_kstat rule;
    struct kstat stat = real_stat(), before;
    struct sumh_entry redirect = {0};
    files[0].dentry.inode = &inode_a;

    /* The inode's actual superblock selects the rule even if getattr reports
     * another device or a virtual inode. Zero spoof fields preserve values. */
    request(&rule, "/a", 0, 501); CHECK(sumh_kstat_set(&rule) == 0);
    request(&rule, "/b", 0, 502); CHECK(sumh_kstat_set(&rule) == 0);
    stat.dev = sb_b.s_dev; stat.ino = 99999;
    before = stat; sumh_apply_kstat_spoof(&inode_a, &stat);
    CHECK(stat.ino == 501 && stat.dev == sb_b.s_dev && !vnode_calls);
    before.ino = 501; before.uid = GLOBAL_ROOT_UID; before.gid = GLOBAL_ROOT_GID;
    CHECK(same_stat(&stat, &before));

    /* Each declared override reaches the existing projection; timestamp zero
     * seconds with nonzero nanoseconds is a real override, not a sentinel. */
    request(&rule, "/a", 17, 503);
    rule.spoofed_dev = 55; rule.spoofed_nlink = 2; rule.spoofed_size = 123456;
    rule.spoofed_blksize = 8192; rule.spoofed_blocks = 32;
    rule.spoofed_atime_sec = 0; rule.spoofed_atime_nsec = 999999999L;
    rule.spoofed_mtime_sec = -50; rule.spoofed_mtime_nsec = 25;
    rule.spoofed_ctime_sec = 30; rule.spoofed_ctime_nsec = 0;
    CHECK(sumh_kstat_set(&rule) == 0); synchronize_rcu();
    stat = real_stat(); sumh_apply_kstat_spoof(&inode_a, &stat);
    CHECK(stat.ino == 503 && stat.dev == 55 && stat.nlink == 2 && stat.size == 123456);
    CHECK(stat.blksize == 8192 && stat.blocks == 32 && stat.mode == (S_IFREG | 0644));
    CHECK(stat.atime.tv_sec == 0 && stat.atime.tv_nsec == 999999999L);
    CHECK(stat.mtime.tv_sec == -50 && stat.mtime.tv_nsec == 25);
    CHECK(stat.ctime.tv_sec == 30 && stat.ctime.tv_nsec == 0);
    CHECK(stat.uid == GLOBAL_ROOT_UID && stat.gid == GLOBAL_ROOT_GID && clean());

    /* Internal resolution and non-target readers stay unchanged. */
    stat = before = real_stat(); internal = 1; sumh_apply_kstat_spoof(&inode_a, &stat);
    CHECK(same_stat(&stat, &before)); internal = 0;
    view_target = 0; sumh_apply_kstat_spoof(&inode_a, &stat);
    CHECK(same_stat(&stat, &before)); view_target = 1;
    sumh_apply_kstat_spoof(&inode_a, NULL);
    sumh_apply_kstat_spoof(NULL, &stat); CHECK(same_stat(&stat, &before) && clean());

    /* A rekey leaves the prior inode hooked/flagged, but no live rule grants
     * it generic root ownership or synthetic identity any more. */
    files[0].dentry.inode = &inode_new;
    CHECK(sumh_kstat_set(&rule) == 0 && rule.target_ino == 33); synchronize_rcu();
    CHECK(map_a.flags & (1UL << AS_FLAGS_SUMH_SPOOF_KSTAT));
    stat = before = real_stat(); sumh_apply_kstat_spoof(&inode_a, &stat);
    CHECK(same_stat(&stat, &before) && !vnode_calls);
    CHECK(!sumh_kstat_has_redirect_source(NULL));

    /* Reuse the live native redirect store for fallback ownership, including
     * its separately pinned nofollow source. Stale entries do not qualify. */
    redirect.source_inode = &inode_a;
    mutex_lock(&sumh_config_mutex); hlist_add_head_rcu(&redirect.node, &sumh_paths[0]);
    mutex_unlock(&sumh_config_mutex);
    stat = before = real_stat(); sumh_apply_kstat_spoof(&inode_a, &stat);
    CHECK(same_stat(&stat, &before) && !vnode_calls);
    redirect.source_path_valid = true;
    sumh_apply_kstat_spoof(&inode_a, &stat);
    CHECK(stat.ino == 9200 && stat.dev == sumh_system_dev && stat.nlink == 1);
    CHECK(vnode_calls == 1 && vnode_dev == before.dev && vnode_ino == before.ino);
    CHECK(stat.uid == GLOBAL_ROOT_UID && stat.gid == GLOBAL_ROOT_GID);
    CHECK(stat.size == before.size && stat.blksize == before.blksize && stat.blocks == before.blocks);
    redirect.source_path_valid = false;
    redirect.source_nofollow_path.dentry = &files[3].dentry;
    redirect.source_nofollow_path_valid = true;
    stat = real_stat(); sumh_apply_kstat_spoof(&inode_a, &stat);
    CHECK(stat.ino == 9200 && vnode_calls == 2);

    /* Exact explicit rules take priority even when a redirect owns the inode. */
    redirect.source_inode = &inode_new; redirect.source_path_valid = true;
    stat = real_stat(); sumh_apply_kstat_spoof(&inode_new, &stat);
    CHECK(stat.ino == 503 && vnode_calls == 2);
    mutex_lock(&sumh_config_mutex); hlist_del_rcu(&redirect.node); mutex_unlock(&sumh_config_mutex);
    stat = before = real_stat(); sumh_apply_kstat_spoof(&inode_a, &stat);
    CHECK(same_stat(&stat, &before) && vnode_calls == 2 && clean());
    cleanup(); CHECK(!live && !indexed_count() && clean());
    return 0;
}
int main(void) {
    int result = mutation_tests();
    if (result) return result;
    return projection_tests();
}
"""
    return COMMON + preamble + structs + methods + body


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cc", default=os.environ.get("CC") or shutil.which("clang") or shutil.which("cc"))
    args = parser.parse_args()
    if not args.cc:
        parser.error("a C compiler is required; pass --cc")
    compiler = shutil.which(args.cc) or str(Path(args.cc).resolve())
    source = kstat_source()
    if os.name != "nt":
        source = "#define SUMH_TEST_LP64 1\n" + source
    run_test(compiler, "kstat", source)
    if os.name == "nt":
        # Android uses LP64; Windows long uses 32 bits. Exercise both numeric
        # widths using the same functions without claiming ABI equivalence.
        lp64 = re.sub(r"\bunsigned long\b(?! long)", "unsigned long long", source)
        run_test(compiler, "kstat-lp64", "#define SUMH_TEST_LP64 1\n" + lp64)
