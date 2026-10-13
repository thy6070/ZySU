package com.zying.zysu.ui.util

private val blockModuleName = Regex("[A-Za-z0-9_]{1,64}")
private val knownBlockModules = setOf("vr", "vklp", "oplus_secure_guard", "oplus_secure_guard_new", "mkp")

/** Null means invalid input; an empty string deliberately blocks nothing. */
internal fun normalizeBlockModules(input: String): String? {
    if (input.isBlank()) return ""
    val names = input.split(',').map { it.trim() }
    if (names.any { !blockModuleName.matches(it) }) return null
    return names.distinct().joinToString(",").takeIf { it.length < 256 }
}

internal fun effectiveBlockModules(enabled: Boolean, input: String): String? =
    if (enabled) normalizeBlockModules(input) else ""

internal fun suggestedBlockModules(procModules: String): String = procModules.lineSequence()
    .map { it.substringBefore(' ').trim() }
    .filter { it in knownBlockModules }
    .distinct()
    .joinToString(",")
