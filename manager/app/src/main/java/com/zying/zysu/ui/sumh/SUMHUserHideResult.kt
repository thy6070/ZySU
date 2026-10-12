package com.zying.zysu.ui.sumh

import com.zying.zysu.ui.sumh.util.SUMHHideAction
import com.zying.zysu.ui.sumh.util.SUMHHideBatchResult
import com.zying.zysu.ui.sumh.util.SUMHHideItemResult
import com.zying.zysu.ui.sumh.util.SUMH_HIDE_BATCH_LIMIT

/** Counts remain complete even when large failure details cannot fit saved screen state. */
internal data class UserHideOperationResult(
    val action: SUMHHideAction,
    val completedCount: Int,
    val failedCount: Int,
    val failures: List<SUMHHideItemResult>,
    val shortenedErrors: Boolean = false,
) {
    val omittedFailures: Int get() = failedCount - failures.size

    companion object {
        fun from(result: SUMHHideBatchResult) = UserHideOperationResult(
            result.action, result.items.count { it.succeeded }, result.items.count { !it.succeeded },
            result.items.filterNot { it.succeeded },
        )
    }
}

internal const val USER_HIDE_RESULT_SAVE_BYTES = 64 * 1024
private const val USER_HIDE_RESULT_ERROR_CHARS = 512

/** Conservative Parcel string size, including length, UTF-16 terminator, alignment and list overhead. */
internal fun userHideSavedStringsBytes(values: List<String>): Int =
    32 + values.sumOf { 16 + it.length * 2 }

internal fun saveUserHideResult(result: UserHideOperationResult?): List<String> {
    if (result == null) return emptyList()
    val values = mutableListOf(result.action.name, result.completedCount.toString(), result.failedCount.toString(),
        result.shortenedErrors.toString())
    var bytes = userHideSavedStringsBytes(values)
    result.failures.forEach { failure ->
        val error = failure.error.orEmpty()
        val savedError = if (error.length > USER_HIDE_RESULT_ERROR_CHARS) {
            error.take(USER_HIDE_RESULT_ERROR_CHARS - 1) + "…"
        } else error
        val itemBytes = 32 + (failure.path.length + savedError.length) * 2
        if (bytes + itemBytes <= USER_HIDE_RESULT_SAVE_BYTES) {
            // Never shorten a path: every restored retry must target the exact original path.
            if (error.length > USER_HIDE_RESULT_ERROR_CHARS) values[3] = "true"
            values += failure.path
            values += savedError
            bytes += itemBytes
        }
    }
    return values
}

internal fun restoreUserHideResult(values: List<String>): UserHideOperationResult? {
    if (values.isEmpty()) return null
    if (values.size < 4 || values.size % 2 != 0) return null
    val action = SUMHHideAction.entries.find { it.name == values[0] } ?: return null
    val completed = values[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val failed = values[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val failures = values.drop(4).chunked(2).map { SUMHHideItemResult(it[0], it[1]) }
    if (failures.size > failed || completed + failed > SUMH_HIDE_BATCH_LIMIT) return null
    return UserHideOperationResult(action, completed, failed, failures, values[3].toBoolean())
}
