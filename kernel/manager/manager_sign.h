#ifndef MANAGER_SIGN_H
#define MANAGER_SIGN_H

// The single Manager certificate must match the pinned package.
#if defined(KSU_MANAGER_CERT_SIZE) != defined(KSU_MANAGER_CERT_SHA256)
#error "Manager certificate size and SHA-256 must be configured together"
#endif

#ifdef KSU_MANAGER_CERT_SIZE
#define EXPECTED_SIZE KSU_MANAGER_CERT_SIZE
#define EXPECTED_HASH KSU_MANAGER_CERT_SHA256
#else
#define EXPECTED_SIZE 0x29c
#define EXPECTED_HASH                                                          \
	"39559b380d4c0191eed81b7eba61533b6a2f247bc55bceba4259e983673f58b7"
#endif

#endif /* MANAGER_SIGN_H */
