#ifndef __KSU_H_MANAGER_IDENTITY
#define __KSU_H_MANAGER_IDENTITY

#include <linux/cred.h>
#include <linux/types.h>

#ifndef PER_USER_RANGE
#define PER_USER_RANGE 100000
#endif // #ifndef PER_USER_RANGE

#define KSU_INVALID_UID ((uid_t) - 1)

#ifdef CONFIG_KSU_DISABLE_MANAGER
static inline bool ksu_is_manager_uid_valid(void)
{
	return true;
}

static inline bool ksu_is_manager_appid_valid(void)
{
	return true;
}

static inline bool is_manager(void)
{
	return current_uid().val == 0;
}

static inline uid_t ksu_get_manager_appid(void)
{
	return 0;
}

static inline uid_t ksu_get_manager_uid(void)
{
	return 0;
}

static inline bool ksu_is_uid_manager(uid_t uid)
{
	return uid == 0;
}

static inline void ksu_set_manager_uid(uid_t uid)
{
	(void)uid;
}

static inline void ksu_set_manager_appid(uid_t appid)
{
	(void)appid;
}

static inline void ksu_invalidate_manager_uid(void)
{
}

static inline void ksu_invalidate_manager_appid(void)
{
}
#else

extern uid_t ksu_manager_uid;
extern uid_t ksu_manager_appid;

#ifdef CONFIG_KSU_SUPERKEY
#include "manager/superkey.h"
#endif // #ifdef CONFIG_KSU_SUPERKEY

static inline bool ksu_is_manager_uid_valid(void)
{
#ifdef CONFIG_KSU_SUPERKEY
	return superkey_get_manager_uid() != (uid_t)-1 ||
	       ksu_manager_uid != KSU_INVALID_UID;
#else
	return ksu_manager_uid != KSU_INVALID_UID;
#endif // #ifdef CONFIG_KSU_SUPERKEY
}

static inline bool ksu_is_manager_appid_valid(void)
{
#ifdef CONFIG_KSU_SUPERKEY
	return superkey_get_manager_uid() != KSU_INVALID_UID ||
	       ksu_manager_appid != KSU_INVALID_UID;
#else
	return ksu_manager_appid != KSU_INVALID_UID;
#endif // #ifdef CONFIG_KSU_SUPERKEY
}

static inline bool ksu_is_uid_manager(uid_t uid)
{
	uid_t appid = uid % PER_USER_RANGE;

#ifdef CONFIG_KSU_SUPERKEY
	uid_t superkey_uid = superkey_get_manager_uid();
	if (superkey_uid != KSU_INVALID_UID &&
	    superkey_uid % PER_USER_RANGE == appid)
		return true;
#endif
	return ksu_manager_appid != KSU_INVALID_UID &&
	       ksu_manager_appid == appid;
}

static inline bool is_manager(void)
{
	return ksu_is_uid_manager(current_uid().val);
}

static inline uid_t ksu_get_manager_appid(void)
{
#ifdef CONFIG_KSU_SUPERKEY
	uid_t superkey_uid = superkey_get_manager_uid();
	if (superkey_uid != (uid_t)-1)
		return superkey_uid % PER_USER_RANGE;
#endif // #ifdef CONFIG_KSU_SUPERKEY
	if (is_manager())
		return current_uid().val % PER_USER_RANGE;
	return ksu_manager_appid;
}

static inline uid_t ksu_get_manager_uid(void)
{
#ifdef CONFIG_KSU_SUPERKEY
	uid_t superkey_uid = superkey_get_manager_uid();
	if (superkey_uid != (uid_t)-1)
		return superkey_uid;
#endif // #ifdef CONFIG_KSU_SUPERKEY
	if (is_manager())
		return current_uid().val;
	return ksu_manager_uid;
}

static inline void ksu_set_manager_uid(uid_t uid)
{
	ksu_manager_uid = uid;
}

static inline void ksu_set_manager_appid(uid_t appid)
{
	ksu_manager_appid = appid;
	ksu_manager_uid =
	    current_uid().val / PER_USER_RANGE * PER_USER_RANGE + appid;
}

static inline void ksu_invalidate_manager_uid(void)
{
	ksu_manager_uid = KSU_INVALID_UID;
#ifdef CONFIG_KSU_SUPERKEY
	superkey_invalidate();
#endif // #ifdef CONFIG_KSU_SUPERKEY
}

static inline void ksu_invalidate_manager_appid(void)
{
	ksu_manager_appid = KSU_INVALID_UID;
	ksu_invalidate_manager_uid();
}

#endif // #ifdef CONFIG_KSU_DISABLE_MANAGER

#endif // #ifndef __KSU_H_MANAGER_IDENTITY
