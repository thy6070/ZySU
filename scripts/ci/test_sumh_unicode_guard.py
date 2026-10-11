#!/usr/bin/env python3
"""Exercise the production Unicode matcher and guard with kernel API doubles.

No Android files are accessed. Synthetic components verify input boundaries;
fault-injected probes verify enable/disable rollback. This is not a device
test of VFS, kprobes, MediaProvider, or the vulnerable filesystem behavior.
"""

import argparse
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[2]

PRELUDE = r"""
typedef __SIZE_TYPE__ size_t;
typedef __UINTPTR_TYPE__ uintptr_t;
typedef unsigned long long u64;
typedef unsigned int u32;
#define bool int
#define true 1
#define false 0
#define NULL ((void *)0)
#define EINVAL 22
#define EACCES 13
#define ENOENT 2
#define EOPNOTSUPP 95
#define CONFIG_ARM64 1
#ifndef CONFIG_UNICODE
#define CONFIG_UNICODE 1
#endif
#ifndef CONFIG_FUSE_FS
#define CONFIG_FUSE_FS 1
#endif
#define CONFIG_KPROBES 1
#define IS_ENABLED(x) (x)
#define IS_BUILTIN(x) ((x) == 1)
#define KERNEL_VERSION(a,b,c) (((a) << 16) + ((b) << 8) + (c))
#define READ_ONCE(x) (x)
#define WRITE_ONCE(x,y) ((x) = (y))
#define ARRAY_SIZE(x) (sizeof(x) / sizeof((x)[0]))
#define pr_info(...) ((void)0)
#define pr_warn(...) ((void)0)
#define CHECK(id,expr) do { if (!(expr)) return id; } while (0)
#define IS_CASEFOLDED(inode) ((inode)->casefold)
#define instruction_pointer_set(regs,value) ((regs)->pc = (value))

struct qstr { const unsigned char *name; unsigned int len; };
struct inode { int casefold; };
struct super_block { void *s_encoding; };
struct dentry {
    struct inode *d_inode;
    struct super_block *d_sb;
    struct qstr d_name;
    int d_lock;
    struct dentry *d_parent;
};
struct pt_regs { uintptr_t regs[31]; uintptr_t pc; };
typedef unsigned char kprobe_opcode_t;
struct kprobe {
    kprobe_opcode_t *addr;
    int (*pre_handler)(struct kprobe *, struct pt_regs *);
    int registered;
    int flags;
};
struct ksu_feature_handler {
    u32 feature_id;
    const char *name;
    int (*get_handler)(u64 *);
    int (*set_handler)(u64);
};
static const struct ksu_feature_handler *handlers[128];
static int shared_fuse_available = 1, hidden, hidden_queries, lock_errors;
static int missing_symbol = -1, registration_failure = -1;
static int probe_live, probe_register_calls, probe_remove_calls;
static int sumh_dh_srcu;

void *memset(void *output, int value, size_t size)
{
    unsigned char *p = output;
    while (size--) *p++ = (unsigned char)value;
    return output;
}
void *memcpy(void *output, const void *input, size_t size)
{
    unsigned char *p = output;
    const unsigned char *q = input;
    while (size--) *p++ = *q++;
    return output;
}
int strcmp(const char *a, const char *b)
{
    while (*a && *a == *b) { a++; b++; }
    return (unsigned char)*a - (unsigned char)*b;
}
unsigned int text_len(const char *text)
{
    unsigned int n = 0;
    while (text[n]) n++;
    return n;
}
int sumh_dirhijack_fuse_guard_available(void) { return shared_fuse_available; }
int sumh_dirhijack_enabled(void) { return shared_fuse_available; }
int sumh_dh_hidden_name(struct inode *parent, const struct qstr *name)
{
    (void)parent; (void)name;
    hidden_queries++;
    return hidden;
}
struct inode *d_inode(struct dentry *entry) { return entry->d_inode; }
void spin_lock(int *lock) { if ((*lock)++) lock_errors++; }
void spin_unlock(int *lock) { if (--(*lock)) lock_errors++; }
int srcu_read_lock(int *lock) { return (*lock)++; }
void srcu_read_unlock(int *lock, int index) { (void)index; --(*lock); }
uintptr_t find_kernel_symbol_exact(const char *name)
{
    const char *names[] = {"generic_ci_d_hash", "fuse_lookup", "fuse_atomic_open"};
    for (unsigned int i = 0; i < ARRAY_SIZE(names); i++)
        if (!strcmp(name, names[i])) return (int)i == missing_symbol ? 0 : 0x1000 + i;
    return 0;
}
int register_kprobe(struct kprobe *probe)
{
    int index = (int)((uintptr_t)probe->addr - 0x1000);
    probe_register_calls++;
    if (probe->registered) return -16;
    if (index == registration_failure) return -5;
    /* Real register_kprobe retains the disabled flag from unregister. */
    probe->flags &= 1;
    probe->registered = 1;
    probe_live++;
    return 0;
}
void unregister_kprobe(struct kprobe *probe)
{
    if (!probe->registered) lock_errors++;
    probe->registered = 0;
    probe->flags |= 1;
    probe_remove_calls++;
    probe_live--;
}
int ksu_register_feature_handler(const struct ksu_feature_handler *handler)
{
    handlers[handler->feature_id] = handler;
    return 0;
}
int ksu_unregister_feature_handler(u32 id) { handlers[id] = NULL; return 0; }
"""

TESTS = r"""
static unsigned int encode(unsigned int cp, unsigned char *out)
{
    if (cp < 0x80) { out[0] = (unsigned char)cp; return 1; }
    if (cp < 0x800) {
        out[0] = (unsigned char)(0xc0 | (cp >> 6));
        out[1] = (unsigned char)(0x80 | (cp & 63)); return 2;
    }
    if (cp < 0x10000) {
        out[0] = (unsigned char)(0xe0 | (cp >> 12));
        out[1] = (unsigned char)(0x80 | ((cp >> 6) & 63));
        out[2] = (unsigned char)(0x80 | (cp & 63)); return 3;
    }
    out[0] = (unsigned char)(0xf0 | (cp >> 18));
    out[1] = (unsigned char)(0x80 | ((cp >> 12) & 63));
    out[2] = (unsigned char)(0x80 | ((cp >> 6) & 63));
    out[3] = (unsigned char)(0x80 | (cp & 63)); return 4;
}

static int match(const char *name)
{
    return sumh_unicode_sensitive_alias((const unsigned char *)name, text_len(name));
}

static int parser_checks(void)
{
    /* Unicode property intervals are fixtures from DerivedCoreProperties. */
    static const unsigned int ranges[][2] = {
        {0xad,0xad},{0x34f,0x34f},{0x61c,0x61c},{0x115f,0x1160},
        {0x17b4,0x17b5},{0x180b,0x180f},{0x200b,0x200f},{0x202a,0x202e},
        {0x2060,0x206f},{0x3164,0x3164},{0xfe00,0xfe0f},{0xfeff,0xfeff},
        {0xffa0,0xffa0},{0xfff0,0xfff8},{0x1bca0,0x1bca3},
        {0x1d173,0x1d17a},{0xe0000,0xe0fff}
    };
    static const char *const targets[] = {"Android", "DaTa", "oBb"};
    static unsigned char buffer[4096];
    unsigned int n, decoded;
    CHECK(1, !match("Android") && !match("ANDROID") && !match("data") && !match("ObB"));
    CHECK(2, !match("file.txt") && !match("Andr\xc3\xb6id") && !match("\xe4\xb8\xad\xe6\x96\x87"));
    CHECK(3, match("An\xe2\x80\x8b" "droid") && match("\xc2\xad" "data") && match("obb\xef\xbb\xbf"));
    CHECK(4, !match("Android\xe2\x80\x8b" "x") && !match("Android\xe2\x80\x8b/data"));
    CHECK(5, !match("\xe2\x80\x8b") && !match("media\xe2\x80\x8b") && !match("my\xe2\x80\x8b" "data"));
    CHECK(6, !match("data\xc0\xad") && !match("data\xe2\x80") && !match("data\xed\xa0\x80"));
    CHECK(7, !match("data\xf4\x90\x80\x80") && !match("data\x80") && !match("data\xe0\x80\xaf"));
    CHECK(8, !sumh_unicode_sensitive_alias(NULL, 4) && !sumh_unicode_sensitive_alias(buffer, 0));
    /* No implicit NUL termination and no reading beyond the supplied qstr. */
    memcpy(buffer, "data\xe2\x80\x8b" "extra", 12);
    CHECK(9, sumh_unicode_sensitive_alias(buffer, 7) && !sumh_unicode_sensitive_alias(buffer, 6));
    buffer[7] = 0;
    CHECK(10, !sumh_unicode_sensitive_alias(buffer, 8));
    /* An alias exceeding NAME_MAX can still hit a warm dentry before the
     * filesystem validates length. It must never become a fail-open case. */
    memcpy(buffer, "Android", 7);
    n = 7;
    while (n + 3 <= sizeof(buffer)) { n += encode(0x200b, buffer + n); }
    CHECK(11, n > 255 && sumh_unicode_sensitive_alias(buffer, n));
    for (unsigned int i = 0; i < ARRAY_SIZE(ranges); i++) {
        CHECK(12, !sumh_unicode_default_ignorable(ranges[i][0] - 1));
        CHECK(13, !sumh_unicode_default_ignorable(ranges[i][1] + 1));
        for (unsigned int cp = ranges[i][0]; cp <= ranges[i][1]; cp++) {
            CHECK(14, sumh_unicode_default_ignorable(cp));
            for (unsigned int j = 0; j < ARRAY_SIZE(targets); j++) {
                unsigned int length = text_len(targets[j]);
                /* Every insertion position, including the first and last. */
                for (unsigned int at = 0; at <= length; at++) {
                    memcpy(buffer, targets[j], at);
                    n = encode(cp, buffer + at);
                    CHECK(15, sumh_unicode_decode(buffer + at, n, &decoded) == n && decoded == cp);
                    memcpy(buffer + at + n, targets[j] + at, length - at);
                    CHECK(16, sumh_unicode_sensitive_alias(buffer, length + n));
                }
            }
        }
    }
    return 0;
}

static int lifecycle_checks(void)
{
    u64 state;
    struct qstr alias = {(const unsigned char *)"An\xe2\x80\x8b" "droid", 10};
    shared_fuse_available = 0;
    sumh_unicode_guard_init();
    CHECK(20, !sumh_unicode_status_get(&state) && state == KSU_UNICODE_PATH_GUARD_UNSUPPORTED);
    CHECK(21, sumh_unicode_feature_set(1) == -EOPNOTSUPP && !probe_live);
    sumh_unicode_guard_exit();
    shared_fuse_available = 1;
    sumh_unicode_guard_init();
    CHECK(22, handlers[KSU_FEATURE_UNICODE_PATH_GUARD] && handlers[KSU_FEATURE_UNICODE_PATH_GUARD_STATUS]);
    CHECK(23, !handlers[KSU_FEATURE_UNICODE_PATH_GUARD_STATUS]->set_handler);
    CHECK(24, sumh_unicode_feature_set(2) == -EINVAL && !probe_live);
    CHECK(25, !sumh_unicode_guard_reject_component(&alias));
#ifdef SUMH_UNICODE_HOOKS_SUPPORTED
    CHECK(26, !sumh_unicode_status_get(&state) && state == KSU_UNICODE_PATH_GUARD_DISABLED);
    for (int failed = 0; failed < 3; failed++) {
        registration_failure = failed;
        CHECK(27, sumh_unicode_feature_set(1) == -5);
        CHECK(28, !probe_live && !sumh_unicode_guard_reject_component(&alias));
        CHECK(29, !sumh_unicode_feature_get(&state) && state == 0);
        CHECK(30, !sumh_unicode_status_get(&state) && state == KSU_UNICODE_PATH_GUARD_UNSUPPORTED);
    }
    registration_failure = -1;
    for (unsigned int cycle = 0; cycle < 3; cycle++) {
        int calls;
        CHECK(31, !sumh_unicode_feature_set(1) && probe_live == 3);
        CHECK(32, sumh_unicode_guard_reject_component(&alias));
        CHECK(33, !sumh_unicode_status_get(&state) && state == KSU_UNICODE_PATH_GUARD_ACTIVE);
        for (unsigned int i = 0; i < 3; i++)
            CHECK(34, sumh_unicode_probes[i].registered && !sumh_unicode_probes[i].flags);
        calls = probe_register_calls;
        CHECK(35, !sumh_unicode_feature_set(1) && calls == probe_register_calls);
        CHECK(36, !sumh_unicode_feature_set(0) && !probe_live);
        CHECK(37, !sumh_unicode_status_get(&state) && state == KSU_UNICODE_PATH_GUARD_DISABLED);
    }
    CHECK(38, !sumh_unicode_feature_set(1));
#else
    CHECK(39, !sumh_unicode_status_get(&state) && state == KSU_UNICODE_PATH_GUARD_UNSUPPORTED);
    CHECK(40, sumh_unicode_feature_set(1) == -EOPNOTSUPP && !probe_live);
#endif
    sumh_unicode_guard_exit();
    CHECK(41, !probe_live && !handlers[KSU_FEATURE_UNICODE_PATH_GUARD] && !handlers[KSU_FEATURE_UNICODE_PATH_GUARD_STATUS]);
    sumh_unicode_guard_exit();
    CHECK(42, !probe_live && !lock_errors);
#ifdef SUMH_UNICODE_HOOKS_SUPPORTED
    for (int i = 0; i < 3; i++) {
        missing_symbol = i;
        sumh_unicode_guard_init();
        CHECK(43, !sumh_unicode_status_get(&state) && state == KSU_UNICODE_PATH_GUARD_UNSUPPORTED);
        CHECK(44, sumh_unicode_feature_set(1) == -EOPNOTSUPP && !probe_live);
        sumh_unicode_guard_exit();
    }
    missing_symbol = -1;
#endif
    return 0;
}

static int ingress_checks(void)
{
    struct inode directory_inode = {1};
    struct super_block sb = {(void *)1};
    struct dentry parent = {&directory_inode, &sb, {(const unsigned char *)"parent",6},0,NULL};
    struct dentry entry = {&directory_inode, &sb, {(const unsigned char *)"An\xe2\x80\x8b" "droid",10},0,&parent};
    struct pt_regs regs;
    int calls;
    shared_fuse_available = 1;
    sumh_unicode_guard_init();
#ifdef SUMH_UNICODE_HOOKS_SUPPORTED
    CHECK(50, !sumh_unicode_feature_set(1));
    for (unsigned int i = 0; i < 3; i++) {
        memset(&regs, 0, sizeof(regs));
        regs.regs[0] = (uintptr_t)&parent;
        regs.regs[1] = (uintptr_t)(i ? (void *)&entry : (void *)&entry.d_name);
        regs.regs[30] = 0x1234;
        CHECK(51, sumh_unicode_guard_pre(&sumh_unicode_probes[i], &regs) == 1);
        CHECK(52, regs.regs[0] == (uintptr_t)-EACCES && regs.pc == 0x1234);
    }
    directory_inode.casefold = 0;
    memset(&regs, 0, sizeof(regs));
    regs.regs[0] = (uintptr_t)&parent;
    regs.regs[1] = (uintptr_t)&entry.d_name;
    CHECK(53, !sumh_unicode_guard_pre(&sumh_unicode_probes[0], &regs));
    directory_inode.casefold = 1;
    sb.s_encoding = NULL;
    CHECK(54, !sumh_unicode_guard_pre(&sumh_unicode_probes[0], &regs));
    sb.s_encoding = (void *)1;
#else
    /* Exercise the shared revalidation callback's ABI independently even
     * on an intentionally unsupported future kernel family. */
    sumh_unicode_enabled = true;
#endif
    memset(&regs, 0, sizeof(regs));
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 13, 0)
    regs.regs[0] = (uintptr_t)&directory_inode;
    regs.regs[1] = (uintptr_t)&entry.d_name;
    regs.regs[2] = (uintptr_t)&entry;
#else
    regs.regs[0] = (uintptr_t)&entry;
#endif
    regs.regs[30] = 0x5678;
    hidden = 0;
    calls = hidden_queries;
    CHECK(55, sumh_dh_fuse_revalidate_pre(NULL, &regs) == 1);
    CHECK(56, regs.regs[0] == (uintptr_t)-EACCES && regs.pc == 0x5678);
    CHECK(57, hidden_queries == calls && !entry.d_lock && !sumh_dh_srcu);
    sumh_unicode_guard_exit();
    CHECK(58, !sumh_unicode_guard_reject_component(&entry.d_name) && !lock_errors);
    return 0;
}

int run(void)
{
    int ret = parser_checks();
    if (ret) return ret;
    ret = lifecycle_checks();
    if (ret) return ret;
    return ingress_checks();
}
#ifndef _WIN32
int main(void) { return run(); }
#endif
"""


def function_body(text, marker):
    start = text.index(marker)
    brace = text.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end] + "\n"


def test_source():
    feature = (ROOT / "uapi/feature.h").read_text(encoding="utf-8")
    match = (ROOT / "kernel/sumh/features/sumh_unicode_guard_match.h").read_text(encoding="utf-8")
    guard = (ROOT / "kernel/sumh/features/sumh_unicode_guard.c").read_text(encoding="utf-8")
    guard = re.sub(r"^#include[^\n]*\n", "", guard, flags=re.M)
    directory = (ROOT / "kernel/sumh/core/sumh_dirhijack.c").read_text(encoding="utf-8")
    shared = function_body(directory, "static int sumh_dh_fuse_revalidate_pre")
    # Kernel unsigned long holds pointers; Windows uses LLP64 instead.
    return PRELUDE + feature + match + guard.replace("unsigned long", "uintptr_t") + shared.replace("unsigned long", "uintptr_t") + TESTS


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cc", default=shutil.which("clang") or shutil.which("cc"))
    parser.add_argument("--ld", help="Windows: path to LLVM ld.lld")
    args = parser.parse_args()
    if not args.cc:
        parser.error("No C compiler found; pass --cc")
    with tempfile.TemporaryDirectory(prefix="sumh-unicode-guard-") as temp:
        directory = Path(temp)
        source = directory / "test.c"
        source.write_text(test_source(), encoding="utf-8")
        configurations = ((1, 1, 1), (6, 1, 1), (12, 1, 1), (18, 1, 1),
                          (13, 1, 1), (1, 0, 1), (1, 1, 0), (1, 1, 2))
        for minor, unicode_enabled, fuse in configurations:
            output = directory / ("test.exe" if sys.platform == "win32" else "test")
            version = (6 << 16) + (minor << 8)
            command = [args.cc, "-std=gnu11", "-Wall", "-Werror", "-ffreestanding",
                       "-fno-stack-protector", "-fno-builtin", f"-DLINUX_VERSION_CODE={version}",
                       f"-DCONFIG_UNICODE={unicode_enabled}", f"-DCONFIG_FUSE_FS={fuse}"]
            if sys.platform == "win32":
                obj = directory / "test.obj"
                subprocess.run(command + ["--target=x86_64-pc-windows-msvc", "-c", str(source), "-o", str(obj)], check=True)
                linker = args.ld or str(Path(args.cc).with_name("ld.lld.exe"))
                subprocess.run([linker, "-flavor", "link", "/entry:run", "/subsystem:console", "/nodefaultlib", f"/out:{output}", str(obj)], check=True)
            else:
                subprocess.run(command + [str(source), "-o", str(output)], check=True)
            result = subprocess.run([str(output)])
            if result.returncode:
                raise AssertionError(f"Unicode guard check {result.returncode} failed (6.{minor}, Unicode={unicode_enabled}, FUSE={fuse})")
            print(f"SUMH Unicode guard: matcher, lifecycle and ingress checks passed (6.{minor}, Unicode={unicode_enabled}, FUSE={fuse})")


if __name__ == "__main__":
    main()
