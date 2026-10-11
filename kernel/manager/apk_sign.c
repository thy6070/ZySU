#include <linux/err.h>
#include <linux/fs.h>
#include <linux/gfp.h>
#include <linux/kernel.h>
#include <linux/limits.h>
#include <linux/slab.h>
#include <linux/string.h>
#ifdef CONFIG_KSU_DEBUG
#include <linux/moduleparam.h>
#endif // #ifdef CONFIG_KSU_DEBUG
#include <crypto/hash.h>
#include <crypto/sha2.h>

#include "manager/apk_sign.h"
#include "util.h"
#include "klog.h" // IWYU pragma: keep
#include "manager/manager_sign.h"
#include "uapi/app_profile.h"

#ifdef CONFIG_KSU_SUPERKEY
#include "manager/superkey.h"
#endif // #ifdef CONFIG_KSU_SUPERKEY

struct sdesc {
	struct shash_desc shash;
	char ctx[];
};

static struct sdesc *init_sdesc(struct crypto_shash *alg)
{
	struct sdesc *sdesc;
	int size;

	size = sizeof(struct shash_desc) + crypto_shash_descsize(alg);
	sdesc = kzalloc(size, GFP_KERNEL);
	if (!sdesc)
		return ERR_PTR(-ENOMEM);
	sdesc->shash.tfm = alg;
	return sdesc;
}

static int calc_hash(struct crypto_shash *alg, const unsigned char *data,
		     unsigned int datalen, unsigned char *digest)
{
	struct sdesc *sdesc;
	int ret;

	sdesc = init_sdesc(alg);
	if (IS_ERR(sdesc)) {
		pr_info("can't alloc sdesc\n");
		return PTR_ERR(sdesc);
	}

	ret = crypto_shash_digest(&sdesc->shash, data, datalen, digest);
	kfree(sdesc);
	return ret;
}

static int ksu_sha256(const unsigned char *data, unsigned int datalen,
		      unsigned char *digest)
{
	struct crypto_shash *alg;
	char *hash_alg_name = "sha256";
	int ret;

	alg = crypto_alloc_shash(hash_alg_name, 0, 0);
	if (IS_ERR(alg)) {
		pr_info("can't alloc alg %s\n", hash_alg_name);
		return PTR_ERR(alg);
	}
	ret = calc_hash(alg, data, datalen, digest);
	crypto_free_shash(alg);
	return ret;
}

static bool read_exact(struct file *fp, void *buffer, size_t size, loff_t *pos,
		       loff_t end)
{
	if (*pos < 0 || *pos > end || size > (size_t)(end - *pos))
		return false;

	return kernel_read(fp, buffer, size, pos) == (ssize_t)size;
}

static bool read_length_prefixed_end(struct file *fp, loff_t *pos,
				     loff_t container_end, loff_t *value_end)
{
	u32 length;

	if (!read_exact(fp, &length, sizeof(length), pos, container_end))
		return false;
	if (length > INT_MAX || length > (u64)(container_end - *pos))
		return false;

	*value_end = *pos + length;
	return true;
}

static bool check_block(struct file *fp, loff_t *pos, loff_t block_end)
{
	loff_t signers_end, signer_end, signed_data_end, digests_end,
	    certificates_end;
	u32 certificate_size;

	if (!read_length_prefixed_end(fp, pos, block_end, &signers_end) ||
	    !read_length_prefixed_end(fp, pos, signers_end, &signer_end) ||
	    !read_length_prefixed_end(fp, pos, signer_end, &signed_data_end) ||
	    !read_length_prefixed_end(fp, pos, signed_data_end, &digests_end))
		return false;

	*pos = digests_end;
	if (!read_length_prefixed_end(fp, pos, signed_data_end,
				      &certificates_end) ||
	    !read_exact(fp, &certificate_size, sizeof(certificate_size), pos,
			certificates_end))
		return false;
	if (certificate_size > INT_MAX ||
	    certificate_size > (u64)(certificates_end - *pos))
		return false;

#define CERT_MAX_LENGTH 1024
	if (certificate_size != EXPECTED_SIZE ||
	    certificate_size > CERT_MAX_LENGTH) {
		pr_info("unexpected certificate size\n");
		return false;
	}

	char cert[CERT_MAX_LENGTH];
	if (!read_exact(fp, cert, certificate_size, pos, certificates_end))
		return false;

	unsigned char digest[SHA256_DIGEST_SIZE];
	if (ksu_sha256(cert, certificate_size, digest)) {
		pr_info("sha256 error\n");
		return false;
	}

	char hash_str[SHA256_DIGEST_SIZE * 2 + 1];
	hash_str[SHA256_DIGEST_SIZE * 2] = '\0';

	bin2hex(hash_str, digest, SHA256_DIGEST_SIZE);
	pr_info("sha256: %s\n", hash_str);
	return !strcmp(EXPECTED_HASH, hash_str);
}

struct zip_entry_header {
	uint32_t signature;
	uint16_t version;
	uint16_t flags;
	uint16_t compression;
	uint16_t mod_time;
	uint16_t mod_date;
	uint32_t crc32;
	uint32_t compressed_size;
	uint32_t uncompressed_size;
	uint16_t file_name_length;
	uint16_t extra_field_length;
} __attribute__((packed));

// This is a necessary but not sufficient condition, but it is enough for us
static bool has_v1_signature_file(struct file *fp)
{
	struct zip_entry_header header;
	const char MANIFEST[] = "META-INF/MANIFEST.MF";

	loff_t pos = 0;

	while (kernel_read(fp, &header, sizeof(struct zip_entry_header),
			   &pos) == sizeof(struct zip_entry_header)) {
		if (header.signature != 0x04034b50) {
			// ZIP magic: 'PK'
			return false;
		}
		// Read the entry file name
		if (header.file_name_length == sizeof(MANIFEST) - 1) {
			char fileName[sizeof(MANIFEST)];
			kernel_read(fp, fileName, header.file_name_length,
				    &pos);
			fileName[header.file_name_length] = '\0';

			// Check if the entry matches META-INF/MANIFEST.MF
			if (strncmp(MANIFEST, fileName, sizeof(MANIFEST) - 1) ==
			    0) {
				return true;
			}
		} else {
			// Skip the entry file name
			pos += header.file_name_length;
		}

		// Skip to the next entry
		pos += header.extra_field_length + header.compressed_size;
	}

	return false;
}

static bool check_v2_signature(char *path)
{
	unsigned char buffer[0x10] = {0};
	u32 cd_offset, cd_size;
	u32 zip64_locator_magic;
	u64 size_of_block, size_of_block_at_head;

	loff_t pos, pairs_end, file_size, eocd_offset;

	bool v2_signing_valid = false;
	int v2_signing_blocks = 0;

	int i;
	struct file *fp = ksu_filp_open_nonotify(path, O_RDONLY | O_NOATIME);
	if (IS_ERR(fp)) {
		pr_err("open %s error.\n", path);
		return false;
	}

	file_size = generic_file_llseek(fp, 0, SEEK_END);
	if (file_size < 0)
		goto clean;

	// https://en.wikipedia.org/wiki/Zip_(file_format)#End_of_central_directory_record_(EOCD)
	for (i = 0;; ++i) {
		unsigned short comment_size;
		u32 magic;
		pos = file_size - i - 2;
		if (!read_exact(fp, &comment_size, sizeof(comment_size), &pos,
				file_size))
			goto clean;
		if (comment_size == i) {
			pos -= 22;
			if (!read_exact(fp, &magic, sizeof(magic), &pos,
					file_size))
				goto clean;
			if (magic == 0x06054b50) {
				eocd_offset = pos - sizeof(magic);
				break;
			}
		}
		if (i == 0xffff) {
			pr_info("error: cannot find eocd\n");
			goto clean;
		}
	}

	// Reject ZIP64 before looking for a signing block.
	if (eocd_offset >= 20) {
		pos = eocd_offset - 20;
		if (!read_exact(fp, &zip64_locator_magic,
				sizeof(zip64_locator_magic), &pos, file_size))
			goto clean;
		if (zip64_locator_magic == 0x07064b50)
			goto clean;
	}

	pos = eocd_offset + 12;
	// Size of central directory.
	if (!read_exact(fp, &cd_size, sizeof(cd_size), &pos, file_size))
		goto clean;
	// offset of central directory
	if (!read_exact(fp, &cd_offset, sizeof(cd_offset), &pos, file_size))
		goto clean;
	if ((u64)cd_offset > (u64)eocd_offset ||
	    (u64)cd_size != (u64)eocd_offset - cd_offset)
		goto clean;
	if (cd_offset < 0x20)
		goto clean;

	pairs_end = (loff_t)cd_offset - 0x18;
	pos = pairs_end;

	if (!read_exact(fp, &size_of_block, sizeof(size_of_block), &pos,
			cd_offset))
		goto clean;
	if (!read_exact(fp, buffer, sizeof(buffer), &pos, cd_offset))
		goto clean;
	if (memcmp((char *)buffer, "APK Sig Block 42", sizeof(buffer))) {
		goto clean;
	}

	if (size_of_block < 0x18 || size_of_block > INT_MAX - 0x8 ||
	    size_of_block > (u64)cd_offset - 0x8)
		goto clean;
	pos = (loff_t)cd_offset - (loff_t)size_of_block - 0x8;
	if (!read_exact(fp, &size_of_block_at_head,
			sizeof(size_of_block_at_head), &pos, pairs_end))
		goto clean;
	if (size_of_block_at_head != size_of_block)
		goto clean;

	// Scan every length-prefixed pair, matching AOSP's signing block
	// parser. Each valid pair consumes an 8-byte length plus at least a
	// 4-byte ID, so malformed entries fail below instead of spinning in
	// place.
	while (pos < pairs_end) {
		uint32_t id;
		u64 size_of_pair;
		loff_t pair_end;

		if (!read_exact(fp, &size_of_pair, sizeof(size_of_pair), &pos,
				pairs_end))
			goto invalid;
		if (size_of_pair < sizeof(id) || size_of_pair > INT_MAX ||
		    size_of_pair > (u64)(pairs_end - pos))
			goto invalid;

		pair_end = pos + (loff_t)size_of_pair;
		if (!read_exact(fp, &id, sizeof(id), &pos, pair_end))
			goto invalid;

		if (id == 0x7109871au) {
			v2_signing_blocks++;
			v2_signing_valid = check_block(fp, &pos, pair_end);
		} else if (id != 0x42726577u) { /* APK verity padding */
#ifdef CONFIG_KSU_DEBUG
			pr_info("Unexpected signature block id: 0x%08x\n", id);
#endif
			goto invalid;
		}
		pos = pair_end;
	}

	if (v2_signing_blocks != 1) {
#ifdef CONFIG_KSU_DEBUG
		pr_err("Unexpected v2 signature count: %d\n",
		       v2_signing_blocks);
#endif // #ifdef CONFIG_KSU_DEBUG
		v2_signing_valid = false;
	}

	if (v2_signing_valid) {
		int has_v1_signing = has_v1_signature_file(fp);
		if (has_v1_signing) {
			pr_err("Unexpected v1 signature scheme found!\n");
			goto invalid;
		}
	}

	goto clean;

invalid:
	v2_signing_valid = false;
clean:
	filp_close(fp, 0);

	return v2_signing_valid;
}

#ifdef CONFIG_KSU_DEBUG

int ksu_debug_manager_uid = -1;

#include "manager/manager_identity.h"

static int set_expected_size(const char *val, const struct kernel_param *kp)
{
	int rv = param_set_uint(val, kp);
	ksu_set_manager_uid(ksu_debug_manager_uid);
	pr_info("ksu_manager_uid set to %d\n", ksu_debug_manager_uid);
	return rv;
}

static struct kernel_param_ops expected_size_ops = {
    .set = set_expected_size,
    .get = param_get_uint,
};

module_param_cb(ksu_debug_manager_uid, &expected_size_ops,
		&ksu_debug_manager_uid, S_IRUSR | S_IWUSR);

#endif // #ifdef CONFIG_KSU_DEBUG

static bool matches_manager_package(const char *name, size_t length)
{
	size_t package_len = sizeof(KSU_MANAGER_PACKAGE) - 1;

	_Static_assert(sizeof(KSU_MANAGER_PACKAGE) > 1 &&
			   sizeof(KSU_MANAGER_PACKAGE) <= KSU_MAX_PACKAGE_NAME,
		       "Invalid KSU_MANAGER_PACKAGE length");
	return length > package_len + 1 &&
	       !strncmp(name, KSU_MANAGER_PACKAGE, package_len) &&
	       name[package_len] == '-';
}

bool is_manager_package_dir(const char *path)
{
	const char *package;

	if (!path || path[0] != '/')
		return false;
	package = strrchr(path, '/') + 1;
	return matches_manager_package(package, strlen(package));
}

bool is_manager_package(const char *path)
{
	const char *apk, *package;

	if (!path || path[0] != '/')
		return false;
	apk = strrchr(path, '/');
	if (strcmp(apk, "/base.apk"))
		return false;
	package = apk;
	while (package > path && package[-1] != '/')
		package--;
	return matches_manager_package(package, apk - package);
}

bool is_manager_apk(char *path)
{
#ifdef CONFIG_KSU_SUPERKEY
	/* Password-only mode still requires the pinned package at auth time. */
	if (superkey_is_set() && superkey_is_signature_bypassed())
		return false;
#endif
	/* NULL queries whether signature authentication is enabled. */
	if (!path)
		return true;
	return is_manager_package(path) && check_v2_signature(path);
}
