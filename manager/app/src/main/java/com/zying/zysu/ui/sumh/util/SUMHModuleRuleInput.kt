package com.zying.zysu.ui.sumh.util

import com.zying.zysu.ui.sumh.util.SUMHManager.ModuleRule
import org.json.JSONArray
import org.json.JSONObject

internal const val SUMH_MAX_MODULE_RULES = 256
internal const val SUMH_MAX_MODULE_RULES_ENCODED_BYTES = 56 * 1024

internal enum class SUMHModuleRuleError {
    AbsolutePath, ControlCharacter, TooLong, NonCanonicalPath, DuplicatePath, InvalidMode, RuleLimit, PayloadLimit,
}

/** Paths are literal. Reject ambiguous components instead of rewriting the user's path. */
internal fun validateSUMHModuleRulePath(path: String): SUMHModuleRuleError? = when {
    !path.startsWith('/') -> SUMHModuleRuleError.AbsolutePath
    path.any { it == '\u0000' || it == '\r' || it == '\n' } -> SUMHModuleRuleError.ControlCharacter
    path.toByteArray(Charsets.UTF_8).size >= 4096 -> SUMHModuleRuleError.TooLong
    path != "/" && (path.endsWith('/') || path.drop(1).split('/').any { it.isEmpty() || it == "." || it == ".." }) ->
        SUMHModuleRuleError.NonCanonicalPath
    else -> null
}

internal fun moduleRulesJson(rules: List<ModuleRule>) = JSONArray(rules.map {
    JSONObject().put("path", it.path).put("mode", it.mode)
})

/** Account for the command's JSON string and spaces inserted by the daemon serializer. */
internal fun moduleRulesEncodedBytes(rules: List<ModuleRule>): Int =
    JSONObject.quote(moduleRulesJson(rules).toString()).toByteArray(Charsets.UTF_8).size +
        (rules.size * 2 - 1).coerceAtLeast(0)

internal fun moduleRulesWithinBudget(rules: List<ModuleRule>): Boolean =
    moduleRulesEncodedBytes(rules) <= SUMH_MAX_MODULE_RULES_ENCODED_BYTES

internal fun validateSUMHModuleRules(rules: List<ModuleRule>): SUMHModuleRuleError? {
    if (rules.size > SUMH_MAX_MODULE_RULES) return SUMHModuleRuleError.RuleLimit
    val paths = HashSet<String>()
    for (rule in rules) {
        validateSUMHModuleRulePath(rule.path)?.let { return it }
        if (!paths.add(rule.path)) return SUMHModuleRuleError.DuplicatePath
        if (rule.mode !in SUMHManager.ruleModes) return SUMHModuleRuleError.InvalidMode
    }
    return if (moduleRulesWithinBudget(rules)) null else SUMHModuleRuleError.PayloadLimit
}

/** Retain ordering when editing; a removed original cannot silently become an addition. */
internal fun updateSUMHModuleRule(
    rules: List<ModuleRule>, originalPath: String?, replacement: ModuleRule,
): List<ModuleRule>? = when {
    originalPath == null -> rules + replacement
    rules.none { it.path == originalPath } -> null
    else -> rules.map { if (it.path == originalPath) replacement else it }
}
