#include <asm/barrier.h>
#include <linux/init.h>
#include <linux/moduleparam.h>
#include <linux/slab.h>
#include <linux/smp.h>
#include <linux/string.h>

#include "feature/module_blacklist.h"
#include "uapi/boot_config.h"
#include "infra/symbol_resolver.h"
#include "klog.h" // IWYU pragma: keep

// Block kernel modules from loading by reusing the kernel's native
// `module_blacklist` (consulted by blacklisted() before a module is laid out),
// instead of hooking init_module/finit_module. It is the standard mechanism and
// needs nothing maintained on our side.
//
// Ramdisk installs pass `block_modules=name1,name2,...` via ksu_config. Direct
// LKM installs parse the same list from ZySU's embedded ImgPatch config.
// Names use the kernel form (underscores, e.g. `oplus_secure_guard`).

static char block_modules[KSU_BLOCK_MODULES_SIZE];

// Use a bounded buffer shared by both boot paths. Ignore invalid lists rather
// than aborting the module load or accidentally blocking a truncated name.
void ksu_module_blacklist_parse(const char *val)
{
	const char *p;
	size_t len = 0;

	if (!val)
		return;
	if (strnlen(val, sizeof(block_modules)) >= sizeof(block_modules)) {
		pr_err("module_blacklist: list too long, ignoring it\n");
		return;
	}
	for (p = val; *p; p++) {
		if (*p == ',') {
			if (!len)
				goto invalid;
			len = 0;
		} else if ((*p >= 'a' && *p <= 'z') ||
			   (*p >= 'A' && *p <= 'Z') ||
			   (*p >= '0' && *p <= '9') || *p == '_') {
			if (++len > KSU_BLOCK_MODULE_NAME_MAX)
				goto invalid;
		} else {
			goto invalid;
		}
	}
	if (*val && !len)
		goto invalid;
	strscpy(block_modules, val, sizeof(block_modules));
	return;
invalid:
	pr_err("module_blacklist: invalid module list, ignoring it\n");
}

static int block_modules_param_set(const char *val,
				   const struct kernel_param *kp)
{
	ksu_module_blacklist_parse(val);
	return 0;
}

static const struct kernel_param_ops block_modules_param_ops = {
    // A bare `block_modules` would otherwise fail parse_args() and the load
    .flags = KERNEL_PARAM_OPS_FL_NOARG,
    .set = block_modules_param_set,
};
module_param_cb(block_modules, &block_modules_param_ops, NULL, 0);

// Merge the configured names into the kernel's native module_blacklist.
void __init ksu_module_blacklist_init(void)
{
	char **native_blacklist;
	const char *existing;
	char *merged;
	size_t existing_len;

	if (!block_modules[0])
		return;

	native_blacklist =
	    (char **)find_kernel_symbol_exact("module_blacklist");
	if (!native_blacklist) {
		pr_warn("module_blacklist: symbol not found, cannot block "
			"modules\n");
		return;
	}

	existing = *native_blacklist;
	existing_len = existing ? strlen(existing) : 0;

	// Merge with any existing blacklist (e.g. one set on the kernel
	// cmdline).
	merged = kasprintf(GFP_KERNEL, "%s%s%s", existing_len ? existing : "",
			   existing_len ? "," : "", block_modules);
	if (!merged) {
		pr_err("module_blacklist: cannot allocate blacklist\n");
		return;
	}

	// Release-publish: pairs with the address dependency in the lockless
	// blacklisted() reader so it can never observe the new pointer before
	// the string contents are visible.
	smp_store_release(native_blacklist, merged);
	// Force every other CPU through a memory barrier so any module load
	// already in flight observes the new list right away (suggested by
	// @backslashxx).
	kick_all_cpus_sync();
	pr_info("module_blacklist: blocking modules: %s\n", merged);
}

void ksu_module_blacklist_exit(void)
{
	// Intentionally does NOT restore the original module_blacklist pointer.
	// The module loader reads module_blacklist in blacklisted() without any
	// lock or READ_ONCE, testing it for NULL and then dereferencing it;
	// swapping the pointer back (especially to the common NULL) could race
	// that reader into a NULL dereference. The merged string already
	// contains any pre-existing entries and lives on the kernel heap, so
	// leaving it installed stays valid even after KernelSU is unloaded. The
	// one-time, bounded allocation is deliberately leaked.
}
