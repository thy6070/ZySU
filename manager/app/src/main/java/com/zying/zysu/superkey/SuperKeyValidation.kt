package com.zying.zysu.superkey

import java.nio.charset.CharacterCodingException

internal const val MAX_SUPERKEY_BYTES = 64

/** Uses the same UTF-8 bytes for patching and the kernel's NUL-terminated key field. */
internal fun encodeSuperKey(superKey: String): ByteArray? {
    val bytes = try {
        superKey.encodeToByteArray(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        return null
    }
    return bytes.takeIf { it.size <= MAX_SUPERKEY_BYTES && 0.toByte() !in it }
}
