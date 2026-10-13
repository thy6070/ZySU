#include "policy/feature.h"
#include "klog.h" // IWYU pragma: keep

#include <linux/mutex.h>
#include <linux/bitmap.h>
#include <linux/kernel.h>
#include <linux/moduleparam.h>
#include <linux/string.h>
#include "uapi/boot_config.h"

static const struct ksu_feature_handler *feature_handlers[KSU_FEATURE_MAX];

static DEFINE_MUTEX(feature_mutex);

// Only runtime-configurable features can be forced. In particular, never lock
// SUMH's idempotent rebind or override ZySU's fixed security policy.
static const char *const feature_names[KSU_FEATURE_MAX] = {
#define KSU_FORCE_NAME(id, name) [id] = name,
    KSU_BOOT_CONFIGURABLE_FEATURES(KSU_FORCE_NAME)
#undef KSU_FORCE_NAME
};

// Features forced by boot config. Written only while parsing module params
// or the embedded ImgPatch block, before feature initialization. Read-only
// afterwards.
static u64 forced_values[KSU_FEATURE_MAX];
static DECLARE_BITMAP(forced_mask, KSU_FEATURE_MAX);
// Forced features whose value has not been applied successfully yet
static DECLARE_BITMAP(forced_pending, KSU_FEATURE_MAX);

static int parse_feature_id(const char *name, u32 *id)
{
	u32 i;

	if (!kstrtou32(name, 10, id)) {
		return *id < KSU_FEATURE_MAX && feature_names[*id] ? 0
								   : -EINVAL;
	}

	for (i = 0; i < KSU_FEATURE_MAX; i++) {
		if (feature_names[i] && !strcmp(name, feature_names[i])) {
			*id = i;
			return 0;
		}
	}

	return -EINVAL;
}

// force_feature=<name|id>:<value>[,<name|id>:<value>...]
// May be specified multiple times. Invalid entries are skipped instead of
// failing, as a param error would prevent KernelSU from loading at all.
// Both module parameters and the embedded ImgPatch block use this parser.
void ksu_feature_parse_forced(const char *val)
{
	char name[32], num[24];
	const char *p = val, *end, *sep;
	size_t name_len, num_len;
	u32 id;
	u64 value;

	if (!val) {
		return;
	}

	for (; *p; p = *end ? end + 1 : end) {
		end = strchrnul(p, ',');
		if (end == p) {
			continue;
		}

		sep = memchr(p, ':', end - p);
		name_len = sep ? sep - p : 0;
		num_len = sep ? end - sep - 1 : 0;
		if (!name_len || name_len >= sizeof(name) || !num_len ||
		    num_len >= sizeof(num)) {
			pr_err("feature: invalid force_feature entry '%.*s'\n",
			       (int)(end - p), p);
			continue;
		}

		memcpy(name, p, name_len);
		name[name_len] = '\0';
		memcpy(num, sep + 1, num_len);
		num[num_len] = '\0';

		if (parse_feature_id(name, &id) || kstrtou64(num, 0, &value)) {
			pr_err("feature: invalid force_feature entry '%s:%s'\n",
			       name, num);
			continue;
		}

		forced_values[id] = value;
		set_bit(id, forced_mask);
		set_bit(id, forced_pending);
		pr_info("feature: %s (id=%u) forced to %llu\n",
			feature_names[id], id, value);
	}
}

static int force_feature_param_set(const char *val,
				   const struct kernel_param *kp)
{
	ksu_feature_parse_forced(val);
	return 0;
}

static const struct kernel_param_ops force_feature_param_ops = {
    // A bare `force_feature` would otherwise fail parse_args() and the whole
    // module load
    .flags = KERNEL_PARAM_OPS_FL_NOARG,
    .set = force_feature_param_set,
};
module_param_cb(force_feature, &force_feature_param_ops, NULL, 0);

int ksu_register_feature_handler(const struct ksu_feature_handler *handler)
{
	if (!handler) {
		pr_err("feature: register handler is NULL\n");
		return -EINVAL;
	}

	if (handler->feature_id >= KSU_FEATURE_MAX) {
		pr_err("feature: invalid feature_id %u\n", handler->feature_id);
		return -EINVAL;
	}

	if (!handler->get_handler && !handler->set_handler) {
		pr_err("feature: no handler provided for feature %u\n",
		       handler->feature_id);
		return -EINVAL;
	}

	mutex_lock(&feature_mutex);

	if (feature_handlers[handler->feature_id]) {
		pr_warn(
		    "feature: handler for %u already registered, overwriting\n",
		    handler->feature_id);
	}

	feature_handlers[handler->feature_id] = handler;

	pr_info("feature: registered handler for %s (id=%u)\n",
		handler->name ? handler->name : "unknown", handler->feature_id);

	mutex_unlock(&feature_mutex);
	return 0;
}

int ksu_unregister_feature_handler(u32 feature_id)
{
	int ret = 0;

	if (feature_id >= KSU_FEATURE_MAX) {
		pr_err("feature: invalid feature_id %u\n", feature_id);
		return -EINVAL;
	}

	mutex_lock(&feature_mutex);

	if (!feature_handlers[feature_id]) {
		pr_warn("feature: no handler registered for %u\n", feature_id);
		ret = -ENOENT;
		goto out;
	}

	feature_handlers[feature_id] = NULL;

	pr_info("feature: unregistered handler for id=%u\n", feature_id);

out:
	mutex_unlock(&feature_mutex);
	return ret;
}

int ksu_get_feature(u32 feature_id, u64 *value, bool *supported)
{
	int ret = 0;
	const struct ksu_feature_handler *handler;

	if (feature_id >= KSU_FEATURE_MAX) {
		pr_err("feature: invalid feature_id %u\n", feature_id);
		return -EINVAL;
	}

	if (!value || !supported) {
		pr_err("feature: invalid parameters\n");
		return -EINVAL;
	}

	mutex_lock(&feature_mutex);

	handler = feature_handlers[feature_id];

	if (!handler) {
		*supported = false;
		*value = 0;
		pr_debug("feature: feature %u not supported\n", feature_id);
		goto out;
	}

	*supported = true;

	if (!handler->get_handler) {
		pr_warn("feature: no get_handler for feature %u\n", feature_id);
		ret = -EOPNOTSUPP;
		goto out;
	}

	ret = handler->get_handler(value);
	if (ret) {
		pr_err("feature: get_handler for %u failed: %d\n", feature_id,
		       ret);
	}

out:
	mutex_unlock(&feature_mutex);
	return ret;
}

int ksu_set_feature(u32 feature_id, u64 value)
{
	int ret = 0;
	const struct ksu_feature_handler *handler;

	if (feature_id >= KSU_FEATURE_MAX) {
		pr_err("feature: invalid feature_id %u\n", feature_id);
		return -EINVAL;
	}

	if (ksu_feature_is_forced(feature_id))
		return -EPERM;

	mutex_lock(&feature_mutex);

	handler = feature_handlers[feature_id];

	if (!handler) {
		pr_err("feature: feature %u not registered\n", feature_id);
		ret = -EOPNOTSUPP;
		goto out;
	}

	if (!handler->set_handler) {
		pr_warn("feature: no set_handler for feature %u\n", feature_id);
		ret = -EOPNOTSUPP;
		goto out;
	}

	ret = handler->set_handler(value);
	if (ret) {
		pr_err("feature: set_handler for %u failed: %d\n", feature_id,
		       ret);
	}

out:
	mutex_unlock(&feature_mutex);
	return ret;
}

bool ksu_feature_is_forced(u32 feature_id)
{
	return feature_id < KSU_FEATURE_MAX &&
	       test_bit(feature_id, forced_mask);
}

void ksu_feature_apply_forced(void)
{
	const struct ksu_feature_handler *handler;
	unsigned int id;
	int ret;

	mutex_lock(&feature_mutex);

	for_each_set_bit (id, forced_pending, KSU_FEATURE_MAX) {
		handler = feature_handlers[id];
		if (!handler || !handler->set_handler) {
			continue;
		}

		ret = handler->set_handler(forced_values[id]);
		// ZySU's SELinux toggle records the requested state even when
		// runtime activation must wait. Keep it pending until the
		// status is active.
		if (!ret && id == KSU_FEATURE_SELINUX_HIDE &&
		    forced_values[id]) {
			const struct ksu_feature_handler *status =
			    feature_handlers[KSU_FEATURE_SELINUX_HIDE_STATUS];
			u64 state = KSU_SELINUX_HIDE_PENDING_REBOOT;

			if (!status || !status->get_handler ||
			    status->get_handler(&state) ||
			    state != KSU_SELINUX_HIDE_ACTIVE)
				ret = -EAGAIN;
		}
		if (ret) {
			pr_warn("feature: apply forced %s=%llu failed: %d\n",
				feature_names[id], forced_values[id], ret);
			continue;
		}

		clear_bit(id, forced_pending);
		pr_info("feature: applied forced %s=%llu\n", feature_names[id],
			forced_values[id]);
	}

	mutex_unlock(&feature_mutex);
}

void ksu_feature_init(void)
{
	int i;

	for (i = 0; i < KSU_FEATURE_MAX; i++) {
		feature_handlers[i] = NULL;
	}

	pr_info("feature: feature management initialized\n");
}

void ksu_feature_exit(void)
{
	int i;

	mutex_lock(&feature_mutex);

	for (i = 0; i < KSU_FEATURE_MAX; i++) {
		feature_handlers[i] = NULL;
	}

	mutex_unlock(&feature_mutex);

	pr_info("feature: feature management cleaned up\n");
}
