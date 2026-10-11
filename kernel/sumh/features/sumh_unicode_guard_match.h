#ifndef _SUMH_UNICODE_GUARD_MATCH_H
#define _SUMH_UNICODE_GUARD_MATCH_H

/*
 * Unicode 17.0 DerivedCoreProperties.txt, Default_Ignorable_Code_Point.
 * https://www.unicode.org/Public/17.0.0/ucd/DerivedCoreProperties.txt
 * Adjacent ranges are merged; reserved code points are intentionally included.
 * This is the complete property, not a hand-picked list of zero-width spaces.
 *
 * Keep this header independent of kernel APIs for host regression coverage.
 */
static inline int sumh_unicode_default_ignorable(unsigned int cp)
{
	return cp == 0x00ad || cp == 0x034f || cp == 0x061c ||
	       (cp >= 0x115f && cp <= 0x1160) ||
	       (cp >= 0x17b4 && cp <= 0x17b5) ||
	       (cp >= 0x180b && cp <= 0x180f) ||
	       (cp >= 0x200b && cp <= 0x200f) ||
	       (cp >= 0x202a && cp <= 0x202e) ||
	       (cp >= 0x2060 && cp <= 0x206f) || cp == 0x3164 ||
	       (cp >= 0xfe00 && cp <= 0xfe0f) || cp == 0xfeff || cp == 0xffa0 ||
	       (cp >= 0xfff0 && cp <= 0xfff8) ||
	       (cp >= 0x1bca0 && cp <= 0x1bca3) ||
	       (cp >= 0x1d173 && cp <= 0x1d17a) ||
	       (cp >= 0xe0000 && cp <= 0xe0fff);
}

/* Decode exactly one scalar. Reject overlong, surrogate and truncated UTF-8. */
static inline unsigned int sumh_unicode_decode(const unsigned char *name,
					       unsigned int len,
					       unsigned int *cp)
{
	unsigned int value, count, min, i;

	if (!len)
		return 0;
	if (name[0] < 0x80) {
		*cp = name[0];
		return 1;
	}
	if (name[0] >= 0xc2 && name[0] <= 0xdf) {
		value = name[0] & 0x1f;
		count = 2;
		min = 0x80;
	} else if (name[0] >= 0xe0 && name[0] <= 0xef) {
		value = name[0] & 0x0f;
		count = 3;
		min = 0x800;
	} else if (name[0] >= 0xf0 && name[0] <= 0xf4) {
		value = name[0] & 0x07;
		count = 4;
		min = 0x10000;
	} else {
		return 0;
	}
	if (len < count)
		return 0;
	for (i = 1; i < count; i++) {
		if ((name[i] & 0xc0) != 0x80)
			return 0;
		value = (value << 6) | (name[i] & 0x3f);
	}
	if (value < min || value > 0x10ffff ||
	    (value >= 0xd800 && value <= 0xdfff))
		return 0;
	*cp = value;
	return count;
}

/*
 * Match only default-ignorable obfuscations of the three ASCII security
 * components. Ordinary ASCII spellings and unrelated Unicode names pass.
 * This is deliberately not a general filename normalizer: never rewrite a
 * path, strip characters from user files, or run normalization on file data.
 * The longest candidate is seven bytes, so ordinary names exit early. Names
 * made of ignorable characters are bounded by the supplied component length.
 * Do not use NAME_MAX here: d_hash precedes filesystem length validation and
 * a longer spelling can still match an already cached casefolded dentry.
 */
static inline int sumh_unicode_sensitive_alias(const unsigned char *name,
					       unsigned int len)
{
	static const char android[] = "android";
	static const char data[] = "data";
	static const char obb[] = "obb";
	unsigned int candidates = 7, pos = 0, offset = 0;
	int ignored = 0;

	if (!name || !len)
		return 0;
	while (offset < len) {
		unsigned int cp, count;

		count = sumh_unicode_decode(name + offset, len - offset, &cp);
		if (!count)
			return 0;
		offset += count;
		if (sumh_unicode_default_ignorable(cp)) {
			ignored = 1;
			continue;
		}
		if (cp >= 'A' && cp <= 'Z')
			cp += 'a' - 'A';
		if (pos >= 7 || cp > 0x7f)
			return 0;
		if (cp != (unsigned int)android[pos])
			candidates &= ~1U;
		if (pos >= 4 || cp != (unsigned int)data[pos])
			candidates &= ~2U;
		if (pos >= 3 || cp != (unsigned int)obb[pos])
			candidates &= ~4U;
		if (!candidates)
			return 0;
		pos++;
	}
	return ignored && (((candidates & 1) && pos == 7) ||
			   ((candidates & 2) && pos == 4) ||
			   ((candidates & 4) && pos == 3));
}

#endif /* _SUMH_UNICODE_GUARD_MATCH_H */
