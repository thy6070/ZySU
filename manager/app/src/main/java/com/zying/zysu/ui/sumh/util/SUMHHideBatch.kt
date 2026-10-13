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
    shouldContinue: () -> Boolean = { true },
    onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    operation: suspend (String) -> Unit,
): SUMHHideBatchResult {
    val unique = paths.distinct()
    require(unique.isNotEmpty()) { "Choose at least one path" }
    // Validate the controller contract before starting any mutations.
    require(unique.all { validateSUMHHidePath(it) == null }) { "Invalid hide rule path" }
    var requestError: String? = null
    onProgress(0, unique.size)
    return SUMHHideBatchResult(action, unique.mapIndexed { index, path ->
        if (!shouldContinue() && requestError == null) requestError = "Not attempted: stopped by user"
        requestError?.let { return@mapIndexed SUMHHideItemResult(path, it) }
        try {
            operation(path)
            SUMHHideItemResult(path)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // A dead controller can otherwise hold a large batch for repeated timeouts.
            // Do not replay a request with an unknown outcome; let the user refresh
            // and explicitly retry after the connection recovers.
            if (error is IOException) {
                requestError = "Not attempted after a controller or kernel I/O failure; refresh before retrying"
            }
            SUMHHideItemResult(path, error.message?.takeIf(String::isNotBlank) ?: "Rule operation failed")
        }.also { onProgress(index + 1, unique.size) }
    })
}
