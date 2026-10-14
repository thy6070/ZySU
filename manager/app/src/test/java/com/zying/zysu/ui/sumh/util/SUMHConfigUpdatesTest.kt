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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun managedMapsRoundTripPreservesLiteralPathsAndOnlyDiffsChangedKeys() {
        val raw = JSONObject("""{"maps_rules":[{"target_path":"/data/file ","replacement_path":"/system/file "}]}""")
        val config = SUMHUiAdapter.config(raw, setOf("managed_maps"), true, "")
        assertEquals(listOf(SUMHUiAdapter.MapsPathRule("/data/file ", "/system/file ")), config.mapsRules)
        assertEquals(0, SUMHUiAdapter.configUpdates(config).length())
        val unrelated = SUMHUiAdapter.configUpdates(config.copy(debug = true))
        assertEquals(setOf("debug"), unrelated.keys().asSequence().toSet())
        val added = config.copy(mapsRules = config.mapsRules + SUMHUiAdapter.MapsPathRule("/data/second", "/system/second"))
        val updates = SUMHUiAdapter.configUpdates(added)
        assertEquals(setOf("maps_rules"), updates.keys().asSequence().toSet())
        assertEquals(added.mapsRules, SUMHUiAdapter.config(updates, emptySet(), false, "").mapsRules)
        assertEquals(SUMHConfigEffects(runtimeRequired = true), sumhConfigEffects(updates.keys().asSequence().toSet()))
        assertEquals(0, SUMHUiAdapter.configUpdates(config.copy(mapsRules = emptyList())).getJSONArray("maps_rules").length())
    }

    @Test
    fun managedMapsDiffRejectsInvalidDefinitionsBeforeSaving() {
        val config = configWithLegacyMode()
        val valid = SUMHUiAdapter.MapsPathRule("/target", "/replacement")
        for (rules in listOf(
            listOf(valid, valid.copy(replacementPath = "/other")),
            listOf(valid.copy(targetPath = "relative")),
            listOf(valid.copy(replacementPath = "/file\nnext")),
            listOf(valid.copy(replacementPath = "/" + "a".repeat(255))),
            List(257) { valid.copy(targetPath = "/$it") },
        )) assertFailsWith<IllegalArgumentException> { SUMHUiAdapter.configUpdates(config.copy(mapsRules = rules)) }
    }

    @Test
    fun managedMapsBudgetIncludesUtf8AndBothJsonEscapeLayers() {
        val plain = List(256) { SUMHUiAdapter.MapsPathRule("/$it/" + "a".repeat(60), "/replacement") }
        val escaped = List(256) { SUMHUiAdapter.MapsPathRule("/$it/" + "\"".repeat(60), "/replacement") }
        assertTrue(mapsRulesWithinBudget(plain))
        assertFalse(mapsRulesWithinBudget(escaped))
        assertFailsWith<IllegalArgumentException> { SUMHUiAdapter.configUpdates(configWithLegacyMode().copy(mapsRules = escaped)) }
        val fixture = listOf(SUMHUiAdapter.MapsPathRule("/中/\"\\\t", "/plain"))
        val serialized = mapsRulesJson(fixture).toString()
        assertEquals(JSONObject.quote(serialized).toByteArray(Charsets.UTF_8).size + 1, mapsRulesEncodedBytes(fixture))
        assertTrue(mapsRulesEncodedBytes(fixture) > serialized.toByteArray(Charsets.UTF_8).size)
        // Shared C++ fixture pins Android slash escaping, the second layer and daemon comma spaces.
        assertEquals(84, mapsRulesEncodedBytes(fixture))
        assertEquals(4, mapsRulesEncodedBytes(emptyList()))
    }

    @Test
    fun managedMapsBudgetAcceptsExactLimitAndRejectsNextByte() {
        val base = List(14) { SUMHUiAdapter.MapsPathRule("/" + "a".repeat(3890) + it.toString().padStart(4, '0'), "/r") }
        val last = SUMHUiAdapter.MapsPathRule("/", "/replacement")
        val remaining = SUMH_MAX_MAPS_ENCODED_BYTES - mapsRulesEncodedBytes(base + last)
        assertTrue(remaining in 0..4094)
        val atLimit = base + last.copy(targetPath = "/" + "z".repeat(remaining))
        assertEquals(SUMH_MAX_MAPS_ENCODED_BYTES, mapsRulesEncodedBytes(atLimit))
        assertTrue(mapsRulesWithinBudget(atLimit))
        assertEquals(15, SUMHUiAdapter.configUpdates(configWithLegacyMode().copy(mapsRules = atLimit)).getJSONArray("maps_rules").length())
        val over = atLimit.dropLast(1) + atLimit.last().copy(targetPath = atLimit.last().targetPath + "z")
        assertFalse(mapsRulesWithinBudget(over))
        assertFailsWith<IllegalArgumentException> { SUMHUiAdapter.configUpdates(configWithLegacyMode().copy(mapsRules = over)) }
        val edited = assertNotNull(updateSUMHMapsPathRule(atLimit, atLimit.last().targetPath,
            atLimit.last().copy(replacementPath = "/short")))
        assertTrue(mapsRulesWithinBudget(edited))
        assertEquals(atLimit.size, edited.size)
    }

    @Test
    fun editingAPathRuleReplacesOneEntryInOrderWithoutDeletingOthers() {
        val rules = List(256) { SUMHUiAdapter.MapsPathRule("/$it", "/replacement-$it") }
        val replacement = SUMHUiAdapter.MapsPathRule("/edited ", "/new ")
        val edited = assertNotNull(updateSUMHMapsPathRule(rules, "/128", replacement))
        assertEquals(rules.take(128), edited.take(128))
        assertEquals(replacement, edited[128])
        assertEquals(rules.drop(129), edited.drop(129))
        assertEquals(256, edited.size)
        assertEquals(256, SUMHUiAdapter.configUpdates(configWithLegacyMode().copy(mapsRules = edited)).getJSONArray("maps_rules").length())
        assertNull(updateSUMHMapsPathRule(rules, "/removed", replacement))
    }
}
