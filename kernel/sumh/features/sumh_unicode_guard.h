#ifndef _SUMH_UNICODE_GUARD_H
#define _SUMH_UNICODE_GUARD_H

#include <linux/types.h>

struct qstr;

void sumh_unicode_guard_init(void);
void sumh_unicode_guard_exit(void);

/* Non-sleeping, allocation-free; independent of module-view/UID policy. */
bool sumh_unicode_guard_reject_component(const struct qstr *name);

#endif /* _SUMH_UNICODE_GUARD_H */
