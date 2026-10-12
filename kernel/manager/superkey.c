/* SPDX-License-Identifier: GPL-2.0 */
#include "manager/superkey.h"
#include "klog.h"
#include "manager/manager_identity.h"
#include "manager/throne_tracker.h"
#include <crypto/hash.h>
#include <linux/err.h>
#include <linux/jiffies.h>
#include <linux/module.h>
#include <linux/random.h>
#include <linux/slab.h>
#include <linux/string.h>

#define SUPERKEY_AUTH_SLOTS 16
#define SUPERKEY_AUTH_ATTEMPTS 5
#define SUPERKEY_AUTH_WINDOW (30 * HZ)
#define SUPERKEY_MAGIC 0x5355504552ULL // "SUPER"
#define SUPERKEY_UNPATCHED (~0ULL)

struct superkey_data {
	volatile u64 magic;
	volatile u8 salt[SUPERKEY_SALT_LEN]; // +8: random salt injected by ksud
					     // LKM patcher
	volatile u64 hash; // +24: first 8 bytes of SHA-256(salt || key)
	volatile u64 flags; // +32: mode 0/1/2; all ones means unpatched
} __attribute__((packed, aligned(8)));

static volatile struct superkey_data
    __attribute__((used, section(".data"))) superkey_store = {
	.magic = SUPERKEY_MAGIC,
	.salt = {0},
	.hash = 0,
	.flags = SUPERKEY_UNPATCHED,
};

u64 ksu_superkey_hash __read_mostly = 0;
u8 ksu_superkey_salt[SUPERKEY_SALT_LEN] __read_mostly = {0};
bool ksu_signature_bypass __read_mostly = false;

/*
 * SHA-256(salt || key)[0..8] as u64. Computing inline rather than caching a
 * derived key keeps the salt confined to .data; a memory dump still has to
 * brute-force the original key (no pre-hashed material exposed).
 */
u64 hash_superkey(const char *key)
{
	struct crypto_shash *tfm;
	SHASH_DESC_ON_STACK(desc, NULL);
	u8 digest[32] = {0};
	u64 out = 0;
	int ret;

	if (!key)
		return 0;

	tfm = crypto_alloc_shash("sha256", 0, 0);
	if (IS_ERR(tfm)) {
		pr_err("superkey: sha256 alloc failed: %ld\n", PTR_ERR(tfm));
		return 0;
	}

	desc->tfm = tfm;
	ret = crypto_shash_init(desc);
	if (!ret)
		ret = crypto_shash_update(desc, ksu_superkey_salt,
					  SUPERKEY_SALT_LEN);
	if (!ret)
		ret = crypto_shash_finup(desc, key, strlen(key), digest);

	crypto_free_shash(tfm);

	if (ret) {
		pr_err("superkey: sha256 op failed: %d\n", ret);
		return 0;
	}

	/* Little-endian load of first 8 bytes; matches ksud-side patcher. */
	out = ((u64)digest[0]) | ((u64)digest[1] << 8) |
	      ((u64)digest[2] << 16) | ((u64)digest[3] << 24) |
	      ((u64)digest[4] << 32) | ((u64)digest[5] << 40) |
	      ((u64)digest[6] << 48) | ((u64)digest[7] << 56);
	return out;
}

/*
 * SuperKey verification mode (injected from userspace LKM patch).
 *
 * 0: signature-only (no superkey configured)
 * 1: signature authentication, or SuperKey with a trusted signature
 * 2: superkey-only (signature bypass)
 */
static u64 ksu_superkey_verification_mode __read_mostly = 0;

static uid_t authenticated_manager_uid = -1;
static DEFINE_SPINLOCK(superkey_lock);
struct superkey_auth_limit {
	uid_t uid;
	unsigned long expires;
	unsigned int attempts;
};
static struct superkey_auth_limit superkey_auth_limits[SUPERKEY_AUTH_SLOTS];

#ifdef KSU_SUPERKEY
#define COMPILE_TIME_SUPERKEY KSU_SUPERKEY
#else
#define COMPILE_TIME_SUPERKEY NULL
#endif // #ifdef KSU_SUPERKEY

void superkey_init(void)
{
	const char *compile_key = COMPILE_TIME_SUPERKEY;

	if (superkey_store.magic == SUPERKEY_MAGIC &&
	    superkey_store.flags != SUPERKEY_UNPATCHED) {
		/*
		 * flags encoding (must match userspace):
		 * 0: signature-only
		 * 1: signature, or SuperKey with a trusted signature
		 * 2: superkey-only (signature bypass)
		 */
		ksu_superkey_verification_mode = superkey_store.flags;

		if (superkey_store.hash != 0 &&
		    (ksu_superkey_verification_mode == 1 ||
		     ksu_superkey_verification_mode == 2)) {
			/* SuperKey is configured (modes 1 or 2). */
			ksu_superkey_hash = superkey_store.hash;
			memcpy(ksu_superkey_salt,
			       (const void *)superkey_store.salt,
			       SUPERKEY_SALT_LEN);
			ksu_signature_bypass =
			    (ksu_superkey_verification_mode == 2);
			pr_info("superkey: loaded from LKM patch: "
				"mode=%llu, bypass=%d\n",
				ksu_superkey_verification_mode,
				ksu_signature_bypass ? 1 : 0);
			return;
		}

		/*
		 * Mode 0 or hash==0: treated as pure signature mode, no
		 * runtime superkey hash. Leave ksu_superkey_hash=0 so
		 * superkey_is_set() stays false.
		 */
		pr_info("superkey: configured for signature-only mode (no "
			"SuperKey hash)\n");
		return;
	}

	if (compile_key && compile_key[0]) {
		if (strlen(compile_key) > SUPERKEY_MAX_LEN) {
			pr_err("superkey: compile-time key exceeds maximum "
			       "length\n");
			return;
		}
		get_random_bytes(ksu_superkey_salt, SUPERKEY_SALT_LEN);
		ksu_superkey_hash = hash_superkey(compile_key);
		ksu_superkey_verification_mode = 1;
		pr_info("superkey: using compile-time key with signature "
			"verification\n");
	}
}

/* Reserve an attempt before hashing. Full tables fail closed until a slot
 * expires; callers cannot evict another identity's active limit. */
static bool superkey_auth_allowed(uid_t uid)
{
	struct superkey_auth_limit *slot = NULL;
	unsigned long now = jiffies;
	bool allowed = false;
	int i;

	spin_lock(&superkey_lock);
	for (i = 0; i < SUPERKEY_AUTH_SLOTS; i++) {
		struct superkey_auth_limit *entry = &superkey_auth_limits[i];

		if (entry->attempts && entry->uid == uid) {
			slot = entry;
			break;
		}
		if (!slot &&
		    (!entry->attempts || time_after_eq(now, entry->expires)))
			slot = entry;
	}
	if (slot) {
		if (!slot->attempts || slot->uid != uid ||
		    time_after_eq(now, slot->expires)) {
			slot->uid = uid;
			slot->attempts = 0;
			slot->expires = now + SUPERKEY_AUTH_WINDOW;
		}
		if (slot->attempts < SUPERKEY_AUTH_ATTEMPTS) {
			slot->attempts++;
			allowed = true;
		}
	}
	spin_unlock(&superkey_lock);
	return allowed;
}

int superkey_authenticate(const char *key)
{
	uid_t uid = current_uid().val;

	/* All transports share this gate; foreign packages cannot consume the
	 * manager's quota or cause package scans and password hashing. */
	if (!superkey_is_set() || !ksu_is_manager_package_uid(uid))
		return -EACCES;
	if (!superkey_auth_allowed(uid))
		return -EAGAIN;
	if (!verify_superkey(key))
		return -EACCES;
	return superkey_on_auth_success(uid) ? 0 : -EACCES;
}

bool superkey_is_manager(void)
{
	uid_t uid;

	if (!superkey_is_set())
		return false;

	uid = superkey_get_manager_uid();
	return uid != KSU_INVALID_UID &&
	       uid % PER_USER_RANGE == current_uid().val % PER_USER_RANGE;
}

void superkey_invalidate(void)
{
	spin_lock(&superkey_lock);
	authenticated_manager_uid = -1;
	spin_unlock(&superkey_lock);
}

uid_t superkey_get_manager_uid(void)
{
	uid_t uid;

	spin_lock(&superkey_lock);
	uid = authenticated_manager_uid;
	spin_unlock(&superkey_lock);

	/* A package removal/UID change must also revoke password-only access.
	 */
	return uid != KSU_INVALID_UID && ksu_is_manager_package_uid(uid)
		   ? uid
		   : KSU_INVALID_UID;
}

bool superkey_on_auth_success(uid_t uid)
{
	int i;

	/* A valid password never authorizes a different installed package. */
	if (!ksu_refresh_manager_package(uid, !ksu_signature_bypass))
		return false;
	spin_lock(&superkey_lock);
	authenticated_manager_uid = uid;
	for (i = 0; i < SUPERKEY_AUTH_SLOTS; i++) {
		if (superkey_auth_limits[i].uid == uid)
			superkey_auth_limits[i].attempts = 0;
	}
	spin_unlock(&superkey_lock);
	return true;
}
