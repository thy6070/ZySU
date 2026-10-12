package com.zying.zysu.ui.sumh.util

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SUMHHideRemovalTest {
    @Test
    fun managedRemovalAcceptsSavedAndLiveOnlyUserRulesButRejectsModuleRules() {
        val kernel = JSONObject("""{"sumh_available":true,"user_hide":[{"path":"/live"}],
            "rules":"add /module /target 0\nhide /module\n"}""")
        requireSUMHHideRemoval("/saved", setOf("/saved"), kernel)
        requireSUMHHideRemoval("/live", emptySet(), kernel)
        assertFailsWith<IllegalStateException> {
            requireSUMHHideRemoval("/module", emptySet(), kernel)
        }
    }

    @Test
    fun legacyDumpsCannotAuthorizeDestructiveFallback() {
        // Even an apparently safe hide line cannot prove ownership across path aliases.
        for (dump in listOf("hide /saved\n", "add /saved /module 0\nhide /saved\n",
            "inject /saved\n", "merge /saved /module\n", "hide /name with spaces\n",
            "x".repeat(65535), "")) {
            val kernel = JSONObject().put("sumh_available", true).put("rules", dump)
            assertFailsWith<IllegalStateException> {
                requireSUMHHideRemoval("/saved", setOf("/saved"), kernel)
            }
        }
    }

    @Test
    fun unavailableKernelAllowsSavedConfigurationCleanupOnly() {
        val kernel = JSONObject().put("sumh_available", false)
        requireSUMHHideRemoval("/saved", setOf("/saved"), kernel)
        assertFailsWith<IllegalStateException> {
            requireSUMHHideRemoval("/module", emptySet(), kernel)
        }
    }

    @Test
    fun recheckingEachItemPreventsFallbackAfterLastManagedRegistration() = runTest {
        val kernel = JSONObject().put("sumh_available", true)
            .put("user_hide", JSONArray("""[{"path":"/first"}]"""))
        val removed = mutableListOf<String>()
        val result = runSUMHHideBatch(SUMHHideAction.Remove, listOf("/first", "/second")) { path ->
            requireSUMHHideRemoval(path, setOf("/first", "/second"), kernel)
            removed += path
            kernel.remove("user_hide")
        }
        assertEquals(listOf("/first"), removed)
        assertTrue(result.items.first().succeeded)
        assertFalse(result.items.last().succeeded)
    }
}
