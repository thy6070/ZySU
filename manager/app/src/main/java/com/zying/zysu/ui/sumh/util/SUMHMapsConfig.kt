package com.zying.zysu.ui.sumh.util

import com.zying.zysu.ui.sumh.util.SUMHUiAdapter.MapsPathRule
import org.json.JSONArray
import org.json.JSONObject

internal const val SUMH_MAX_MAPS_ENCODED_BYTES = 56 * 1024

internal fun mapsRulesJson(rules: List<MapsPathRule>) = JSONArray(rules.map {
    JSONObject().put("target_path", it.targetPath).put("replacement_path", it.replacementPath)
})

/** Include JSON re-escaping plus the daemon serializer's spaces after object/array commas. */
internal fun mapsRulesEncodedBytes(rules: List<MapsPathRule>): Int =
    JSONObject.quote(mapsRulesJson(rules).toString()).toByteArray(Charsets.UTF_8).size +
        (rules.size * 2 - 1).coerceAtLeast(0)

internal fun mapsRulesWithinBudget(rules: List<MapsPathRule>): Boolean =
    mapsRulesEncodedBytes(rules) <= SUMH_MAX_MAPS_ENCODED_BYTES

/** Edit in place so one config apply replaces the managed set without a remove/add gap. */
internal fun updateSUMHMapsPathRule(
    rules: List<MapsPathRule>, originalTarget: String?, replacement: MapsPathRule,
): List<MapsPathRule>? = when {
    originalTarget == null -> rules + replacement
    rules.none { it.targetPath == originalTarget } -> null
    else -> rules.map { if (it.targetPath == originalTarget) replacement else it }
}
