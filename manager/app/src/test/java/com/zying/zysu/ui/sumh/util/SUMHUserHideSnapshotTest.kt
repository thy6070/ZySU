package com.zying.zysu.ui.sumh.util

import android.app.Application
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SUMHUserHideSnapshotTest {
    @Test
    fun failedRuntimeDeletionStaysManageableWithoutPretendingItIsSaved() {
        val snapshot = SUMHManager.RuleSnapshot(JSONObject("""{"user_hide":[
            {"path":"/deleted","management":0,"binding":2,"error":0},
            {"path":"/saved","management":0,"binding":3,"error":-13}
        ]}"""), listOf("/saved", "/unregistered"))
        val rules = SUMHUiAdapter.userRules(snapshot).associateBy { it.src }
        assertEquals(setOf("/deleted", "/saved", "/unregistered"), rules.keys)
        assertFalse(rules.getValue("/deleted").persisted)
        assertEquals(2, rules.getValue("/deleted").hideState)
        assertTrue(rules.getValue("/saved").persisted)
        assertEquals(-13, rules.getValue("/saved").hideError)
        assertEquals(-1, rules.getValue("/unregistered").hideState)
    }

    @Test
    fun legacySnapshotPreservesSavedRulesWithoutInventingBindingStatus() {
        val rules = SUMHUiAdapter.userRules(SUMHManager.RuleSnapshot(JSONObject(), listOf("/saved", "/saved")))
        assertEquals(listOf("/saved"), rules.map { it.src })
        assertTrue(rules.single().persisted)
        assertEquals(null, rules.single().hideState)
    }
}
