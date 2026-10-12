#ifndef __KSU_H_SUPERCALL
#define __KSU_H_SUPERCALL

#include <linux/fs.h>

#include "uapi/supercall.h"

int ksu_install_fd(void);
int ksu_install_su_fd(void);
bool ksu_is_su_session_fd(const struct file *filp);
void ksu_supercalls_init(void);
void ksu_supercalls_exit(void);

#endif // #ifndef __KSU_H_SUPERCALL
