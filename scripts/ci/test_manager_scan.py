"""Exercise manager discovery and cache invalidation using production C functions.

The VFS and signature-reader doubles record directory opens, iteration state and
APK reads, while the real tracker, traversal, identity checks and cache run.
Certificate parsing and password authentication run production helpers with
deterministic cryptographic doubles.
These host regressions do not replace a kernel build or device validation.
"""

import argparse
import os
from pathlib import Path
import shutil
import sys

sys.dont_write_bytecode = True
from test_sumh_vfs_helpers import COMMON, function, run_test


SOURCE = "kernel/manager/throne_tracker.c"


def extracted(marker):
    return function(SOURCE, marker) + "\n"


def harness(superkey=False):
    preamble = r"""
typedef unsigned int u32;
typedef unsigned int uid_t;
typedef unsigned long long u64;
typedef long long loff_t;
#define DATA_PATH_LEN 384
#define KSU_MAX_PACKAGE_NAME 256
#define KSU_MANAGER_PACKAGE "com.example.one"
#define PER_USER_RANGE 100000
#define READ_ONCE(value) (value)
#define WRITE_ONCE(target, value) ((target) = (value))
#define KSU_INVALID_UID ((uid_t)-1)
#define SYSTEM_PACKAGES_LIST_PATH "/data/system/packages.list"
#define GFP_ATOMIC 0
#define GFP_KERNEL 1
#define O_RDONLY 0
#define O_NOFOLLOW 1
#define O_NOATIME 2
#define O_DIRECTORY 4
#define DT_DIR 4
#define DT_REG 8
#define DT_LNK 10
#define STATX_TYPE 1
#define STATX_INO 2
#define STATX_SIZE 4
#define STATX_MTIME 8
#define STATX_CTIME 16
#define STATX_BASIC_STATS 31
#define AT_STATX_SYNC_AS_STAT 0
#define S_IFREG 0100000
#define S_ISREG(mode) (((mode) & 0170000) == S_IFREG)
#define pr_info(...) ((void)0)
#define pr_err(...) ((void)0)
#define EACCES 13
#define IS_ERR(value) ((value) == NULL)
#define PTR_ERR(value) (-1L)
#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))
#define container_of(ptr, type, member) \
    ((type *)((char *)(ptr) - __builtin_offsetof(type, member)))
struct list_head { struct list_head *next, *prev; };
#define LIST_HEAD(name) struct list_head name = { &name, &name }
#define INIT_LIST_HEAD(head) ((head)->next = (head)->prev = (head))
static bool list_empty(const struct list_head *head) { return head->next == head; }
static void list_add_tail(struct list_head *node, struct list_head *head) {
    node->next = head; node->prev = head->prev;
    head->prev->next = node; head->prev = node;
}
static void list_del(struct list_head *node) {
    node->prev->next = node->next; node->next->prev = node->prev;
}
#define list_entry(ptr, type, member) container_of(ptr, type, member)
#define list_for_each_entry(pos, head, member) \
    for (pos = list_entry((head)->next, __typeof__(*pos), member); \
         &pos->member != (head); \
         pos = list_entry(pos->member.next, __typeof__(*pos), member))
#define list_for_each_entry_safe(pos, next_pos, head, member) \
    for (pos = list_entry((head)->next, __typeof__(*pos), member), \
         next_pos = list_entry(pos->member.next, __typeof__(*pos), member); \
         &pos->member != (head); pos = next_pos, \
         next_pos = list_entry(next_pos->member.next, __typeof__(*next_pos), member))
static char *strchr(const char *text, int ch) {
    for (;;) { if (*text == ch) return (char *)text; if (!*text++) return NULL; }
}
static char *strrchr(const char *text, int ch) {
    char *found = NULL;
    for (;;) { if (*text == ch) found = (char *)text; if (!*text++) return found; }
}
static ssize_t strscpy(char *dst, const char *src, size_t size) {
    size_t n = strlen(src);
    if (!size) return -1;
    size_t copied = n < size ? n : size - 1;
    memcpy(dst, src, copied); dst[copied] = 0;
    return n < size ? (ssize_t)n : -1;
}
static char *strsep(char **input, const char *delim) {
    char *first = *input, *next = first;
    if (!first) return NULL;
    while (*next && !strchr(delim, *next)) next++;
    if (*next) { *next = 0; *input = next + 1; } else *input = NULL;
    return first;
}
static int kstrtou32(const char *s, unsigned int base, u32 *out) {
    u64 value = 0;
    if (base != 10 || !*s) return -1;
    while (*s) {
        if (*s < '0' || *s > '9') return -1;
        value = value * 10 + *s++ - '0';
        if (value > 0xffffffffULL) return -1;
    }
    *out = value; return 0;
}
/* Only the two path-format forms used by the production walker are needed. */
static int snprintf(char *dst, size_t capacity, const char *format, ...) {
    __builtin_va_list args;
    __builtin_va_start(args, format);
    const char *first = __builtin_va_arg(args, const char *);
    const char *last;
    size_t n = strlen(first), m;
    if (!strcmp(format, "%s/base.apk")) { last = "base.apk"; m = 8; }
    else { m = __builtin_va_arg(args, int); last = __builtin_va_arg(args, const char *); }
    size_t total = n + 1 + m;
    for (size_t i = 0; i < total && i + 1 < capacity; i++)
        dst[i] = i < n ? first[i] : i == n ? '/' : last[i - n - 1];
    if (capacity) dst[total < capacity ? total : capacity - 1] = 0;
    __builtin_va_end(args);
    return total;
}
struct timestamp { long long tv_sec, tv_nsec; };
struct kstat {
    u64 dev, ino, size;
    struct timestamp mtime, ctime;
    unsigned int mode, result_mask;
};
struct super_block { unsigned long s_magic; };
struct inode { struct super_block *i_sb; loff_t size; };
struct file { struct inode *f_inode; char name[DATA_PATH_LEN]; };
struct dir_context {
    bool (*actor)(struct dir_context *, const char *, int, loff_t, u64, unsigned int);
};
struct cred { int unused; };
static struct cred test_cred;
static const struct cred *ksu_cred = &test_cred;
static int creds_depth, lock_depth, bad_lock;
static int throne_scan_lock;
static const struct cred *override_creds(const struct cred *value) {
    (void)value; creds_depth++; return &test_cred;
}
static void revert_creds(const struct cred *value) { (void)value; creds_depth--; }
static void mutex_lock(int *lock) { (void)lock; if (lock_depth++) bad_lock++; }
static void mutex_unlock(int *lock) { (void)lock; if (--lock_depth) bad_lock++; }
static uid_t ksu_manager_uid = KSU_INVALID_UID;
static uid_t ksu_manager_appid = KSU_INVALID_UID;
static uid_t ksu_manager_package_appid = KSU_INVALID_UID;
static uid_t authenticated_manager_uid = KSU_INVALID_UID;
static int superkey_lock, superkey_spin_depth, superkey_spin_errors;
static int superkey_fail_count, password_failures;
static bool superkey_set, superkey_bypassed;
static uid_t test_current_uid;
struct kuid { uid_t val; };
static struct kuid current_uid(void) { return (struct kuid){ test_current_uid }; }
static void spin_lock(int *lock) { (void)lock; if (superkey_spin_depth++) superkey_spin_errors++; }
static void spin_unlock(int *lock) { (void)lock; if (--superkey_spin_depth) superkey_spin_errors++; }
static void atomic_set(int *value, int next) { *value = next; }
static bool superkey_is_set(void) { return superkey_set; }
static bool superkey_is_signature_bypassed(void) { return superkey_bypassed; }
static bool verify_superkey(const char *key) { return key && !strcmp(key, "correct-key"); }
static void superkey_on_auth_fail(void) { password_failures++; }
void superkey_invalidate(void);
uid_t superkey_get_manager_uid(void);
static int granted, pruned;
static int superkey_hook_calls;
void ksu_superkey_register_prctl_hook(void) { superkey_hook_calls++; }
static void ksu_set_manager_appid(uid_t appid) {
    granted++; ksu_manager_uid = ksu_manager_appid = appid;
}
static void ksu_invalidate_manager_appid(void) {
    ksu_manager_uid = ksu_manager_appid = KSU_INVALID_UID;
    superkey_invalidate();
}
static bool ksu_is_manager_uid_valid(void) {
    return ksu_manager_uid != KSU_INVALID_UID || superkey_get_manager_uid() != KSU_INVALID_UID;
}
static void ksu_prune_allowlist(bool (*present)(uid_t, char *, void *), void *data) {
    (void)present; (void)data; pruned++;
}
static union allocation { u64 alignment; char bytes[2048]; } allocations[128];
static bool allocation_live[128];
static int live_allocations, allocation_errors, fail_alloc_count;
static size_t fail_alloc_size;
static void *kzalloc(size_t size, int flags) {
    (void)flags;
    if (size == fail_alloc_size && fail_alloc_count && !--fail_alloc_count) return NULL;
    if (size > sizeof(allocations[0])) return NULL;
    for (int i = 0; i < 128; i++) if (!allocation_live[i]) {
        allocation_live[i] = true; live_allocations++;
        memset(allocations[i].bytes, 0, size); return allocations[i].bytes;
    }
    return NULL;
}
static void kfree(void *ptr) {
    if (!ptr) return;
    for (int i = 0; i < 128; i++) if (ptr == allocations[i].bytes && allocation_live[i]) {
        allocation_live[i] = false; live_allocations--; return;
    }
    allocation_errors++;
}
struct app_fixture {
    char directory[DATA_PATH_LEN];
    struct kstat stat;
    bool installed, metadata_error, trusted, mutate_during_read;
    bool fail_metadata_after_read;
    bool missing_base, directory_open_error, iterate_error;
};
static struct app_fixture apps[4];
static int directory_opens, package_opens, signatures, metadata_reads, file_closes;
static int iteration_depth, open_directories, vfs_errors, bad_directory_flags;
static int apk_io_during_iteration, apk_io_before_directory_close;
static struct super_block app_super = { 0xf2f52010 };
static struct inode dir_inode = { &app_super, 0 }, list_inode;
static struct file directory_file = { &dir_inode }, packages_file = { &list_inode };
static char packages_text[1024];
static int list_read_short, list_open_error;
struct path { struct app_fixture *app; };
static void record_apk_io(void) {
    if (iteration_depth) apk_io_during_iteration++;
    if (open_directories) apk_io_before_directory_close++;
}
static struct app_fixture *apk_fixture(const char *path) {
    for (int i = 0; i < 4; i++) {
        size_t n = strlen(apps[i].directory);
        if (apps[i].installed && !strncmp(path, apps[i].directory, n) &&
            !strcmp(path + n, "/base.apk")) return &apps[i];
    }
    return NULL;
}
static int kern_path(const char *name, unsigned int flags, struct path *out) {
    record_apk_io();
    (void)flags; out->app = apk_fixture(name);
    return !out->app || out->app->metadata_error ? -1 : 0;
}
static int vfs_getattr(const struct path *path, struct kstat *out, u32 mask, unsigned int flags) {
    record_apk_io();
    (void)mask; (void)flags; metadata_reads++; *out = path->app->stat; return 0;
}
static void path_put(struct path *path) { (void)path; }
static bool fixture_signature(char *path) {
    record_apk_io();
    struct app_fixture *app = apk_fixture(path); signatures++;
    if (!app) return false;
    if (app->mutate_during_read) app->stat.mtime.tv_nsec++;
    if (app->fail_metadata_after_read) app->metadata_error = true;
    return app->trusted;
}
static struct file *ksu_filp_open_nonotify(const char *path, int flags) {
    directory_opens++;
    if (!(flags & O_DIRECTORY)) bad_directory_flags++;
    for (int i = 0; i < 4; i++) {
        if (apps[i].installed && !strcmp(path, apps[i].directory)) {
            package_opens++;
            if (apps[i].directory_open_error) return NULL;
        }
    }
    if (open_directories++) vfs_errors++;
    strscpy(directory_file.name, path, sizeof(directory_file.name));
    return &directory_file;
}
static int filp_close(struct file *file, void *owner) {
    if (file == &directory_file && (--open_directories || iteration_depth)) vfs_errors++;
    (void)owner; file_closes++; return 0;
}
static int iterate_dir(struct file *file, struct dir_context *ctx) {
    int result = 0;
    if (iteration_depth++ || open_directories != 1) vfs_errors++;
    size_t n = strlen(file->name);
    char previous[DATA_PATH_LEN] = "";
    for (int i = 0; i < 4; i++) {
        if (!apps[i].installed) continue;
        if (!strcmp(file->name, apps[i].directory)) {
            if (apps[i].iterate_error) { result = -1; break; }
            if (apps[i].missing_base) {
                /* Similar names and non-regular entries cannot be APK candidates. */
                ctx->actor(ctx, "base.ap", 7, 0, 1, DT_REG);
                ctx->actor(ctx, "base.apk.extra", 14, 0, 2, DT_REG);
                ctx->actor(ctx, "base.apk", 8, 0, 3, DT_LNK);
            } else {
                ctx->actor(ctx, "base.apk", 8, 0, 1, DT_REG);
            }
            break;
        }
        if (strncmp(file->name, apps[i].directory, n) || apps[i].directory[n] != '/') continue;
        const char *child = apps[i].directory + n + 1, *slash = strchr(child, '/');
        size_t length = slash ? (size_t)(slash - child) : strlen(child);
        if (strlen(previous) == length && !strncmp(previous, child, length)) continue;
        memcpy(previous, child, length); previous[length] = 0;
        ctx->actor(ctx, child, length, 0, i + 1, DT_DIR);
    }
    iteration_depth--;
    return result;
}
static struct file *filp_open(const char *name, int flags, int mode) {
    (void)flags; (void)mode;
    if (list_open_error || strcmp(name, SYSTEM_PACKAGES_LIST_PATH)) return NULL;
    list_inode.size = strlen(packages_text); return &packages_file;
}
static struct inode *file_inode(struct file *file) { return file->f_inode; }
static loff_t i_size_read(struct inode *inode) { return inode->size; }
static ssize_t kernel_read(struct file *file, void *out, size_t size, loff_t *position) {
    (void)file; (void)position;
    size_t count = list_read_short && size ? size - 1 : size;
    memcpy(out, packages_text, count); return count;
}
"""
    structs = "\n".join(
        extracted(marker).rstrip() + ";"
        for marker in ("struct uid_data {", "struct data_path {", "struct apk_scan_entry {", "struct my_dir_context {")
    )
    helpers = "\n#define is_manager_apk fixture_signature\nstatic LIST_HEAD(apk_scan_cache);\n"
    helpers += "\n".join(function("kernel/manager/apk_sign.c", marker) for marker in (
        "static bool matches_manager_package", "bool is_manager_package_dir", "bool is_manager_package(const",
    )) + "\n"
    helpers += "".join(extracted(marker) for marker in (
        "static void crown_manager",
        "static void clear_apk_scan_cache",
        "static struct apk_scan_entry *find_apk_scan_entry",
        "static bool get_apk_appid",
        "static bool stat_apk",
        "static bool same_apk_stat",
        "static bool cached_apk_directory",
        "static void scan_manager_apk",
        "static bool my_actor",
        "static void search_manager",
        "static bool is_uid_exist",
        "static bool track_throne_locked",
        "void track_throne(bool",
        "bool ksu_is_manager_package_uid",
        "bool ksu_refresh_manager_package",
    ))
    helpers += "\n".join(function("kernel/manager/superkey.c", marker) for marker in (
        "void superkey_invalidate", "uid_t superkey_get_manager_uid",
        "bool superkey_on_auth_success", "int superkey_authenticate", "bool superkey_is_manager",
    )) + "\n"
    fixtures = r"""
static struct uid_data identities[4];
static LIST_HEAD(uid_list);
static void reset_counts(void) {
    directory_opens = package_opens = signatures = metadata_reads = file_closes = 0;
    apk_io_during_iteration = apk_io_before_directory_close = bad_directory_flags = 0;
    granted = pruned = superkey_hook_calls = 0;
}
static void init_fixture(void) {
    clear_apk_scan_cache();
    memset(apps, 0, sizeof(apps));
    memset(identities, 0, sizeof(identities));
    INIT_LIST_HEAD(&uid_list);
    strscpy(apps[0].directory, "/data/app/~~one/com.example.one-a", DATA_PATH_LEN);
    strscpy(apps[1].directory, "/data/app/~~two/com.example.one-b", DATA_PATH_LEN);
    strscpy(apps[2].directory, "/data/app/~~three/com.example.one-c", DATA_PATH_LEN);
    strscpy(apps[3].directory, "/data/app/~~four/com.example.one-d", DATA_PATH_LEN);
    const char *names[] = { "com.example.one", "com.example.two", "com.example.three", "com.example.four" };
    for (int i = 0; i < 4; i++) {
        apps[i].stat = (struct kstat){ .dev = 55, .ino = 100 + i, .size = 1000,
            .mtime = { 10, 11 }, .ctime = { 12, 13 }, .mode = S_IFREG,
            .result_mask = STATX_BASIC_STATS };
        identities[i].appid = 10001 + i;
        strscpy(identities[i].package, names[i], KSU_MAX_PACKAGE_NAME);
        list_add_tail(&identities[i].list, &uid_list);
    }
    apps[0].installed = apps[1].installed = true;
    strscpy(packages_text, "com.example.one 10001 0\ncom.example.two 10002 0\n", sizeof(packages_text));
    list_read_short = list_open_error = 0;
    fail_alloc_size = fail_alloc_count = 0;
    ksu_manager_appid = ksu_manager_uid = ksu_manager_package_appid = authenticated_manager_uid = KSU_INVALID_UID;
    superkey_set = superkey_bypassed = false;
    password_failures = superkey_fail_count = 0;
    reset_counts();
}
static void run_scan(void) { reset_counts(); search_manager("/data/app", 2, &uid_list); }
static int cache_entries(void) {
    struct apk_scan_entry *entry; int count = 0;
    list_for_each_entry(entry, &apk_scan_cache, list) count++;
    return count;
}
"""
    config = "#define CONFIG_KSU_SUPERKEY 1\n" if superkey else ""
    return COMMON + config + preamble + structs + helpers + fixtures + "\n#undef is_manager_apk\n"


def cache_source():
    return harness() + r"""
int main(void) {
    init_fixture();
    run_scan();
    CHECK(package_opens == 2 && signatures == 2 && cache_entries() == 2);
    CHECK(!granted);
    for (int i = 0; i < 3; i++) {
        run_scan();
        CHECK(package_opens == 0 && signatures == 0 && cache_entries() == 2);
        CHECK(directory_opens == 3 && file_closes == directory_opens);
        CHECK(!granted);
    }

    /* The inventory still discovers new installs and changed install paths. */
    apps[2].installed = true;
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && cache_entries() == 3);
    strscpy(apps[0].directory, "/data/app/~~replacement/com.example.one-b", DATA_PATH_LEN);
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && cache_entries() == 3);
    identities[0].appid++;
    run_scan(); CHECK(package_opens == 3 && signatures == 3);

    /* Every identity field that can distinguish an APK replacement invalidates. */
    apps[0].stat.ino++;
    run_scan(); CHECK(package_opens == 1 && signatures == 1);
    apps[0].stat.dev++;
    run_scan(); CHECK(package_opens == 1 && signatures == 1);
    apps[0].stat.size++;
    run_scan(); CHECK(package_opens == 1 && signatures == 1);
    apps[0].stat.mtime.tv_sec++;
    run_scan(); CHECK(package_opens == 1 && signatures == 1);
    apps[0].stat.mtime.tv_nsec++;
    run_scan(); CHECK(package_opens == 1 && signatures == 1);
    apps[0].stat.ctime.tv_sec++;
    run_scan(); CHECK(package_opens == 1 && signatures == 1);
    apps[0].stat.ctime.tv_nsec++;
    run_scan(); CHECK(package_opens == 1 && signatures == 1);
    run_scan(); CHECK(!package_opens && !signatures && !granted);

    apps[0].installed = false;
    run_scan(); CHECK(!package_opens && !signatures && cache_entries() == 2);
    apps[0].installed = true;
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && cache_entries() == 3);
    list_del(&identities[0].list);
    run_scan(); CHECK(!cache_entries() && !granted);
    run_scan(); CHECK(!cache_entries() && !granted);
    list_add_tail(&identities[0].list, &uid_list);
    run_scan(); CHECK(package_opens == 3 && signatures == 3 && cache_entries() == 3);

    /* The legacy direct layout and long randomized parent paths work too. */
    strscpy(apps[0].directory, "/data/app/com.example.one-direct", DATA_PATH_LEN);
    run_scan(); CHECK(package_opens == 1 && signatures == 1);
    run_scan(); CHECK(!package_opens && !signatures);
    strscpy(apps[0].directory, "/data/app/~~", DATA_PATH_LEN);
    size_t prefix = strlen(apps[0].directory);
    memset(apps[0].directory + prefix, 'x', 245);
    strscpy(apps[0].directory + prefix + 245, "/com.example.one-long",
            DATA_PATH_LEN - prefix - 245);
    run_scan(); CHECK(package_opens == 1 && signatures == 1);
    run_scan(); CHECK(!package_opens && !signatures);
    clear_apk_scan_cache();
    CHECK(!live_allocations && !allocation_errors);
    return 0;
}
"""


def failures_source():
    return harness() + r"""
int main(void) {
    init_fixture(); run_scan();
    apps[0].metadata_error = true;
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && cache_entries() == 1);
    run_scan(); CHECK(package_opens == 1 && signatures == 1);
    apps[0].metadata_error = false;
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && cache_entries() == 2);
    run_scan(); CHECK(!package_opens && !signatures);

    apps[0].stat.result_mask &= ~STATX_CTIME;
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && cache_entries() == 1);
    apps[0].stat.result_mask = STATX_BASIC_STATS;
    apps[0].stat.mode = 0040000;
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && cache_entries() == 1);
    apps[0].stat.mode = S_IFREG;
    apps[0].mutate_during_read = true;
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && cache_entries() == 1);
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && cache_entries() == 1);
    apps[0].mutate_during_read = false;
    apps[0].fail_metadata_after_read = true;
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && cache_entries() == 1);
    apps[0].metadata_error = apps[0].fail_metadata_after_read = false;
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && cache_entries() == 2);
    run_scan(); CHECK(!package_opens && !signatures);

    /* Allocation failure cannot turn an unread or unverified APK into a hit. */
    clear_apk_scan_cache(); apps[1].installed = false;
    fail_alloc_size = sizeof(struct apk_scan_entry); fail_alloc_count = 1;
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && !cache_entries());
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && cache_entries() == 1);
    run_scan(); CHECK(!package_opens && !signatures && !granted);

    /* A changed APK can become a valid manager, but only a signature grants it. */
    apps[0].trusted = true; apps[0].stat.ctime.tv_nsec++;
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && granted == 1);
    CHECK(ksu_manager_appid == identities[0].appid && !cache_entries());
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && granted == 1);
    apps[0].trusted = false;
    run_scan(); CHECK(package_opens == 1 && signatures == 1 && !granted);
    run_scan(); CHECK(!package_opens && !signatures && !granted);
    clear_apk_scan_cache();
    CHECK(!live_allocations && !allocation_errors);
    return 0;
}
"""


def deferred_scan_source():
    return harness() + r"""
int main(void) {
    init_fixture();
    apps[2].installed = apps[3].installed = true;
    apps[0].trusted = apps[1].trusted = true;
    run_scan();
    /* Real APK metadata and signature I/O must happen after iteration and close. */
    CHECK(!apk_io_during_iteration && !apk_io_before_directory_close);
    CHECK(!iteration_depth && !open_directories && !vfs_errors && !bad_directory_flags);
    CHECK(package_opens == 4 && signatures == 4 && granted == 2);
    CHECK(ksu_manager_appid == identities[0].appid && cache_entries() == 2);

    /* Each directory gets a fresh candidate, even when a later directory has no
     * regular APK or cannot enumerate. Trusted matches must not stop discovery. */
    init_fixture();
    apps[2].installed = apps[3].installed = true;
    apps[0].trusted = true;
    apps[1].missing_base = true;
    apps[2].iterate_error = true;
    run_scan();
    CHECK(package_opens == 4 && signatures == 2 && granted == 1);
    CHECK(cache_entries() == 1);
    CHECK(!apk_io_during_iteration && !apk_io_before_directory_close);
    CHECK(!iteration_depth && !open_directories && !vfs_errors && !bad_directory_flags);

    clear_apk_scan_cache();
    apps[2].iterate_error = false;
    apps[2].directory_open_error = true;
    run_scan();
    CHECK(package_opens == 4 && signatures == 2 && granted == 1);
    CHECK(file_closes == directory_opens - 1 && cache_entries() == 1);
    CHECK(!apk_io_during_iteration && !apk_io_before_directory_close);
    CHECK(!iteration_depth && !open_directories && !vfs_errors && !bad_directory_flags);
    clear_apk_scan_cache();
    CHECK(!live_allocations && !allocation_errors);
    return 0;
}
"""


def tracker_source():
    return harness() + r"""
int main(void) {
    init_fixture();
    track_throne(false);
    CHECK(package_opens == 2 && signatures == 2 && pruned == 1);
    reset_counts(); track_throne(false);
    CHECK(!package_opens && !signatures && pruned == 1);
    /* Metadata-only package-list rewrites retain the same APK identities. */
    strscpy(packages_text, "com.example.one    10001 changed metadata\ncom.example.two\t10002 other\n", sizeof(packages_text));
    reset_counts(); track_throne(false);
    CHECK(!package_opens && !signatures && pruned == 1);
    /* Replacing a previously rejected APK requests a fresh signature read. */
    apps[0].trusted = true;
    apps[0].stat.ctime.tv_nsec++;
    reset_counts(); track_throne(false);
    CHECK(package_opens == 1 && signatures == 1 && granted == 1 && pruned == 1);
    CHECK(ksu_manager_appid == 10001);
    CHECK(!creds_depth && !lock_depth && !bad_lock);

    /* Failed/incomplete list snapshots must not prune or clear manager state. */
    reset_counts(); list_read_short = 1; track_throne(false);
    CHECK(!pruned && !directory_opens && ksu_manager_appid == 10001);
    CHECK(!creds_depth && !lock_depth);
    list_read_short = 0;
    list_open_error = 1;
    reset_counts(); track_throne(false);
    CHECK(!pruned && !directory_opens && ksu_manager_appid == 10001);
    list_open_error = 0;
    strscpy(packages_text, " \t\r\n\n", sizeof(packages_text));
    reset_counts(); track_throne(false);
    CHECK(!pruned && !directory_opens && ksu_manager_appid == 10001);
    strscpy(packages_text, "com.example.two 10002\nbroken-row\n", sizeof(packages_text));
    reset_counts(); track_throne(false);
    CHECK(!pruned && !directory_opens && ksu_manager_appid == 10001);
    strscpy(packages_text, "com.example.two 10002\nbroken.uid NaN\n", sizeof(packages_text));
    reset_counts(); track_throne(false);
    CHECK(!pruned && !directory_opens && ksu_manager_appid == 10001);
    strscpy(packages_text, "com.example.two 10002\ncom.example.one 10001\n", sizeof(packages_text));
    fail_alloc_size = sizeof(struct uid_data); fail_alloc_count = 2;
    reset_counts(); track_throne(false);
    CHECK(!pruned && !directory_opens && ksu_manager_appid == 10001);
    CHECK(!creds_depth && !lock_depth && !allocation_errors);

    reset_counts(); track_throne(false);
    CHECK(!package_opens && !signatures && pruned == 1);
    reset_counts(); track_throne(true);
    CHECK(pruned == 1 && !directory_opens && !signatures);
    CHECK(!creds_depth && !lock_depth && !bad_lock);
    clear_apk_scan_cache();
    CHECK(!live_allocations && !allocation_errors);
    return 0;
}
"""


def package_pin_source():
    return harness() + r"""
int main(void) {
    /* Package matching accepts randomized and legacy layouts, but never a
     * prefix, an ancestor directory, a malformed name or another APK file. */
    CHECK(is_manager_package("/data/app/~~random/com.example.one-token/base.apk"));
    CHECK(is_manager_package("/data/app/com.example.one-token/base.apk"));
    CHECK(!is_manager_package(NULL));
    CHECK(!is_manager_package(""));
    CHECK(!is_manager_package("com.example.one-token/base.apk"));
    CHECK(!is_manager_package("/base.apk"));
    CHECK(!is_manager_package("/data/app/com.example.one-token/split.apk"));
    CHECK(!is_manager_package("/data/app/com.example.one-token/base.apk.extra"));
    CHECK(!is_manager_package("/data/app/com.example.one-token/other/base.apk"));
    CHECK(!is_manager_package("/data/app/com.example.one.extra-token/base.apk"));
    CHECK(!is_manager_package("/data/app/com.example.on-token/base.apk"));
    CHECK(!is_manager_package("/data/app/com.example.one/base.apk"));
    CHECK(!is_manager_package("/data/app/com.example.one-/base.apk"));

    init_fixture();
    strscpy(apps[1].directory, "/data/app/~~two/com.example.two-a", DATA_PATH_LEN);
    apps[0].trusted = apps[1].trusted = true;
    run_scan();
    /* A second APK with the exact release certificate must be rejected before
     * opening its installation directory or performing any APK I/O. */
    CHECK(package_opens == 1 && signatures == 1 && granted == 1);
    CHECK(ksu_manager_appid == 10001 && !cache_entries());
    CHECK(!apk_io_during_iteration && !apk_io_before_directory_close);
    apps[0].installed = false;
    ksu_manager_uid = ksu_manager_appid = KSU_INVALID_UID;
    run_scan();
    CHECK(!package_opens && !signatures && !metadata_reads && !granted);
    CHECK(ksu_manager_appid == KSU_INVALID_UID && !cache_entries());
    strscpy(apps[1].directory, "/data/app/com.example.one.extra-a", DATA_PATH_LEN);
    run_scan();
    CHECK(!package_opens && !signatures && !metadata_reads && !granted);
    strscpy(apps[1].directory, "/data/app/com.example.one-/base", DATA_PATH_LEN);
    run_scan();
    CHECK(!package_opens && !signatures && !metadata_reads && !granted);

    /* A pinned package still needs the matching certificate. */
    init_fixture();
    apps[1].installed = false;
    run_scan();
    CHECK(signatures == 1 && !granted && cache_entries() == 1);
    run_scan();
    CHECK(!signatures && !granted);
    apps[0].trusted = true;
    apps[0].stat.ctime.tv_nsec++;
    run_scan();
    CHECK(signatures == 1 && granted == 1 && ksu_manager_appid == 10001);
    clear_apk_scan_cache();
    CHECK(!live_allocations && !allocation_errors);
    return 0;
}
"""


def package_identity_source():
    identity = function("kernel/manager/manager_identity.h", "static inline bool ksu_is_uid_manager(uid_t uid)\n{\n\tuid_t appid")
    return harness(superkey=True) + identity + r"""
int main(void) {
    init_fixture();
    strscpy(apps[1].directory, "/data/app/~~two/com.example.two-a", DATA_PATH_LEN);
    apps[0].trusted = apps[1].trusted = true;
    track_throne(false);
    CHECK(granted == 1 && ksu_manager_appid == 10001);
    CHECK(ksu_is_uid_manager(110001) && !ksu_is_uid_manager(10002));
    reset_counts(); track_throne(false);
    CHECK(!directory_opens && !signatures && ksu_manager_appid == 10001);

    /* Reinstallation moves the package to a new appid. An unrelated package
     * reusing the old appid cannot keep the old manager's privilege. */
    strscpy(packages_text, "com.example.two 10001\ncom.example.one 10003\n", sizeof(packages_text));
    reset_counts(); track_throne(false);
    CHECK(granted == 1 && ksu_manager_appid == 10003 && ksu_manager_uid == 10003);
    CHECK(!ksu_is_uid_manager(10001) && ksu_is_uid_manager(110003));

    /* UID presence alone, including a package-name prefix, cannot retain a
     * removed release manager's access. A matching certificate cannot regrant it. */
    apps[0].installed = false;
    strscpy(packages_text, "com.example.two 10003\ncom.example.one.extra 10003\n", sizeof(packages_text));
    reset_counts(); track_throne(false);
    CHECK(!granted && ksu_manager_appid == KSU_INVALID_UID);
    CHECK(ksu_manager_uid == KSU_INVALID_UID && !ksu_is_uid_manager(110003));
    CHECK(!signatures && !metadata_reads && !package_opens);

    /* The package still needs its trusted certificate after a UID change. */
    apps[0].installed = true;
    apps[0].trusted = false;
    strscpy(packages_text, "com.example.one 10004\ncom.example.two 10003\n", sizeof(packages_text));
    reset_counts(); track_throne(false);
    CHECK(!granted && ksu_manager_appid == KSU_INVALID_UID);
    CHECK(!ksu_is_uid_manager(10004));
    CHECK(!creds_depth && !lock_depth && !bad_lock);
    clear_apk_scan_cache();
    CHECK(!live_allocations && !allocation_errors);
    return 0;
}
"""


def manager_signature_api_source():
    doubles = r"""
static bool check_v2_signature(char *path) { return fixture_signature(path); }
"""
    production = function("kernel/manager/apk_sign.c", "bool is_manager_apk(char")
    return harness(superkey=True) + doubles + production + r"""
int main(void) {
    char path[DATA_PATH_LEN];
    init_fixture();
    strscpy(apps[1].directory, "/data/app/~~two/com.example.two-a", DATA_PATH_LEN);
    apps[0].trusted = apps[1].trusted = true;
    snprintf(path, sizeof(path), "%s/base.apk", apps[0].directory);
    CHECK(is_manager_apk(path) && signatures == 1);
    snprintf(path, sizeof(path), "%s/base.apk", apps[1].directory);
    reset_counts();
    CHECK(!is_manager_apk(path) && !signatures);
    CHECK(is_manager_apk(NULL) && !signatures);

    /* Bound the package component, not the randomized installation path. */
    strscpy(apps[0].directory, "/data/app/~~", DATA_PATH_LEN);
    size_t prefix = strlen(apps[0].directory);
    memset(apps[0].directory + prefix, 'x', 245);
    strscpy(apps[0].directory + prefix + 245, "/com.example.one-long",
            DATA_PATH_LEN - prefix - 245);
    snprintf(path, sizeof(path), "%s/base.apk", apps[0].directory);
    CHECK(strlen(path) > KSU_MAX_PACKAGE_NAME && is_manager_package(path));
    CHECK(is_manager_apk(path));
    strscpy(apps[0].directory, "/data/app/com.example.one-direct", DATA_PATH_LEN);
    snprintf(path, sizeof(path), "%s/base.apk", apps[0].directory);
    CHECK(is_manager_apk(path));
    apps[0].trusted = false;
    CHECK(!is_manager_apk(path));
    superkey_set = superkey_bypassed = true;
    reset_counts();
    CHECK(!is_manager_apk(path) && !is_manager_apk(NULL) && !signatures);
    superkey_bypassed = false;
    apps[0].trusted = true;
    CHECK(is_manager_apk(path) && signatures == 1);
    clear_apk_scan_cache();
    CHECK(!live_allocations && !allocation_errors);
    return 0;
}
"""


def package_refresh_source():
    return harness(superkey=True) + r"""
int main(void) {
    init_fixture();
    /* Password authentication refreshes package identity without treating an
     * unsigned APK as a signature-authorized manager or doing APK I/O. */
    CHECK(ksu_refresh_manager_package(110001));
    CHECK(ksu_manager_package_appid == 10001 && ksu_manager_appid == KSU_INVALID_UID);
    CHECK(!directory_opens && !signatures && !granted && pruned == 1);
    CHECK(!ksu_refresh_manager_package(10002));
    CHECK(ksu_is_manager_package_uid(210001));

    /* Authentication fails closed on a failed snapshot even if the cached
     * package identity matches. The prior snapshot is retained for observers. */
    list_read_short = 1;
    CHECK(!ksu_refresh_manager_package(110001));
    CHECK(ksu_manager_package_appid == 10001);
    list_read_short = 0;
    list_open_error = 1;
    CHECK(!ksu_refresh_manager_package(110001));
    list_open_error = 0;
    strscpy(packages_text, "com.example.one 10001\nbroken-row\n", sizeof(packages_text));
    CHECK(!ksu_refresh_manager_package(110001));
    strscpy(packages_text, "com.example.one 10001\ncom.example.two 10002\n", sizeof(packages_text));
    fail_alloc_size = sizeof(struct uid_data); fail_alloc_count = 2;
    CHECK(!ksu_refresh_manager_package(110001));
    CHECK(ksu_manager_package_appid == 10001);

    /* A reassigned UID or a prefix collision cannot pass the password gate. */
    strscpy(packages_text, "com.example.two 10001\ncom.example.one 10003\n", sizeof(packages_text));
    CHECK(!ksu_refresh_manager_package(110001));
    CHECK(ksu_refresh_manager_package(210003));
    CHECK(!ksu_is_manager_package_uid(10001));
    strscpy(packages_text, "com.example.one.extra 10003\n", sizeof(packages_text));
    CHECK(!ksu_refresh_manager_package(110003));
    CHECK(ksu_manager_package_appid == KSU_INVALID_UID);
    CHECK(!directory_opens && !signatures && !granted);
    CHECK(!live_allocations && !allocation_errors && !creds_depth && !lock_depth && !bad_lock);
    return 0;
}
"""


def superkey_auth_source():
    identity = function("kernel/manager/manager_identity.h", "static inline bool ksu_is_uid_manager(uid_t uid)\n{\n\tuid_t appid")
    return harness(superkey=True) + identity + r"""
int main(void) {
    init_fixture();
    superkey_set = superkey_bypassed = true;
    test_current_uid = 10002;
    CHECK(superkey_authenticate("correct-key") == -EACCES);
    CHECK(superkey_get_manager_uid() == KSU_INVALID_UID && !superkey_is_manager());
    CHECK(!ksu_is_uid_manager(10002) && !directory_opens && !signatures && !granted);

    /* The password verifier is doubled; the production authentication path
     * must still enforce its result and obtain a complete package snapshot. */
    test_current_uid = 110001;
    CHECK(superkey_authenticate("wrong-key") == -EACCES && password_failures == 1);
    list_read_short = 1;
    CHECK(superkey_authenticate("correct-key") == -EACCES);
    CHECK(superkey_get_manager_uid() == KSU_INVALID_UID);
    list_read_short = 0;
    CHECK(superkey_authenticate("correct-key") == 0);
    CHECK(superkey_get_manager_uid() == 110001 && superkey_is_manager());
    CHECK(ksu_is_uid_manager(210001) && !ksu_is_uid_manager(10002));
    CHECK(ksu_manager_appid == KSU_INVALID_UID && ksu_manager_uid == KSU_INVALID_UID);
    CHECK(!directory_opens && !signatures && !granted);

    /* Repeated snapshots must retain a password-only manager. */
    track_throne(false);
    CHECK(superkey_get_manager_uid() == 110001 && superkey_is_manager());
    CHECK(ksu_is_uid_manager(210001) && ksu_manager_appid == KSU_INVALID_UID);
    CHECK(superkey_on_auth_success(210001));
    CHECK(superkey_get_manager_uid() == 210001);

    /* Reinstalling under a new uid revokes the old password identity even
     * when another installed package reuses the former uid. */
    strscpy(packages_text, "com.example.two 10001\ncom.example.one 10003\n", sizeof(packages_text));
    track_throne(true);
    CHECK(superkey_get_manager_uid() == KSU_INVALID_UID && !ksu_is_uid_manager(110001));
    CHECK(superkey_authenticate("correct-key") == -EACCES);
    test_current_uid = 210003;
    CHECK(superkey_authenticate("correct-key") == 0 && superkey_is_manager());
    CHECK(ksu_is_uid_manager(110003) && !ksu_is_uid_manager(10001));
    strscpy(packages_text, "com.example.two 10003\n", sizeof(packages_text));
    track_throne(true);
    CHECK(superkey_get_manager_uid() == KSU_INVALID_UID && !ksu_is_uid_manager(110003));
    CHECK(!superkey_on_auth_success(210003));

    /* A stale stored password uid cannot bypass the current package binding. */
    authenticated_manager_uid = 210003;
    CHECK(superkey_get_manager_uid() == KSU_INVALID_UID && !superkey_is_manager());
    clear_apk_scan_cache();
    CHECK(!live_allocations && !allocation_errors && !creds_depth && !lock_depth && !bad_lock);
    CHECK(!superkey_spin_depth && !superkey_spin_errors);
    return 0;
}
"""


def workqueue_source():
    doubles = r"""
struct work_struct { int unused; };
struct delayed_work {
    struct work_struct work;
    void (*callback)(struct work_struct *);
    bool pending;
};
static struct delayed_work throne_search_work;
static int throne_work_lock, spin_depth, work_errors, queued, cancelled;
static bool throne_work_stopping = true;
static void *system_wq;
static unsigned long delay;
static void spin_enter(void) { if (spin_depth++) work_errors++; }
static void spin_leave(void) { if (--spin_depth) work_errors++; }
#define spin_lock_irqsave(lock, flags) do { (void)(lock); (flags) = 0; spin_enter(); } while (0)
#define spin_unlock_irqrestore(lock, flags) do { (void)(lock); (void)(flags); spin_leave(); } while (0)
#define msecs_to_jiffies(ms) (ms)
#define INIT_DELAYED_WORK(w, fn) do { (w)->callback = (fn); (w)->pending = false; } while (0)
static void schedule_delayed_work(struct delayed_work *work, unsigned long ms) {
    if (spin_depth != 1 || throne_work_stopping) work_errors++;
    queued++; work->pending = true; delay = ms;
}
static void mod_delayed_work(void *queue, struct delayed_work *work, unsigned long ms) {
    (void)queue; schedule_delayed_work(work, ms);
}
void ksu_schedule_manager_scan(void);
static void cancel_delayed_work_sync(struct delayed_work *work) {
    if (spin_depth || lock_depth || !throne_work_stopping) work_errors++;
    /* Exercise an observer callback arriving after the stop flag is set. */
    int was_queued = queued;
    ksu_schedule_manager_scan();
    if (queued != was_queued) work_errors++;
    cancelled++; work->pending = false;
}
static int memcmp(const void *left, const void *right, size_t n) {
    const unsigned char *a = left, *b = right;
    for (size_t i = 0; i < n; i++) if (a[i] != b[i]) return a[i] - b[i];
    return 0;
}
struct fsnotify_mark { int unused; };
struct qstr { unsigned int len; const char *name; };
#define FS_ISDIR 0x10000000
#define FS_MOVED_TO 0x80
static void run_queued_work(void) {
    if (!throne_search_work.pending) { work_errors++; return; }
    throne_search_work.pending = false;
    throne_search_work.callback(&throne_search_work.work);
}
"""
    production = "".join(extracted(marker) for marker in (
        "static void do_throne_search",
        "void ksu_schedule_manager_scan",
        "void ksu_throne_tracker_init",
        "void ksu_throne_tracker_exit",
    ))
    production += function("kernel/manager/pkg_observer.c", "static int ksu_handle_inode_event")
    body = r"""
int main(void) {
    struct qstr name = {13, "packages.list"}, unrelated = {13, "packages-lost"};
    init_fixture();
    ksu_schedule_manager_scan();
    CHECK(!queued && !directory_opens);
    ksu_throne_tracker_init();
    CHECK(queued == 1 && delay == 3000 && !directory_opens);
    ksu_handle_inode_event(NULL, FS_MOVED_TO, NULL, NULL, NULL, 0);
    ksu_handle_inode_event(NULL, FS_MOVED_TO | FS_ISDIR, NULL, NULL, &name, 0);
    ksu_handle_inode_event(NULL, FS_MOVED_TO, NULL, NULL, &unrelated, 0);
    CHECK(queued == 1 && !directory_opens);
    for (int i = 0; i < 3; i++)
        ksu_handle_inode_event(NULL, FS_MOVED_TO, NULL, NULL, &name, 0);
    CHECK(queued == 4 && delay == 100 && !directory_opens && !lock_depth);
    run_queued_work();
    CHECK(package_opens == 2 && signatures == 2 && cache_entries() == 2);
    reset_counts();
    ksu_handle_inode_event(NULL, FS_MOVED_TO, NULL, NULL, &name, 0);
    CHECK(!directory_opens);
    run_queued_work();
    CHECK(!package_opens && !signatures && cache_entries() == 2);

    ksu_schedule_manager_scan();
    int before_stop = queued;
    ksu_throne_tracker_exit();
    CHECK(throne_work_stopping && !throne_search_work.pending && cancelled == 1);
    CHECK(queued == before_stop && !cache_entries() && !live_allocations);
    ksu_handle_inode_event(NULL, FS_MOVED_TO, NULL, NULL, &name, 0);
    CHECK(queued == before_stop && !throne_search_work.pending);
    CHECK(!work_errors && !spin_depth && !lock_depth && !bad_lock && !allocation_errors);
    return 0;
}
"""
    return harness() + doubles + production + body


def certificate_block_source():
    doubles = r"""
typedef unsigned int u32;
typedef unsigned long long u64;
typedef long long loff_t;
#define INT_MAX 2147483647
#define SHA256_DIGEST_SIZE 32
#define EXPECTED_SIZE 4
#define EXPECTED_HASH "0102030401020304010203040102030401020304010203040102030401020304"
#define pr_info(...) ((void)0)
struct file { unsigned char bytes[1600]; size_t size; } certificate_file;
static int reads, fail_read, hashes;
static bool hash_error;
static ssize_t kernel_read(struct file *file, void *output, size_t size, loff_t *pos) {
    reads++;
    if (reads == fail_read || *pos < 0 || (u64)*pos > file->size) return -1;
    size_t remaining = file->size - *pos;
    if (size > remaining) size = remaining;
    memcpy(output, file->bytes + *pos, size); *pos += size; return size;
}
/* Deterministic hash double verifies the certificate bytes and size passed by
 * the production parser; actual SHA-256 is provided by the kernel crypto API. */
static int ksu_sha256(const void *data, unsigned int length, unsigned char *digest) {
    const unsigned char *certificate = data;
    hashes++;
    if (hash_error || !length) return -1;
    for (int i = 0; i < SHA256_DIGEST_SIZE; i++) digest[i] = certificate[i % length];
    return 0;
}
static char *bin2hex(char *output, const void *input, size_t size) {
    const char hex[] = "0123456789abcdef";
    const unsigned char *bytes = input;
    for (size_t i = 0; i < size; i++) {
        output[2 * i] = hex[bytes[i] >> 4];
        output[2 * i + 1] = hex[bytes[i] & 15];
    }
    return output + size * 2;
}
static void put_u32(size_t offset, u32 value) {
    for (int i = 0; i < 4; i++) certificate_file.bytes[offset + i] = value >> (i * 8);
}
static void build_certificate(u32 size) {
    memset(&certificate_file, 0, sizeof(certificate_file));
    /* signers -> signer -> signed data -> digests, certificates -> certificate */
    put_u32(0, 20 + size); put_u32(4, 16 + size); put_u32(8, 12 + size);
    put_u32(12, 0); put_u32(16, 4 + size); put_u32(20, size);
    for (u32 i = 0; i < size; i++) certificate_file.bytes[24 + i] = i % 4 + 1;
    certificate_file.size = 24 + size;
    reads = fail_read = hashes = 0;
    hash_error = false;
}
"""
    production = "\n".join(function("kernel/manager/apk_sign.c", marker) for marker in (
        "static bool read_exact", "static bool read_length_prefixed_end", "static bool check_block",
    ))
    return COMMON + doubles + production + r"""
int main(void) {
    loff_t pos;
    build_certificate(EXPECTED_SIZE);
    pos = 0;
    CHECK(check_block(&certificate_file, &pos, certificate_file.size));
    CHECK(hashes == 1 && pos == certificate_file.size);
    build_certificate(EXPECTED_SIZE);
    certificate_file.bytes[27] ^= 1;
    pos = 0;
    CHECK(!check_block(&certificate_file, &pos, certificate_file.size) && hashes == 1);

    const u32 wrong_sizes[] = { 0, EXPECTED_SIZE - 1, EXPECTED_SIZE + 1, 1025 };
    for (size_t i = 0; i < sizeof(wrong_sizes) / sizeof(wrong_sizes[0]); i++) {
        build_certificate(wrong_sizes[i]); pos = 0;
        CHECK(!check_block(&certificate_file, &pos, certificate_file.size) && !hashes);
    }
    build_certificate(EXPECTED_SIZE); hash_error = true; pos = 0;
    CHECK(!check_block(&certificate_file, &pos, certificate_file.size) && hashes == 1);

    /* All nested lengths are bounded by their parent, including u32 overflow. */
    for (size_t offset = 0; offset <= 20; offset += 4) {
        build_certificate(EXPECTED_SIZE); put_u32(offset, 0xffffffffU); pos = 0;
        CHECK(!check_block(&certificate_file, &pos, certificate_file.size) && !hashes);
    }
    build_certificate(EXPECTED_SIZE); put_u32(8, 4); pos = 0;
    CHECK(!check_block(&certificate_file, &pos, certificate_file.size) && !hashes);
    for (loff_t length = 0; length < 24 + EXPECTED_SIZE; length++) {
        build_certificate(EXPECTED_SIZE); certificate_file.size = length; pos = 0;
        CHECK(!check_block(&certificate_file, &pos, 24 + EXPECTED_SIZE) && !hashes);
        build_certificate(EXPECTED_SIZE); pos = 0;
        CHECK(!check_block(&certificate_file, &pos, length) && !hashes);
    }
    for (int failure = 1; failure <= 7; failure++) {
        build_certificate(EXPECTED_SIZE); fail_read = failure; pos = 0;
        CHECK(!check_block(&certificate_file, &pos, certificate_file.size) && !hashes);
    }
    build_certificate(EXPECTED_SIZE); pos = -1;
    CHECK(!check_block(&certificate_file, &pos, certificate_file.size) && !reads);
    pos = certificate_file.size + 1;
    CHECK(!check_block(&certificate_file, &pos, certificate_file.size) && !reads);
    return 0;
}
"""


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cc", default=os.environ.get("CC") or shutil.which("clang") or shutil.which("cc"))
    args = parser.parse_args()
    if not args.cc:
        parser.error("a C compiler is required; pass --cc")
    compiler = shutil.which(args.cc) or str(Path(args.cc).resolve())
    run_test(compiler, "manager-directory-cache", cache_source())
    run_test(compiler, "manager-cache-failures-and-auth", failures_source())
    run_test(compiler, "manager-scan-after-directory-iteration", deferred_scan_source())
    run_test(compiler, "manager-tracker-snapshots", tracker_source())
    run_test(compiler, "manager-package-pin-before-apk-io", package_pin_source())
    run_test(compiler, "manager-package-identity-and-uid-reuse", package_identity_source())
    run_test(compiler, "manager-package-signature-api", manager_signature_api_source())
    run_test(compiler, "manager-package-refresh-for-password-auth", package_refresh_source())
    run_test(compiler, "manager-superkey-bound-to-package", superkey_auth_source())
    run_test(compiler, "manager-observer-work-and-teardown", workqueue_source())
    run_test(compiler, "manager-single-certificate-block", certificate_block_source())
