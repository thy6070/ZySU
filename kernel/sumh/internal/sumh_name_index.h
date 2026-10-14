#ifndef _SUMH_NAME_INDEX_H
#define _SUMH_NAME_INDEX_H

#include <linux/string.h>

#include "sumh_types.h"

/* A caller-owned index for one directory enumeration. Nodes and names remain
 * owned by the output list; the tree only accelerates duplicate checks and
 * never changes the list's ordering or first-entry precedence.
 */
static inline int sumh_name_compare(const char *name, size_t len,
				    const struct sumh_name_list *item)
{
	size_t common = len < item->name_len ? len : item->name_len;
	int cmp = memcmp(name, item->name, common);

	if (cmp)
		return cmp;
	return (len > item->name_len) - (len < item->name_len);
}

static inline bool sumh_name_index_contains(const struct rb_root *root,
					    const char *name, size_t len)
{
	struct rb_node *node = root->rb_node;

	while (node) {
		struct sumh_name_list *item =
		    rb_entry(node, struct sumh_name_list, name_node);
		int cmp = sumh_name_compare(name, len, item);

		if (!cmp)
			return true;
		node = cmp < 0 ? node->rb_left : node->rb_right;
	}
	return false;
}

static inline void sumh_name_index_add(struct rb_root *root,
				       struct sumh_name_list *item)
{
	struct rb_node **link = &root->rb_node;
	struct rb_node *parent = NULL;

	item->name_len = strlen(item->name);
	while (*link) {
		struct sumh_name_list *existing =
		    rb_entry(*link, struct sumh_name_list, name_node);
		int cmp =
		    sumh_name_compare(item->name, item->name_len, existing);

		if (!cmp)
			return;
		parent = *link;
		link = cmp < 0 ? &parent->rb_left : &parent->rb_right;
	}
	rb_link_node(&item->name_node, parent, link);
	rb_insert_color(&item->name_node, root);
}

static inline void sumh_name_index_init(struct rb_root *root,
					struct list_head *head)
{
	struct sumh_name_list *item;

	*root = RB_ROOT;
	list_for_each_entry (item, head, list)
		sumh_name_index_add(root, item);
}

#endif /* _SUMH_NAME_INDEX_H */
