package com.zying.zysu.ui.sumh.util

import kotlinx.coroutines.CancellationException

data class SUMHHideEditResult(val replacementSaved: Boolean, val originalRemoved: Boolean, val error: String? = null)

/** Never remove the original definition until the replacement command succeeds. */
internal suspend fun runSUMHHideEdit(
    original: String,
    replacement: String,
    prepareRemoval: suspend () -> Unit,
    add: suspend (String) -> Unit,
    remove: suspend (String) -> Unit,
): SUMHHideEditResult {
    require(validateSUMHHidePath(replacement) == null) { "Invalid hide rule path" }
    if (original == replacement) return SUMHHideEditResult(true, true)
    var saved = false
    return try {
        prepareRemoval()
        add(replacement)
        saved = true
        remove(original)
        SUMHHideEditResult(true, true)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        SUMHHideEditResult(saved, false, error.message ?: "Rule edit failed")
    }
}
