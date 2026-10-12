package com.zying.zysu.ui.sumh

import com.zying.zysu.ui.sumh.util.SUMHHideAction
import com.zying.zysu.ui.sumh.util.SUMHHideBatchResult
import com.zying.zysu.ui.sumh.util.SUMHHideItemResult
import com.zying.zysu.ui.sumh.util.SUMHUiAdapter.ActiveRule
import org.junit.Assert.*
import org.junit.Test

class SUMHUserHideRulesTest {
    private fun rule(path: String, state: Int?, persisted: Boolean = true) =
        ActiveRule("hide", path, isUserDefined = true, hideState = state, persisted = persisted)

    @Test
    fun attentionIncludesUnknownAndSuspendedButNotBindingOrEffective() {
        val rules = listOf(-1, 0, 1, 2, 3, 4, null).map { rule("/data/$it", it) }
        assertEquals(listOf(-1, 0, 3, 4, null),
            filterUserHideRules(rules, "", UserHideFilter.Attention).map { it.hideState })
        assertEquals(listOf(2), filterUserHideRules(rules, "", UserHideFilter.Effective).map { it.hideState })
    }

    @Test
    fun searchAndStateFilterIntersectWithoutDiscardingHiddenSelection() {
        val matching = rule("/data/Visible", 3)
        val hidden = rule("/data/other", 3)
        val rules = listOf(matching, hidden, rule("/data/visible-bound", 2))
        val selected = setOf(matching.src, hidden.src)
        val visible = filterUserHideRules(rules, "  VISIBLE  ", UserHideFilter.Attention)
        assertEquals(listOf(matching), visible)
        assertEquals(2, selected.size)
        assertEquals(1, visible.count { it.src in selected })
    }

    @Test
    fun runtimeOnlyRulesRemainVisibleButCannotBeReaddedByRetry() {
        val runtimeOnly = rule("/data/live", 3, persisted = false)
        assertEquals(listOf(runtimeOnly), filterUserHideRules(listOf(runtimeOnly), "", UserHideFilter.Attention))
        assertFalse(userHideCanRetry(runtimeOnly))
        assertFalse(userHideCanRetry(rule("/data/binding", 1)))
        assertTrue(userHideCanRetry(rule("/data/suspended", 4)))
    }

    @Test
    fun partialAddRetainsFailedAndUntouchedDraftLines() {
        val draft = "  /data/ok  \n/data/failed\ninvalid\n/data/ok"
        val result = SUMHHideBatchResult(SUMHHideAction.Add, listOf(
            SUMHHideItemResult("/data/ok"), SUMHHideItemResult("/data/failed", "permission denied"),
        ))
        assertEquals("/data/failed\ninvalid", remainingUserHideDraft(draft, result))
    }

    @Test
    fun failedAddOrOtherActionsDoNotClearDraft() {
        val draft = "/data/pending\n"
        assertEquals(draft, remainingUserHideDraft(draft, SUMHHideBatchResult(SUMHHideAction.Add,
            listOf(SUMHHideItemResult("/data/pending", "failed")))))
        assertEquals(draft, remainingUserHideDraft(draft, SUMHHideBatchResult(SUMHHideAction.Remove,
            listOf(SUMHHideItemResult("/data/pending")))))
    }

    @Test
    fun resultRestoresCountsActionAndExactFailurePaths() {
        val original = UserHideOperationResult.from(SUMHHideBatchResult(SUMHHideAction.Remove, listOf(
            SUMHHideItemResult("/data/done"), SUMHHideItemResult("/data/failed", "permission denied"),
        )))
        val restored = restoreUserHideResult(saveUserHideResult(original))!!
        assertEquals(original, restored)
        assertEquals(0, restored.omittedFailures)
        assertFalse(saveUserHideResult(original).contains("/data/done"))
    }

    @Test
    fun oversizedResultsKeepTruthfulCountsAndNeverTruncateRetryPaths() {
        val items = (0 until 100).map {
            SUMHHideItemResult("/data/$it/" + "p".repeat(4080), "failure ".repeat(1000))
        }
        val original = UserHideOperationResult.from(SUMHHideBatchResult(SUMHHideAction.Retry, items))
        val saved = saveUserHideResult(original)
        assertTrue(userHideSavedStringsBytes(saved) <= USER_HIDE_RESULT_SAVE_BYTES)
        val restored = restoreUserHideResult(saved)!!
        assertEquals(100, restored.failedCount)
        assertEquals(0, restored.completedCount)
        assertTrue(restored.failures.isNotEmpty())
        assertTrue(restored.omittedFailures > 0)
        assertTrue(restored.shortenedErrors)
        assertTrue(restored.failures.all { restoredItem -> items.any { it.path == restoredItem.path } })
        assertTrue(restored.failures.all { it.error!!.length <= 512 })
        assertEquals(restored, restoreUserHideResult(saveUserHideResult(restored)))
    }

    @Test
    fun allSuccessSummaryRestoresWithoutSavingLargePaths() {
        val result = UserHideOperationResult.from(SUMHHideBatchResult(SUMHHideAction.Add,
            (0 until 100).map { SUMHHideItemResult("/data/$it/" + "p".repeat(4080)) }))
        val saved = saveUserHideResult(result)
        assertEquals(4, saved.size)
        val restored = restoreUserHideResult(saved)!!
        assertEquals(100, restored.completedCount)
        assertEquals(0, restored.failedCount)
        assertTrue(restored.failures.isEmpty())
    }

    @Test
    fun errorShorteningAndMissingPathsAreReportedSeparately() {
        val result = UserHideOperationResult.from(SUMHHideBatchResult(SUMHHideAction.Add,
            listOf(SUMHHideItemResult("/data/path", "e".repeat(10000)))))
        val restored = restoreUserHideResult(saveUserHideResult(result))!!
        assertEquals(0, restored.omittedFailures)
        assertTrue(restored.shortenedErrors)
        assertEquals("/data/path", restored.failures.single().path)
    }

    @Test
    fun invalidSavedSummaryDoesNotCreateRetryableResults() {
        assertNull(restoreUserHideResult(emptyList()))
        assertNull(restoreUserHideResult(listOf("Remove", "1", "0", "false", "/data/a", "error")))
        assertNull(restoreUserHideResult(listOf("Remove", "-1", "1", "false")))
        assertNull(restoreUserHideResult(listOf("unknown", "0", "1", "false")))
    }
}
