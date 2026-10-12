package com.zying.zysu.ui.sumh.util

import kotlinx.coroutines.CancellationException
import java.io.IOException

enum class SUMHHideAction { Add, Remove, Retry }

/** Success means the controller accepted the operation, not that a path has bound in the kernel. */
data class SUMHHideItemResult(val path: String, val error: String? = null) {
    val succeeded: Boolean get() = error == null
}

data class SUMHHideBatchResult(val action: SUMHHideAction, val items: List<SUMHHideItemResult>)

internal suspend fun runSUMHHideBatch(
    action: SUMHHideAction,
    paths: List<String>,
    operation: suspend (String) -> Unit,
): SUMHHideBatchResult {
    val unique = paths.distinct()
    require(unique.size in 1..SUMH_HIDE_BATCH_LIMIT) { "Choose between 1 and $SUMH_HIDE_BATCH_LIMIT paths" }
    // Validate the entire request before starting any mutations. Existing definitions
    // can still be removed even when they predate the stricter new-path validation.
    require(unique.all { path ->
        if (action == SUMHHideAction.Add) validateSUMHHidePath(path) == null
        else path.startsWith('/') && '\u0000' !in path && path.toByteArray(Charsets.UTF_8).size < 4096
    }) { "Invalid hide rule path" }
    var requestError: String? = null
    return SUMHHideBatchResult(action, unique.map { path ->
        requestError?.let { return@map SUMHHideItemResult(path, it) }
        try {
            operation(path)
            SUMHHideItemResult(path)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // A dead controller can otherwise hold a 100-item batch for 100 timeouts.
            // Do not replay a request with an unknown outcome; let the user refresh
            // and explicitly retry after the connection recovers.
            if (error is IOException) {
                requestError = "Not attempted after a controller or kernel I/O failure; refresh before retrying"
            }
            SUMHHideItemResult(path, error.message?.takeIf(String::isNotBlank) ?: "Rule operation failed")
        }
    })
}
