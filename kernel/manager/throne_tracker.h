#ifndef __KSU_H_THRONE_TRACKER
#define __KSU_H_THRONE_TRACKER

#include <linux/types.h>

#ifdef CONFIG_KSU_DISABLE_MANAGER
static inline void ksu_throne_tracker_init(void)
{
}

static inline void ksu_throne_tracker_exit(void)
{
}

static inline void track_throne(bool prune_only)
{
	(void)prune_only;
}

static inline bool ksu_is_manager_package_uid(uid_t uid)
{
	return false;
}

static inline bool ksu_refresh_manager_package(uid_t uid)
{
	return false;
}

static inline void ksu_schedule_manager_scan(void)
{
}
#else
void ksu_throne_tracker_init(void);

void ksu_throne_tracker_exit(void);

void track_throne(bool prune_only);
bool ksu_is_manager_package_uid(uid_t uid);
bool ksu_refresh_manager_package(uid_t uid);
void ksu_schedule_manager_scan(void);
#endif // #ifdef CONFIG_KSU_DISABLE_MANAGER

#endif // #ifndef __KSU_H_THRONE_TRACKER
