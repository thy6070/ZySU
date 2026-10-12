package com.zying.zysu.ui.sumh.util

/** Keep unsigned 64-bit values intact when crossing the signed JNI Long boundary. */
internal fun parseSUMHMapsNumber(input: String): ULong? {
    val value = input.trim()
    if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
    return value.toULongOrNull()
}

internal enum class SUMHMapsPathError { AbsolutePath, ControlCharacter, TooLong }

/** SUMH_MAX_LEN_PATHNAME is 256 bytes, including the terminating NUL. */
internal fun validateSUMHMapsPath(path: String): SUMHMapsPathError? = when {
    !path.startsWith('/') -> SUMHMapsPathError.AbsolutePath
    path.any { it.isISOControl() } -> SUMHMapsPathError.ControlCharacter
    path.toByteArray(Charsets.UTF_8).size > 255 -> SUMHMapsPathError.TooLong
    else -> null
}

internal data class SUMHMapsRuleInput(
    val targetInode: Long,
    val targetDevice: Long,
    val spoofedInode: Long,
    val spoofedDevice: Long,
    val path: String,
)

internal fun parseSUMHMapsRuleInput(
    targetInode: String,
    targetDevice: String,
    spoofedInode: String,
    spoofedDevice: String,
    path: String,
): SUMHMapsRuleInput? {
    if (validateSUMHMapsPath(path) != null) return null
    return SUMHMapsRuleInput(
        targetInode = (parseSUMHMapsNumber(targetInode) ?: return null).toLong(),
        targetDevice = (parseSUMHMapsNumber(targetDevice) ?: return null).toLong(),
        spoofedInode = (parseSUMHMapsNumber(spoofedInode) ?: return null).toLong(),
        spoofedDevice = (parseSUMHMapsNumber(spoofedDevice) ?: return null).toLong(),
        path = path,
    )
}
