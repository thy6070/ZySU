#include <linux/err.h>
#include <linux/fs.h>
#include <linux/list.h>
#include <linux/mutex.h>
#include <linux/slab.h>
#include <linux/spinlock.h>
#include <linux/stat.h>
#include <linux/string.h>
#include <linux/types.h>
#include <linux/workqueue.h>

#include "policy/allowlist.h"
#include "klog.h" // IWYU pragma: keep
#include "manager/apk_sign.h"
#include "manager/manager_identity.h"
#include "manager/throne_tracker.h"
#include "ksu.h"
#include "util.h"
#include "hook/tp_marker.h"

uid_t ksu_manager_uid = KSU_INVALID_UID;
uid_t ksu_manager_appid = KSU_INVALID_UID;
/* Package identity is independent of signature/password authentication. */
static uid_t ksu_manager_package_appid = KSU_INVALID_UID;

#define SYSTEM_PACKAGES_LIST_PATH "/data/system/packages.list"

struct uid_data {
	struct list_head list;
	u32 appid;
	char package[KSU_MAX_PACKAGE_NAME];
};

static void crown_manager(struct list_head *uid_list)
{
	struct uid_data *entry;

	list_for_each_entry (entry, uid_list, list) {
		if (!strcmp(entry->package, KSU_MANAGER_PACKAGE)) {
			pr_info("Crowning manager: %s (appid=%u)\n",
				KSU_MANAGER_PACKAGE, entry->appid);
			ksu_set_manager_appid(entry->appid);
			/* A process may already be running during late
			 * discovery. */
			ksu_mark_running_process();
			break;
		}
	}
}

#define DATA_PATH_LEN 384 // 384 is enough for /data/app/<package>/base.apk

struct data_path {
	char dirpath[DATA_PATH_LEN];
	int depth;
	struct list_head list;
};

struct apk_scan_entry {
	char path[DATA_PATH_LEN];
	u32 appid;
	struct kstat stat;
	bool exists;
	struct list_head list;
};

/* All scan entry points and cache updates share this lock. */
static DEFINE_MUTEX(throne_scan_lock);
static LIST_HEAD(apk_scan_cache);

static void clear_apk_scan_cache(void)
{
	struct apk_scan_entry *entry, *next;

	list_for_each_entry_safe (entry, next, &apk_scan_cache, list) {
		list_del(&entry->list);
		kfree(entry);
	}
}

static struct apk_scan_entry *find_apk_scan_entry(const char *path)
{
	struct apk_scan_entry *entry;

	list_for_each_entry (entry, &apk_scan_cache, list) {
		if (!strcmp(entry->path, path))
			return entry;
	}
	return NULL;
}

static bool get_apk_appid(const char *path, struct list_head *uid_list,
			  u32 *appid)
{
	struct uid_data *entry;

	if (!is_manager_package(path))
		return false;
	list_for_each_entry (entry, uid_list, list) {
		if (!strcmp(entry->package, KSU_MANAGER_PACKAGE)) {
			*appid = entry->appid;
			return true;
		}
	}
	return false;
}

static bool stat_apk(const char *name, struct kstat *stat)
{
	struct path path;
	int ret;
	u32 required =
	    STATX_TYPE | STATX_INO | STATX_SIZE | STATX_MTIME | STATX_CTIME;

	/* Metadata lookup does not open or enumerate the package directory. */
	if (kern_path(name, 0, &path))
		return false;
	memset(stat, 0, sizeof(*stat));
	ret =
	    vfs_getattr(&path, stat, STATX_BASIC_STATS, AT_STATX_SYNC_AS_STAT);
	path_put(&path);
	return !ret && S_ISREG(stat->mode) &&
	       (stat->result_mask & required) == required;
}

static bool same_apk_stat(const struct kstat *a, const struct kstat *b)
{
	return a->dev == b->dev && a->ino == b->ino && a->size == b->size &&
	       a->mtime.tv_sec == b->mtime.tv_sec &&
	       a->mtime.tv_nsec == b->mtime.tv_nsec &&
	       a->ctime.tv_sec == b->ctime.tv_sec &&
	       a->ctime.tv_nsec == b->ctime.tv_nsec;
}

static bool cached_apk_directory(const char *directory,
				 struct list_head *uid_list)
{
	char apk[DATA_PATH_LEN];
	struct apk_scan_entry *entry;
	struct kstat stat;
	u32 appid;

	if (snprintf(apk, sizeof(apk), "%s/base.apk", directory) >= sizeof(apk))
		return false;
	entry = find_apk_scan_entry(apk);
	if (!entry || !get_apk_appid(apk, uid_list, &appid) ||
	    entry->appid != appid || !stat_apk(apk, &stat) ||
	    !same_apk_stat(&entry->stat, &stat))
		return false;

	entry->exists = true;
	return true;
}

static void scan_manager_apk(char *path, struct list_head *uid_list)
{
	struct apk_scan_entry *entry = find_apk_scan_entry(path);
	bool is_manager;
	struct kstat before, after;
	u32 appid;
	bool cacheable =
	    get_apk_appid(path, uid_list, &appid) && stat_apk(path, &before);

	if (!is_manager_package(path))
		return;
	is_manager = is_manager_apk(path);
	if (is_manager)
		crown_manager(uid_list);

	/* Revalidate trusted APKs whenever a search is needed. Never cache a
	 * file that changed while its signature was being read. */
	if (is_manager || !cacheable || !stat_apk(path, &after) ||
	    !same_apk_stat(&before, &after)) {
		if (entry) {
			list_del(&entry->list);
			kfree(entry);
		}
		return;
	}

	if (!entry) {
		entry = kzalloc(sizeof(*entry), GFP_ATOMIC);
		if (!entry)
			return;
		strscpy(entry->path, path, sizeof(entry->path));
		list_add_tail(&entry->list, &apk_scan_cache);
	}
	entry->appid = appid;
	entry->stat = after;
	entry->exists = true;
}

struct my_dir_context {
	struct dir_context ctx;
	struct list_head *data_path_list;
	char *parent_dir;
	char candidate_path[DATA_PATH_LEN];
	int depth;
};

static bool my_actor(struct dir_context *ctx, const char *name, int namelen,
		     loff_t off, u64 ino, unsigned int d_type)
{
	struct my_dir_context *my_ctx =
	    container_of(ctx, struct my_dir_context, ctx);
	char dirpath[DATA_PATH_LEN];

	if (!strncmp(name, "..", namelen) || !strncmp(name, ".", namelen))
		return true; // Skip "." and ".."

	if (d_type == DT_DIR && namelen >= 8 && !strncmp(name, "vmdl", 4) &&
	    !strncmp(name + namelen - 4, ".tmp", 4)) {
		pr_info("Skipping directory: %.*s\n", namelen, name);
		return true; // Skip staging package
	}

	if (snprintf(dirpath, DATA_PATH_LEN, "%s/%.*s", my_ctx->parent_dir,
		     namelen, name) >= DATA_PATH_LEN) {
		pr_err("Path too long: %s/%.*s\n", my_ctx->parent_dir, namelen,
		       name);
		return true;
	}

	if (d_type == DT_DIR && my_ctx->depth > 0) {
		/* Android 12+ uses ~~random containers; legacy installs put the
		 * package directly under /data/app. Never enter another
		 * package. */
		if (!is_manager_package_dir(dirpath) &&
		    !(my_ctx->depth == 2 && namelen > 2 &&
		      !strncmp(name, "~~", 2)))
			return true;
		struct data_path *data =
		    kzalloc(sizeof(struct data_path), GFP_ATOMIC);

		if (!data) {
			pr_err("Failed to allocate memory for %s\n", dirpath);
			return true;
		}

		strscpy(data->dirpath, dirpath, DATA_PATH_LEN);
		data->depth = my_ctx->depth - 1;
		list_add_tail(&data->list, my_ctx->data_path_list);
	} else if (d_type == DT_REG && namelen == 8 &&
		   !strncmp(name, "base.apk", namelen)) {
		/* No APK I/O while iterate_dir holds directory locks. */
		if (is_manager_package(dirpath))
			strscpy(my_ctx->candidate_path, dirpath, DATA_PATH_LEN);
		return false;
	}

	return true;
}

static void search_manager(const char *path, int depth,
			   struct list_head *uid_data)
{
	int i;
	unsigned long data_app_magic = 0;
	struct apk_scan_entry *pos, *n;
	struct list_head data_path_list;
	struct data_path data;

	INIT_LIST_HEAD(&data_path_list);

	// Initialize APK cache list
	list_for_each_entry (pos, &apk_scan_cache, list) {
		pos->exists = false;
	}

	// First depth
	strscpy(data.dirpath, path, DATA_PATH_LEN);
	data.depth = depth;
	list_add_tail(&data.list, &data_path_list);

	for (i = depth; i >= 0; i--) {
		struct data_path *pos, *n;

		list_for_each_entry_safe (pos, n, &data_path_list, list) {
			struct my_dir_context ctx = {.ctx.actor = my_actor,
						     .data_path_list =
							 &data_path_list,
						     .parent_dir = pos->dirpath,
						     .depth = pos->depth};
			struct file *file;

			if (!cached_apk_directory(pos->dirpath, uid_data)) {
				file = ksu_filp_open_nonotify(
				    pos->dirpath, O_RDONLY | O_NOFOLLOW |
						      O_NOATIME | O_DIRECTORY);
				if (IS_ERR(file)) {
					pr_err("Failed to open directory: %s, "
					       "err: %ld\n",
					       pos->dirpath, PTR_ERR(file));
					goto skip_iterate;
				}

				// grab magic on first folder, which is
				// /data/app
				if (!data_app_magic) {
					if (file->f_inode->i_sb->s_magic) {
						data_app_magic =
						    file->f_inode->i_sb
							->s_magic;
						pr_info("%s: dir: %s got "
							"magic! 0x%lx\n",
							__func__, pos->dirpath,
							data_app_magic);
					} else {
						filp_close(file, NULL);
						goto skip_iterate;
					}
				}

				if (file->f_inode->i_sb->s_magic !=
				    data_app_magic) {
					pr_info("%s: skip: %s magic: 0x%lx "
						"expected: 0x%lx\n",
						__func__, pos->dirpath,
						file->f_inode->i_sb->s_magic,
						data_app_magic);
					filp_close(file, NULL);
					goto skip_iterate;
				}

				iterate_dir(file, &ctx.ctx);
				filp_close(file, NULL);

				/* APK lookup and signature reads may acquire
				 * VFS locks. Defer them until directory
				 * iteration ends. */
				if (ctx.candidate_path[0])
					scan_manager_apk(ctx.candidate_path,
							 uid_data);
			}
		skip_iterate:
			list_del(&pos->list);
			if (pos != &data)
				kfree(pos);
		}
	}

	// Remove stale cached APK entries
	list_for_each_entry_safe (pos, n, &apk_scan_cache, list) {
		if (!pos->exists) {
			list_del(&pos->list);
			kfree(pos);
		}
	}
}

static bool is_uid_exist(uid_t uid, char *package, void *data)
{
	struct list_head *list = (struct list_head *)data;
	struct uid_data *np;
	u32 appid = uid % 100000;
	bool exist = false;

	list_for_each_entry (np, list, list) {
		if (np->appid == appid &&
		    strncmp(np->package, package, KSU_MAX_PACKAGE_NAME) == 0) {
			exist = true;
			break;
		}
	}
	return exist;
}

static bool track_throne_locked(bool prune_only)
{
	const struct cred *old_cred = override_creds(ksu_cred);
	struct list_head uid_list;
	struct uid_data *np, *n;
	struct file *fp;
	loff_t pos = 0;
	loff_t size;
	ssize_t nr;
	char *buf = NULL;
	char *line = NULL;
	char *next = NULL;
	uid_t package_appid = KSU_INVALID_UID;
	bool updated = false;

	// init uid list head
	INIT_LIST_HEAD(&uid_list);

	fp = filp_open(SYSTEM_PACKAGES_LIST_PATH, O_RDONLY, 0);
	if (IS_ERR(fp)) {
		pr_err("%s: open " SYSTEM_PACKAGES_LIST_PATH " failed: %ld\n",
		       __func__, PTR_ERR(fp));
		goto out;
	}

	size = i_size_read(file_inode(fp));
	if (size <= 0) {
		filp_close(fp, 0);
		goto out;
	}

	buf = kzalloc(size + 1, GFP_KERNEL);
	if (!buf) {
		filp_close(fp, 0);
		goto out;
	}

	nr = kernel_read(fp, buf, size, &pos);
	filp_close(fp, 0);
	if (nr != size) {
		pr_err("track_throne: read packages.list failed: %zd\n", nr);
		goto out;
	}
	buf[nr] = '\0';

	for (line = buf; line && *line; line = next) {
		struct uid_data *data = NULL;
		const char *delim = " \t";
		char *package = NULL;
		char *tmp = NULL;
		char *uid = NULL;
		u32 res;

		next = strchr(line, '\n');
		if (next)
			*next++ = '\0';
		while (*line == ' ' || *line == '\t' || *line == '\r')
			line++;
		if (!*line)
			continue;

		tmp = line;
		package = strsep(&tmp, delim);
		while (tmp && (*tmp == ' ' || *tmp == '\t'))
			tmp++;
		uid = strsep(&tmp, delim);
		if (!uid || !package || !*package ||
		    strlen(package) >= KSU_MAX_PACKAGE_NAME) {
			pr_err("update_uid: package or uid is NULL!\n");
			goto out;
		}

		if (kstrtou32(uid, 10, &res)) {
			pr_err("track_throne: appid parse err\n");
			goto out;
		}

		data = kzalloc(sizeof(*data), GFP_KERNEL);
		if (!data) {
			pr_err("track_throne: OOM appid=%u\n", res);
			goto out;
		}
		data->appid = res;
		strscpy(data->package, package, KSU_MAX_PACKAGE_NAME);
		list_add_tail(&data->list, &uid_list);
	}
	kfree(buf);
	buf = NULL;

	if (list_empty(&uid_list))
		goto out;
	updated = true;
	list_for_each_entry (np, &uid_list, list) {
		if (!strcmp(np->package, KSU_MANAGER_PACKAGE)) {
			package_appid = np->appid;
			break;
		}
	}
	if (READ_ONCE(ksu_manager_package_appid) != package_appid) {
		WRITE_ONCE(ksu_manager_package_appid, package_appid);
		ksu_invalidate_manager_appid();
		clear_apk_scan_cache();
		/* Also cover a manager that started before packages.list was
		 * ready. The marker's clear path rechecks this identity under
		 * its lock. */
		ksu_mark_running_process();
	}
	/* Check the package as well as its uid, including after a reinstall. */
	if (ksu_manager_appid != KSU_INVALID_UID &&
	    ksu_manager_appid != package_appid)
		ksu_invalidate_manager_appid();
	if (prune_only)
		goto prune;

	if (package_appid != KSU_INVALID_UID &&
	    ksu_manager_appid == KSU_INVALID_UID) {
		pr_info("Searching manager " KSU_MANAGER_PACKAGE "...\n");
		search_manager("/data/app", 2, &uid_list);
		pr_info("Manager search finished\n");
	}

prune:
	// then prune the allowlist
	ksu_prune_allowlist(is_uid_exist, &uid_list);
out:
	kfree(buf);
	// free uid_list
	list_for_each_entry_safe (np, n, &uid_list, list) {
		list_del(&np->list);
		kfree(np);
	}
	revert_creds(old_cred);
	return updated;
}

void track_throne(bool prune_only)
{
	mutex_lock(&throne_scan_lock);
	track_throne_locked(prune_only);
	mutex_unlock(&throne_scan_lock);
}

/* Shared by package events and the initial search after a late load. */
static struct delayed_work throne_search_work;
static DEFINE_SPINLOCK(throne_work_lock);
static bool throne_work_stopping = true;

static void do_throne_search(struct work_struct *work)
{
	pr_info("throne_tracker: delayed search for manager...\n");
	track_throne(false);
}

void ksu_schedule_manager_scan(void)
{
	unsigned long flags;

	/* Package notifications may run with VFS directory locks held. Do not
	 * wait for throne_scan_lock or perform filesystem I/O in that context.
	 * Coalesce replacement events before reading the completed list. */
	spin_lock_irqsave(&throne_work_lock, flags);
	if (!throne_work_stopping)
		mod_delayed_work(system_wq, &throne_search_work,
				 msecs_to_jiffies(100));
	spin_unlock_irqrestore(&throne_work_lock, flags);
}

bool ksu_is_manager_package_uid(uid_t uid)
{
	uid_t appid = READ_ONCE(ksu_manager_package_appid);

	return appid != KSU_INVALID_UID && uid % PER_USER_RANGE == appid;
}

bool ksu_is_manager_auth_candidate(uid_t uid)
{
#ifdef CONFIG_KSU_SUPERKEY
	return superkey_is_set() && ksu_is_manager_package_uid(uid);
#else
	return false;
#endif
}

bool ksu_refresh_manager_package(uid_t uid, bool require_signature)
{
	bool allowed;

	/* Authentication must not reuse a stale identity after a failed read.
	 */
	mutex_lock(&throne_scan_lock);
	if (require_signature) {
		/* Re-read the installed APK at the password authorization
		 * boundary. Do not reuse a prior signature verdict across APK
		 * replacement. */
		ksu_invalidate_manager_appid();
		clear_apk_scan_cache();
	}
	allowed =
	    track_throne_locked(!require_signature) &&
	    ksu_is_manager_package_uid(uid) &&
	    (!require_signature || ksu_manager_appid == uid % PER_USER_RANGE);
	mutex_unlock(&throne_scan_lock);
	return allowed;
}

void ksu_throne_tracker_init(void)
{
	unsigned long flags;

	INIT_DELAYED_WORK(&throne_search_work, do_throne_search);
	spin_lock_irqsave(&throne_work_lock, flags);
	throne_work_stopping = false;
	schedule_delayed_work(&throne_search_work, msecs_to_jiffies(3000));
	spin_unlock_irqrestore(&throne_work_lock, flags);
	pr_info("throne_tracker: init, scheduled manager search in 3s\n");
}

void ksu_throne_tracker_exit(void)
{
	unsigned long flags;

	/* Serialize the last possible callback's queue operation with teardown,
	 * then wait outside the spinlock for any running scan to finish. */
	spin_lock_irqsave(&throne_work_lock, flags);
	throne_work_stopping = true;
	spin_unlock_irqrestore(&throne_work_lock, flags);
	cancel_delayed_work_sync(&throne_search_work);
	mutex_lock(&throne_scan_lock);
	clear_apk_scan_cache();
	mutex_unlock(&throne_scan_lock);
	pr_info("throne_tracker: exit\n");
}
