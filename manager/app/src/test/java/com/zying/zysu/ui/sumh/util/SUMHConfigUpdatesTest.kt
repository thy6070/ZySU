package com.zying.zysu.ui.sumh.util

import android.app.Application
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SUMHConfigUpdatesTest {
    private fun configWithLegacyMode() = SUMHUiAdapter.SUMHPConfig(
        original = JSONObject().put("mount_hide_mode", "aggressive"),
        kernelAvailable = true,
    )

    @Test
    fun unchangedConfigurationWithLegacyModeHasNoUpdates() {
        assertEquals(0, SUMHUiAdapter.configUpdates(configWithLegacyMode()).length())
    }

    @Test
    fun mountAndLogEditsKeepTheSavedMountHideMode() {
        val updates = SUMHUiAdapter.configUpdates(configWithLegacyMode().copy(mountBackend = "magic", debug = true))
        assertEquals(setOf("mount_backend", "debug"), updates.keys().asSequence().toSet())
        val plan = planSUMHConfigSave(updates.keys().asSequence().toSet(), true)
        assertFalse(plan.applyRuntime)
        assertEquals(SUMHConfigEffects(rebootRequired = true), plan.effects)
    }

    @Test
    fun togglingMountHidingSavesTheNormalMode() {
        val updates = SUMHUiAdapter.configUpdates(configWithLegacyMode().copy(enableMountHide = true))
        assertEquals(setOf("enable_mount_hide", "mount_hide_mode"), updates.keys().asSequence().toSet())
        assertEquals("normal", updates.getString("mount_hide_mode"))
        assertEquals(true, updates.getBoolean("enable_mount_hide"))
    }

    @Test
    fun kernelBuildStageAndFieldsAreRuntimeChanges() {
        val updates = SUMHUiAdapter.configUpdates(configWithLegacyMode().copy(
            kernelBuildRelease = "6.1.75-custom",
            kernelBuildApplyStage = "boot-completed",
        ))
        assertEquals(setOf("kernel_build_release", "kernel_build_apply_stage"), updates.keys().asSequence().toSet())
        assertEquals(SUMHConfigEffects(runtimeRequired = true), sumhConfigEffects(updates.keys().asSequence().toSet()))
    }

    @Test
    fun missingCapabilitiesRemainUnknownWhileEmptyCapabilitiesAreKnown() {
        assertNull(SUMHUiAdapter.parseFeatures(JSONObject().optJSONObject("features")))
        assertNull(SUMHUiAdapter.parseFeatures(JSONObject()))
        assertNull(SUMHUiAdapter.parseFeatures(JSONObject("""{"bitmask":0}""")))
        val empty = assertNotNull(SUMHUiAdapter.parseFeatures(JSONObject("""{"bitmask":0,"names":[]}""")))
        assertEquals(0, empty.bitmask)
        assertEquals(emptyList(), empty.names)
        val supported = assertNotNull(SUMHUiAdapter.parseFeatures(JSONObject("""{"bitmask":64,"names":["mount_hide"]}""")))
        assertEquals(64, supported.bitmask)
        assertEquals(listOf("mount_hide"), supported.names)
    }
}
