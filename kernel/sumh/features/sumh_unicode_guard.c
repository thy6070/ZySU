#include <linux/dcache.h>
#include <linux/errno.h>
#include <linux/fs.h>
#include <linux/kernel.h>
#include <linux/kprobes.h>
#include <linux/ptrace.h>
#include <linux/string.h>
#include <linux/version.h>

#include "infra/symbol_resolver.h"
#include "policy/feature.h"
#include "sumh_dirhijack.h"
#include "sumh_unicode_guard.h"
#include "sumh_unicode_guard_match.h"

/*
 * CVE-2025-48567 compensating control, not a replacement MediaProvider patch.
 * Reject ambiguous components before casefold lookup and FUSE lookup/open.
 * The existing FUSE revalidation probe covers cached and READDIRPLUS dentries.
 * Each hook observes the actual VFS component, including relative/dirfd paths
 * and symlink expansion. Already-open descriptors cannot be revoked here.
 *
 * The feature mutex serializes configuration and handler removal. Probe
 * callbacks access only immutable names and the published enabled bit, never
 * configuration locks, allocations, module-view policy or UID exemptions.
 */
static bool sumh_unicode_enabled;
static bool sumh_unicode_capable;
static bool sumh_unicode_failed;
static bool sumh_unicode_feature_registered;
static bool sumh_unicode_status_registered;

bool sumh_unicode_guard_reject_component(const struct qstr *name)
{
	if (!READ_ONCE(sumh_unicode_enabled) || !name)
		return false;
	return sumh_unicode_sensitive_alias(name->name, name->len);
}

#if defined(CONFIG_ARM64) && IS_ENABLED(CONFIG_UNICODE) &&                     \
    IS_ENABLED(CONFIG_KPROBES) && IS_BUILTIN(CONFIG_FUSE_FS) &&                \
    ((LINUX_VERSION_CODE >= KERNEL_VERSION(6, 1, 0) &&                         \
      LINUX_VERSION_CODE < KERNEL_VERSION(6, 2, 0)) ||                         \
     (LINUX_VERSION_CODE >= KERNEL_VERSION(6, 6, 0) &&                         \
      LINUX_VERSION_CODE < KERNEL_VERSION(6, 7, 0)) ||                         \
     (LINUX_VERSION_CODE >= KERNEL_VERSION(6, 12, 0) &&                        \
      LINUX_VERSION_CODE < KERNEL_VERSION(6, 13, 0)) ||                        \
     (LINUX_VERSION_CODE >= KERNEL_VERSION(6, 18, 0) &&                        \
      LINUX_VERSION_CODE < KERNEL_VERSION(6, 19, 0)))
#define SUMH_UNICODE_HOOKS_SUPPORTED 1

/* Verified GKI callback ABIs only; unknown families must not report active.
 * FUSE must be built in: kprobes do not retain the target module, and module
 * unload marks a probe GONE without updating this feature's enabled state.
 */
static struct kprobe sumh_unicode_probes[3];
static unsigned long sumh_unicode_addresses[3];
static unsigned int sumh_unicode_probe_count;
static const char *const sumh_unicode_symbols[] = {
    "generic_ci_d_hash",
    "fuse_lookup",
    "fuse_atomic_open",
};

static int sumh_unicode_guard_pre(struct kprobe *probe, struct pt_regs *regs)
{
	struct dentry *entry;
	bool reject;

	if (!READ_ONCE(sumh_unicode_enabled))
		return 0;
	if (probe == &sumh_unicode_probes[0]) {
		/* generic_ci_d_hash(parent, qstr): called before cache lookup.
		 */
		const struct dentry *parent = (void *)regs->regs[0];
		const struct qstr *name = (void *)regs->regs[1];
		const struct inode *dir;

		if (!parent || !parent->d_sb || !parent->d_sb->s_encoding)
			return 0;
		dir = READ_ONCE(parent->d_inode);
		if (!dir || !IS_CASEFOLDED(dir))
			return 0;
		reject = sumh_unicode_guard_reject_component(name);
	} else {
		/* fuse_lookup/atomic_open(dir, entry, ...), stable across 6.1+.
		 */
		entry = (void *)regs->regs[1];
		if (!entry)
			return 0;
		spin_lock(&entry->d_lock);
		reject = sumh_unicode_guard_reject_component(&entry->d_name);
		spin_unlock(&entry->d_lock);
	}
	if (!reject)
		return 0;

	/* ERR_PTR(-EACCES) and int -EACCES share the ARM64 return register.
	 * Return from d_hash itself: utf8_casefold_hash errors can be swallowed
	 * by d_hash on non-strict-encoding filesystems.
	 */
	regs->regs[0] = (unsigned long)-EACCES;
	instruction_pointer_set(regs, regs->regs[30]);
	return 1;
}

static bool sumh_unicode_resolve_hooks(void)
{
	unsigned int i;

	if (!sumh_dirhijack_fuse_guard_available())
		return false;
	for (i = 0; i < ARRAY_SIZE(sumh_unicode_symbols); i++) {
		unsigned long addr =
		    find_kernel_symbol_exact(sumh_unicode_symbols[i]);

		if (!addr)
			return false;
		sumh_unicode_addresses[i] = addr;
	}
	return true;
}

static void sumh_unicode_remove_hooks(void)
{
	/* Stop decisions before unregister_kprobe drains in-flight callbacks.
	 */
	WRITE_ONCE(sumh_unicode_enabled, false);
	while (sumh_unicode_probe_count)
		unregister_kprobe(
		    &sumh_unicode_probes[--sumh_unicode_probe_count]);
}

static int sumh_unicode_install_hooks(void)
{
	unsigned int i;
	int ret;

	if (!sumh_dirhijack_fuse_guard_available())
		return -EOPNOTSUPP;
	for (i = 0; i < ARRAY_SIZE(sumh_unicode_probes); i++) {
		/* unregister_kprobe retains KPROBE_FLAG_DISABLED. A clean
		 * descriptor is necessary for a real off -> on transition and
		 * for retry after partial registration was rolled back.
		 */
		memset(&sumh_unicode_probes[i], 0,
		       sizeof(sumh_unicode_probes[i]));
		sumh_unicode_probes[i].addr =
		    (kprobe_opcode_t *)sumh_unicode_addresses[i];
		sumh_unicode_probes[i].pre_handler = sumh_unicode_guard_pre;
		/* Registration can reject a blacklisted site or an incompatible
		 * existing probe. Never leave a partially enabled guard behind.
		 */
		ret = register_kprobe(&sumh_unicode_probes[i]);
		if (ret) {
			sumh_unicode_remove_hooks();
			pr_warn("sumh: Unicode guard hook %s failed: %d\n",
				sumh_unicode_symbols[i], ret);
			return ret;
		}
		sumh_unicode_probe_count++;
	}
	WRITE_ONCE(sumh_unicode_enabled, true);
	return 0;
}
#else
static bool sumh_unicode_resolve_hooks(void)
{
	return false;
}

static void sumh_unicode_remove_hooks(void)
{
	WRITE_ONCE(sumh_unicode_enabled, false);
}

static int sumh_unicode_install_hooks(void)
{
	return -EOPNOTSUPP;
}
#endif

static int sumh_unicode_feature_get(u64 *value)
{
	*value = READ_ONCE(sumh_unicode_enabled) ? 1 : 0;
	return 0;
}

static int sumh_unicode_feature_set(u64 value)
{
	int ret;

	if (value > 1)
		return -EINVAL;
	if (!value) {
		sumh_unicode_remove_hooks();
		return 0;
	}
	if (READ_ONCE(sumh_unicode_enabled))
		return 0;
	if (!sumh_unicode_capable)
		return -EOPNOTSUPP;
	ret = sumh_unicode_install_hooks();
	sumh_unicode_failed = ret != 0;
	return ret;
}

static int sumh_unicode_status_get(u64 *value)
{
	if (!sumh_unicode_capable || sumh_unicode_failed)
		*value = KSU_UNICODE_PATH_GUARD_UNSUPPORTED;
	else
		*value = READ_ONCE(sumh_unicode_enabled)
			     ? KSU_UNICODE_PATH_GUARD_ACTIVE
			     : KSU_UNICODE_PATH_GUARD_DISABLED;
	return 0;
}

static const struct ksu_feature_handler sumh_unicode_feature = {
    .feature_id = KSU_FEATURE_UNICODE_PATH_GUARD,
    .name = "unicode_path_guard",
    .get_handler = sumh_unicode_feature_get,
    .set_handler = sumh_unicode_feature_set,
};

static const struct ksu_feature_handler sumh_unicode_status = {
    .feature_id = KSU_FEATURE_UNICODE_PATH_GUARD_STATUS,
    .name = "unicode_path_guard_status",
    .get_handler = sumh_unicode_status_get,
};

void sumh_unicode_guard_init(void)
{
	int ret;

	sumh_unicode_capable = sumh_unicode_resolve_hooks();
	ret = ksu_register_feature_handler(&sumh_unicode_status);
	if (ret)
		return;
	sumh_unicode_status_registered = true;
	ret = ksu_register_feature_handler(&sumh_unicode_feature);
	if (ret) {
		ksu_unregister_feature_handler(
		    KSU_FEATURE_UNICODE_PATH_GUARD_STATUS);
		sumh_unicode_status_registered = false;
		return;
	}
	sumh_unicode_feature_registered = true;
	pr_info("sumh: Unicode path guard %s; disabled by default\n",
		sumh_unicode_capable ? "available" : "unsupported");
}

void sumh_unicode_guard_exit(void)
{
	/* Withdraw controls before draining probes, before dirhijack teardown.
	 * Handler removal takes feature_mutex and waits for an ongoing SET.
	 */
	if (sumh_unicode_feature_registered) {
		ksu_unregister_feature_handler(KSU_FEATURE_UNICODE_PATH_GUARD);
		sumh_unicode_feature_registered = false;
	}
	if (sumh_unicode_status_registered) {
		ksu_unregister_feature_handler(
		    KSU_FEATURE_UNICODE_PATH_GUARD_STATUS);
		sumh_unicode_status_registered = false;
	}
	sumh_unicode_remove_hooks();
	sumh_unicode_capable = false;
}
