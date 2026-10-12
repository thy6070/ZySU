package com.zying.zysu.ui.sumh

import com.zying.zysu.ui.sumh.util.SUMHManager

/** Only a successful kernel apply can clear an earlier pending runtime change. */
internal fun pendingSUMHApplyAfterSave(
    wasPending: Boolean,
    result: SUMHManager.ConfigSaveResult,
): Boolean = when {
    result.noChanges || !result.persisted -> wasPending
    result.applied -> false
    result.runtimeRequired -> true
    else -> wasPending
}
