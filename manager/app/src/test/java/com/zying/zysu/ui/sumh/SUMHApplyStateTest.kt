package com.zying.zysu.ui.sumh

import com.zying.zysu.ui.sumh.util.SUMHManager.ConfigSaveResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SUMHApplyStateTest {
    @Test
    fun unrelatedMountOrLogSaveKeepsEarlierApplyFailure() {
        assertTrue(pendingSUMHApplyAfterSave(true, ConfigSaveResult(true, false, rebootRequired = true)))
        assertTrue(pendingSUMHApplyAfterSave(true, ConfigSaveResult(true, false)))
        assertFalse(pendingSUMHApplyAfterSave(false, ConfigSaveResult(true, false, rebootRequired = true)))
    }

    @Test
    fun saveWithoutKernelAndFailedApplyBothNeedRetry() {
        assertTrue(pendingSUMHApplyAfterSave(false, ConfigSaveResult(true, false, runtimeRequired = true)))
        assertTrue(pendingSUMHApplyAfterSave(false, ConfigSaveResult(true, false, "apply failed", runtimeRequired = true)))
    }

    @Test
    fun failedPersistenceAndNoChangesLeavePendingStateAlone() {
        for (pending in listOf(false, true)) {
            val failed = ConfigSaveResult(false, false, "save failed", runtimeRequired = true)
            val unchanged = ConfigSaveResult(false, false, noChanges = true)
            kotlin.test.assertEquals(pending, pendingSUMHApplyAfterSave(pending, failed))
            kotlin.test.assertEquals(pending, pendingSUMHApplyAfterSave(pending, unchanged))
        }
    }

    @Test
    fun successfulApplyClearsPendingState() {
        assertFalse(pendingSUMHApplyAfterSave(true, ConfigSaveResult(true, true, runtimeRequired = true)))
    }
}
