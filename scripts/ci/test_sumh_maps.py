#!/usr/bin/env python3
"""Exercise production SUMH maps mutations and selection with host API doubles.

Checks ownership, atomic replacement failure, device precedence and proc-text
input boundaries. These checks do not load a kernel or model real concurrency.
"""

import argparse
import os
from pathlib import Path
import re
import shutil
import sys

sys.dont_write_bytecode = True
from test_sumh_vfs_helpers import COMMON, function, run_test


def maps_source():
    preamble = r"""
typedef unsigned int __u32;
#define __aligned_u64 unsigned long long __attribute__((aligned(8)))
#define __user
#define EINVAL 22
#define ENOMEM 12
#define EFAULT 14
#define EOPNOTSUPP 95
#define GFP_KERNEL 0
#define SUMH_IOC_CLEAR_MAPS_RULES 24
#define u64_to_user_ptr(value) ((void *)(__UINTPTR_TYPE__)(value))
struct list_head { struct list_head *next, *prev; };
#define LIST_HEAD(name) struct list_head name = { &(name), &(name) }
#define container_of(ptr, type, member) \
    ((type *)((char *)(ptr) - __builtin_offsetof(type, member)))
#define list_entry(ptr, type, member) container_of(ptr, type, member)
#define list_for_each_entry(pos, head, member) \
    for (pos = list_entry((head)->next, __typeof__(*pos), member); \
         &pos->member != (head); \
         pos = list_entry(pos->member.next, __typeof__(*pos), member))
#define list_for_each_entry_safe(pos, next_entry, head, member) \
    for (pos = list_entry((head)->next, __typeof__(*pos), member), \
         next_entry = list_entry(pos->member.next, __typeof__(*pos), member); \
         &pos->member != (head); pos = next_entry, \
         next_entry = list_entry(next_entry->member.next, __typeof__(*next_entry), member))
static void list_add_tail(struct list_head *entry, struct list_head *head) {
    entry->prev = head->prev; entry->next = head;
    head->prev->next = entry; head->prev = entry;
}
static void list_del(struct list_head *entry) {
    entry->next->prev = entry->prev; entry->prev->next = entry->next;
}
static void list_replace(struct list_head *old, struct list_head *entry) {
    entry->next = old->next; entry->prev = old->prev;
    entry->next->prev = entry; entry->prev->next = entry;
}
static void list_move_tail(struct list_head *entry, struct list_head *head) {
    list_del(entry); list_add_tail(entry, head);
}
static void list_splice_tail_init(struct list_head *source, struct list_head *head) {
    if (source->next != source) {
        head->prev->next = source->next; source->next->prev = head->prev;
        head->prev = source->prev; source->prev->next = head;
        source->next = source->prev = source;
    }
}
static void *memchr(const void *data, int c, size_t n) {
    const unsigned char *bytes = data;
    for (size_t i = 0; i < n; i++) if (bytes[i] == (unsigned char)c) return (void *)(bytes + i);
    return NULL;
}
static int strscpy(char *dst, const char *src, size_t n) {
    size_t i = 0;
    if (!n) return -7;
    while (i + 1 < n && src[i]) { dst[i] = src[i]; i++; }
    dst[i] = 0;
    return src[i] ? -7 : (int)i;
}
static int sumh_maps_mutex, lock_depth, lock_entries, bad_context;
static int sumh_proc_proxy_registered = 1;
static LIST_HEAD(sumh_maps_rules);
static void mutex_lock(int *mutex) {
    if (mutex != &sumh_maps_mutex || lock_depth) bad_context++;
    lock_depth++; lock_entries++;
}
static void mutex_unlock(int *mutex) {
    if (mutex != &sumh_maps_mutex || lock_depth != 1) bad_context++;
    lock_depth--;
}
static unsigned char blocks[600][512] __attribute__((aligned(16)));
static unsigned char array_block[100000] __attribute__((aligned(16)));
static int blocks_used[600], array_used, live, alloc_calls, alloc_fail_at;
static void *kmalloc(size_t size, int flags) {
    (void)flags;
    if (lock_depth) bad_context++;
    if (++alloc_calls == alloc_fail_at) return NULL;
    if (size > sizeof(blocks[0])) { bad_context++; return NULL; }
    for (int i = 0; i < 600; i++) if (!blocks_used[i]) {
        blocks_used[i] = 1; live++; return blocks[i];
    }
    return NULL;
}
static void kfree(void *ptr) {
    if (!ptr) return;
    if (lock_depth) bad_context++;
    for (int i = 0; i < 600; i++) if (ptr == blocks[i] && blocks_used[i]) {
        blocks_used[i] = 0; live--; return;
    }
    bad_context++;
}
static void *kvmalloc_array(size_t count, size_t size, int flags) {
    (void)flags;
    if (lock_depth || array_used) bad_context++;
    if (++alloc_calls == alloc_fail_at) return NULL;
    if (count * size > sizeof(array_block)) { bad_context++; return NULL; }
    array_used = 1; live++; return array_block;
}
static void kvfree(void *ptr) {
    if (!ptr) return;
    if (lock_depth || ptr != array_block || !array_used) bad_context++;
    array_used = 0; live--;
}
static int copies, copy_fail_at, reply_fault;
static int copy_from_user(void *out, const void *in, size_t size) {
    if (lock_depth) bad_context++;
    if (++copies == copy_fail_at) return 1;
    memcpy(out, in, size); return 0;
}
static int copy_to_user(void *out, const void *in, size_t size) {
    if (lock_depth) bad_context++;
    if (reply_fault) return 1;
    memcpy(out, in, size); return 0;
}
"""
    root = Path(__file__).resolve().parents[2]
    uapi = (root / "uapi/sumh.h").read_text(encoding="utf-8")
    defines = "\n".join(line for line in uapi.splitlines() if line.startswith(
        ("#define SUMH_MAX_LEN_PATHNAME ", "#define SUMH_MANAGED_MAPS_MAX_RULES ")))
    structs = "\n".join(function("uapi/sumh.h", marker) + ";" for marker in (
        "struct sumh_maps_rule {", "struct sumh_managed_maps_arg {"))
    structs += function("kernel/sumh/core/sumh_store.h", "struct sumh_maps_rule_entry {") + ";"
    methods = "\n".join(function("kernel/sumh/control/sumh_ioctl.c", marker) for marker in (
        "static int sumh_maps_rule_validate(",
        "static struct sumh_maps_rule_entry *\nsumh_maps_rule_alloc(",
        "static void sumh_maps_free_list(", "static long sumh_maps_add_rule(",
        "static long sumh_maps_replace_managed("))
    methods += "static struct sumh_maps_rule_entry *\n" + function(
        "kernel/sumh/hooks/sumh_proc_read_hooks.c", "sumh_maps_find_rule(")
    clear = function("kernel/sumh/control/sumh_ioctl.c", "if (cmd == SUMH_IOC_CLEAR_MAPS_RULES)")
    methods += "\nstatic long clear_rules(unsigned int cmd) {\n" + clear + "\nreturn -EINVAL;\n}\n"
    body = r"""
_Static_assert(sizeof(struct sumh_managed_maps_arg) == 24, "stable managed maps ABI");
_Static_assert(__builtin_offsetof(struct sumh_managed_maps_arg, rules) == 8, "aligned pointer");
static void make_rule(struct sumh_maps_rule *rule, unsigned long ino, unsigned long dev, unsigned long spoof) {
    memset(rule, 0, sizeof(*rule));
    rule->target_ino = ino; rule->target_dev = dev;
    rule->spoofed_ino = spoof; rule->spoofed_dev = 7;
    strscpy(rule->spoofed_pathname, "/system/lib64/library.so", sizeof(rule->spoofed_pathname));
}
static int entry_count(void) {
    int count = 0; struct sumh_maps_rule_entry *entry;
    list_for_each_entry(entry, &sumh_maps_rules, list) count++;
    return count;
}
static unsigned long selected(unsigned long ino, unsigned long dev) {
    struct sumh_maps_rule_entry *entry; unsigned long result;
    mutex_lock(&sumh_maps_mutex);
    entry = sumh_maps_find_rule(ino, dev);
    result = entry ? entry->spoofed_ino : 0;
    mutex_unlock(&sumh_maps_mutex);
    return result;
}
static int clean(void) { return !bad_context && !lock_depth && !array_used; }
static void reset_faults(void) {
    alloc_calls = alloc_fail_at = copies = copy_fail_at = reply_fault = 0;
}
static long replace(struct sumh_maps_rule *rules, unsigned int count) {
    struct sumh_managed_maps_arg arg = {0};
    arg.size = sizeof(arg); arg.count = count; arg.rules = (__UINTPTR_TYPE__)rules;
    return sumh_maps_replace_managed(&arg);
}
int main(void) {
    static struct sumh_maps_rule rule, saved[3], replacement[3];
    static struct sumh_managed_maps_arg arg;
    int before, locks;

    make_rule(&rule, 1, 0, 11);
    CHECK(sumh_maps_add_rule(&rule) == 0);
    rule.spoofed_ino = 12;
    CHECK(sumh_maps_add_rule(&rule) == 0);
    CHECK(entry_count() == 1 && live == 1 && selected(1, 77) == 12);
    make_rule(&rule, 1, 7, 21);
    CHECK(sumh_maps_add_rule(&rule) == 0);
    CHECK(selected(1, 7) == 21 && selected(1, 8) == 12);

    make_rule(&saved[0], 2, 9, 31);
    make_rule(&saved[1], 1, 7, 41);
    make_rule(&saved[2], 1, 0, 51);
    CHECK(replace(saved, 3) == 0);
    CHECK(entry_count() == 5 && live == 5);
    CHECK(selected(1, 7) == 21 && selected(1, 8) == 12 && selected(2, 9) == 31);
    /* Updating an explicit rule must leave the managed twin independently owned. */
    rule.spoofed_ino = 22;
    CHECK(sumh_maps_add_rule(&rule) == 0);
    CHECK(entry_count() == 5 && live == 5 && selected(1, 7) == 22);

    make_rule(&replacement[0], 1, 8, 61);
    make_rule(&replacement[1], 2, 0, 71);
    make_rule(&replacement[2], 2, 9, 81);
    CHECK(replace(replacement, 3) == 0);
    CHECK(entry_count() == 5 && live == 5 && selected(1, 7) == 22);
    CHECK(selected(1, 8) == 61 && selected(1, 6) == 12);
    CHECK(selected(2, 9) == 81 && selected(2, 6) == 71);

    /* Each user-copy and allocation failure preserves the complete old set. */
    for (int fault = 1; fault <= 4; fault++) {
        reset_faults(); alloc_fail_at = fault;
        CHECK(replace(saved, 3) == -ENOMEM);
        CHECK(entry_count() == 5 && live == 5 && selected(1, 8) == 61);
        CHECK(selected(2, 9) == 81 && clean());
    }
    for (int fault = 1; fault <= 2; fault++) {
        reset_faults(); copy_fail_at = fault;
        CHECK(replace(saved, 3) == -EFAULT);
        CHECK(entry_count() == 5 && live == 5 && selected(1, 8) == 61 && clean());
    }
    reset_faults();
    before = live; locks = lock_entries;
    saved[2] = saved[0];
    CHECK(replace(saved, 3) == -EINVAL);
    CHECK(live == before && lock_entries == locks && entry_count() == 5);
    make_rule(&saved[2], 3, 0, 51);
    for (int invalid = 0; invalid < 5; invalid++) {
        struct sumh_maps_rule original = saved[2];
        if (invalid == 0) saved[2].target_ino = 0;
        if (invalid == 1) saved[2].err = 1;
        if (invalid == 2) memset(saved[2].spoofed_pathname, 'x', sizeof(saved[2].spoofed_pathname));
        if (invalid == 3) saved[2].spoofed_pathname[3] = '\n';
        if (invalid == 4) saved[2].spoofed_pathname[3] = '\r';
        CHECK(replace(saved, 3) == -EINVAL);
        CHECK(live == before && lock_entries == locks && entry_count() == 5 && clean());
        saved[2] = original;
    }
    arg.size = sizeof(arg); arg.count = 1; arg.rules = (__UINTPTR_TYPE__)saved;
    arg.size--;
    CHECK(sumh_maps_replace_managed(&arg) == -EINVAL);
    arg.size++; arg.reserved[0] = 1;
    CHECK(sumh_maps_replace_managed(&arg) == -EINVAL);
    arg.reserved[0] = 0; arg.reserved[1] = 1;
    CHECK(sumh_maps_replace_managed(&arg) == -EINVAL);
    arg.reserved[1] = 0; arg.count = SUMH_MANAGED_MAPS_MAX_RULES + 1;
    CHECK(sumh_maps_replace_managed(&arg) == -EINVAL);
    arg.count = 1; arg.rules = 0;
    CHECK(sumh_maps_replace_managed(&arg) == -EINVAL);
    arg.rules = (__UINTPTR_TYPE__)saved; sumh_proc_proxy_registered = 0;
    CHECK(sumh_maps_replace_managed(&arg) == -EOPNOTSUPP);
    sumh_proc_proxy_registered = 1;
    CHECK(live == before && lock_entries == locks && entry_count() == 5 && clean());
#if __SIZEOF_LONG__ > 4 || defined(SUMH_TEST_LP64)
    saved[2].target_dev = 0x100000000UL;
    CHECK(replace(saved, 3) == -EINVAL);
    saved[2].target_dev = 0;
    saved[2].spoofed_dev = 0x100000000UL;
    CHECK(replace(saved, 3) == -EINVAL);
    saved[2].spoofed_dev = 7;
    CHECK(live == before && lock_entries == locks && entry_count() == 5 && clean());
#endif

    /* Legacy failures also preserve the prior explicit value. */
    make_rule(&rule, 1, 7, 99);
    reply_fault = 1;
    CHECK(sumh_maps_add_rule(&rule) == -EFAULT);
    reset_faults(); alloc_fail_at = 1;
    CHECK(sumh_maps_add_rule(&rule) == -ENOMEM);
    reset_faults(); copy_fail_at = 1;
    CHECK(sumh_maps_add_rule(&rule) == -EFAULT);
    reset_faults();
    rule.spoofed_pathname[2] = '\n';
    CHECK(sumh_maps_add_rule(&rule) == -EINVAL);
    rule.spoofed_pathname[2] = '\r';
    CHECK(sumh_maps_add_rule(&rule) == -EINVAL);
    memset(rule.spoofed_pathname, 'x', sizeof(rule.spoofed_pathname));
    CHECK(sumh_maps_add_rule(&rule) == -EINVAL);
    CHECK(selected(1, 7) == 22 && live == before && clean());

    CHECK(replace(NULL, 0) == 0);
    CHECK(entry_count() == 2 && live == 2 && !selected(2, 9));
    CHECK(selected(1, 7) == 22 && selected(1, 8) == 12);
    CHECK(replace(NULL, 0) == 0 && entry_count() == 2 && live == 2);
    CHECK(clear_rules(SUMH_IOC_CLEAR_MAPS_RULES) == 0 && !live && !entry_count());

    /* Legacy numeric rules retain support for anonymous mappings. */
    make_rule(&rule, 0, 0, 999);
    CHECK(sumh_maps_add_rule(&rule) == 0 && selected(0, 0) == 999);
    CHECK(replace(&rule, 1) == -EINVAL && selected(0, 0) == 999);
    CHECK(clear_rules(SUMH_IOC_CLEAR_MAPS_RULES) == 0 && !live && !entry_count());

    /* Reverse insertion order; a later explicit wildcard cannot beat exact. */
    make_rule(&saved[0], 3, 9, 100); make_rule(&saved[1], 3, 0, 101);
    CHECK(replace(saved, 2) == 0);
    make_rule(&rule, 3, 0, 102);
    CHECK(sumh_maps_add_rule(&rule) == 0);
    CHECK(selected(3, 9) == 100 && selected(3, 8) == 102);
    make_rule(&rule, 3, 9, 103);
    CHECK(sumh_maps_add_rule(&rule) == 0 && selected(3, 9) == 103);
    CHECK(clear_rules(SUMH_IOC_CLEAR_MAPS_RULES) == 0 && !live && !entry_count());

    /* Maximum-size batch and 255-byte or intentionally empty replacement names. */
    static struct sumh_maps_rule maximum[SUMH_MANAGED_MAPS_MAX_RULES];
    for (unsigned int i = 0; i < SUMH_MANAGED_MAPS_MAX_RULES; i++) make_rule(&maximum[i], i + 1, 9, i + 1);
    memset(maximum[0].spoofed_pathname, 'x', sizeof(maximum[0].spoofed_pathname) - 1);
    maximum[0].spoofed_pathname[sizeof(maximum[0].spoofed_pathname) - 1] = 0;
    maximum[1].spoofed_pathname[0] = 0;
    CHECK(replace(maximum, SUMH_MANAGED_MAPS_MAX_RULES) == 0);
    CHECK(entry_count() == SUMH_MANAGED_MAPS_MAX_RULES && live == SUMH_MANAGED_MAPS_MAX_RULES);
    CHECK(selected(256, 9) == 256 && !selected(256, 8));
    CHECK(clear_rules(SUMH_IOC_CLEAR_MAPS_RULES) == 0 && !live && !entry_count() && clean());
    return 0;
}
"""
    return COMMON + preamble + defines + "\n" + structs + methods + body


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cc", default=os.environ.get("CC") or shutil.which("clang") or shutil.which("cc"))
    args = parser.parse_args()
    if not args.cc:
        parser.error("a C compiler is required; pass --cc")
    compiler = shutil.which(args.cc) or str(Path(args.cc).resolve())
    source = maps_source()
    run_test(compiler, "maps", source)
    if os.name == "nt":
        # Windows long is 32 bits. Also exercise the actual Android-width
        # inode/device values; this remains an API-double test, not ABI proof.
        lp64 = re.sub(r"\bunsigned long\b(?! long)", "unsigned long long", source)
        run_test(compiler, "maps-lp64", "#define SUMH_TEST_LP64 1\n" + lp64)
