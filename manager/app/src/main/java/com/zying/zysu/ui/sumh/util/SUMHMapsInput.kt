package com.zying.zysu.ui.sumh.util

/** Keep unsigned 64-bit values intact when crossing the signed JNI Long boundary. */
internal fun parseSUMHMapsNumber(input: String): ULong? {
    val value = input.trim()
    if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
    return value.toULongOrNull()
}

/** Kernel MKDEV layout: (major << 20) | minor, using an unsigned 32-bit value. */
internal fun parseSUMHMapsDevice(input: String): ULong? = parseSUMHMapsNumber(input)?.takeIf { it <= UInt.MAX_VALUE.toULong() }

internal enum class SUMHMapsPathError { AbsolutePath, ControlCharacter, TooLong }

/** SUMH_MAX_LEN_PATHNAME is 256 bytes, including the terminating NUL. */
internal fun validateSUMHMapsPath(path: String, maxBytes: Int = 255): SUMHMapsPathError? = when {
    !path.startsWith('/') -> SUMHMapsPathError.AbsolutePath
    path.any { it.isISOControl() } -> SUMHMapsPathError.ControlCharacter
    path.toByteArray(Charsets.UTF_8).size > maxBytes -> SUMHMapsPathError.TooLong
    else -> null
}

internal const val SUMH_MAX_MAPS_PATH_RULES = 256

/** Managed definitions follow the config contract; preserve other literal path characters. */
internal fun validateSUMHManagedMapsPath(path: String, maxBytes: Int = 255): SUMHMapsPathError? = when {
    !path.startsWith('/') -> SUMHMapsPathError.AbsolutePath
    path.any { it == '\u0000' || it == '\r' || it == '\n' } -> SUMHMapsPathError.ControlCharacter
    path.toByteArray(Charsets.UTF_8).size > maxBytes -> SUMHMapsPathError.TooLong
    else -> null
}

internal data class SUMHMapsPathRuleValidation(
    val targetError: SUMHMapsPathError?,
    val replacementError: SUMHMapsPathError?,
    val duplicateTarget: Boolean,
    val limitReached: Boolean,
) {
    val valid: Boolean get() = targetError == null && replacementError == null && !duplicateTarget && !limitReached
}

/** Validate literal paths without trimming or normalizing meaningful spaces. */
internal fun validateSUMHMapsPathRule(
    target: String,
    replacement: String,
    existingTargets: List<String>,
    originalTarget: String? = null,
): SUMHMapsPathRuleValidation {
    val others = existingTargets.filterNot { it == originalTarget }
    return SUMHMapsPathRuleValidation(
        targetError = validateSUMHManagedMapsPath(target, 4095),
        replacementError = validateSUMHManagedMapsPath(replacement),
        duplicateTarget = target in others,
        limitReached = others.size >= SUMH_MAX_MAPS_PATH_RULES,
    )
}

internal fun sumhManagedMapsSupport(kernelAvailable: Boolean, capabilities: Set<String>?): SUMHFeatureSupport = when {
    !kernelAvailable -> SUMHFeatureSupport.KernelUnavailable
    capabilities == null -> SUMHFeatureSupport.Unknown
    "managed_maps" in capabilities -> SUMHFeatureSupport.Supported
    else -> SUMHFeatureSupport.Unsupported
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
        targetDevice = (parseSUMHMapsDevice(targetDevice) ?: return null).toLong(),
        spoofedInode = (parseSUMHMapsNumber(spoofedInode) ?: return null).toLong(),
        spoofedDevice = (parseSUMHMapsDevice(spoofedDevice) ?: return null).toLong(),
        path = path,
    )
}
