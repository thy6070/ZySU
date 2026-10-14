package com.zying.zysu.ui.sumh.util

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SUMHRuntimeRulesTest {
    private fun snapshot(raw: String, states: String? = null, saved: List<String> = emptyList()) =
        SUMHManager.RuleSnapshot(JSONObject().put("rules", raw).apply {
            if (states != null) put("user_hide", JSONArray(states))
        }, saved)

    @Test
    fun onlyBoundManagedPathsJoinKernelRulesAndSavedPathsRemainEditable() {
        val snapshot = snapshot("hide /module\n", """[
            {"path":"/bound","management":0,"binding":2,"error":0},
            {"path":"/pending","management":0,"binding":0,"error":-2},
            {"path":"/binding","management":0,"binding":1,"error":0},
            {"path":"/error","management":0,"binding":3,"error":-13},
            {"path":"/suspended","management":1,"binding":2,"error":0},
            {"path":"/live-only","management":0,"binding":2,"error":0}
        ]""", listOf("/bound", "/pending", "/binding", "/error", "/suspended", "/unregistered"))
        val runtime = SUMHUiAdapter.activeRules(snapshot)
        assertEquals(listOf("/module", "/bound", "/live-only"), runtime.rules.map { it.src })
        assertFalse(runtime.incomplete)
        val personal = SUMHUiAdapter.userRules(snapshot).associateBy { it.src }
        assertEquals(7, personal.size)
        assertEquals(-1, personal.getValue("/unregistered").hideState)
        assertFalse(personal.getValue("/live-only").persisted)
    }

    @Test
    fun duplicateBoundPathCountsOnceButIndependentLegacyHideStillCounts() {
        val runtime = SUMHUiAdapter.activeRules(snapshot("hide /bound\nhide /pending\nhide /bound\n", """[
            {"path":"/bound","management":0,"binding":2,"error":0},
            {"path":"/pending","management":0,"binding":0,"error":-2}
        ]"""))
        assertEquals(listOf("/pending", "/bound"), runtime.rules.map { it.src })
        assertFalse(runtime.rules.first().isUserDefined)
        assertTrue(runtime.rules.last().isUserDefined)
    }

    @Test
    fun legacyRulesAreMatchedByExactPathAndNeverCreatedFromSavedConfiguration() {
        val runtime = SUMHUiAdapter.activeRules(snapshot("hide /data/with space \n",
            saved = listOf("/data/with space ", "/saved-only")))
        assertEquals("/data/with space ", runtime.rules.single().src)
        assertTrue(runtime.rules.single().isUserDefined)
        assertNull(runtime.rules.single().hideState)
    }

    @Test
    fun parsingPreservesLiteralPathsAndOnlyRemovesAddTypeSuffix() {
        val rules = SUMHUiAdapter.rules("add /system/My Files/item  /data/adb/module file  1\n" +
            "hide /data/配置\t \nmerge /system/path /data/path \n").rules
        assertEquals("/system/My Files/item ", rules[0].src)
        assertEquals("/data/adb/module file ", rules[0].target)
        assertEquals("/data/配置\t ", rules[1].src)
        assertEquals("/data/path ", rules[2].target)
    }

    @Test
    fun ambiguousUnescapedPathPairIsNotSplitAtAnInventedBoundary() {
        val result = SUMHUiAdapter.rules("add /system/has /slash /data/source 0\n")
        assertEquals("/system/has /slash /data/source", result.rules.single().src)
        assertNull(result.rules.single().target)
        assertTrue(result.rules.single().rawOperands)
        assertFalse(result.incomplete)
    }

    @Test
    fun truncatedAndMalformedRowsNeverBecomeRules() {
        val result = SUMHUiAdapter.rules("SUMH Protocol: 1\nSUMH Enabled: 1\nhide /complete\n" +
            "hide relative\nadd /missing-target 0\nunknown enabled\nhide /truncated")
        assertEquals(listOf("/complete"), result.rules.map { it.src })
        assertTrue(result.incomplete)
        assertTrue(SUMHUiAdapter.rules("hide /only-fragment").rules.isEmpty())
        assertFalse(SUMHUiAdapter.rules("").incomplete)
    }

    @Test
    fun fullKernelBufferIsMarkedPartialEvenWhenItEndsOnARowBoundary() {
        val row = "hide /" + "x".repeat(65528) + "\n"
        assertEquals(65535, row.toByteArray(Charsets.UTF_8).size)
        assertTrue(SUMHUiAdapter.rules(row).incomplete)
    }

    @Test
    fun kernelFeatureRecordsDoNotDependOnSavedFeatureSettings() {
        val result = SUMHUiAdapter.rules("inject /system\nhide_xattr_sb 0000\nmount_hide enabled mode=aggressive\n" +
            "maps_spoof enabled\nstatfs_spoof enabled\nstealth enabled\nkernel_build_spoof enabled\n")
        assertEquals(listOf("mount_hide", "maps_spoof", "statfs_spoof", "stealth", "kernel_build_spoof"), result.rules.map { it.type })
        assertFalse(result.incomplete)
    }

    @Test
    fun malformedManagedSnapshotCannotInventBindingOrLoseSavedPaths() {
        val snapshot = snapshot("hide /module\n", """[
            {"path":"/missing-state"},
            {"path":"/unknown-state","management":0,"binding":9},
            {"path":"/wrong-type","management":"0","binding":"2"},
            {"path":25,"management":0,"binding":2},
            null
        ]""", listOf("/saved", "/missing-state"))
        val runtime = SUMHUiAdapter.activeRules(snapshot)
        assertEquals(listOf("/module"), runtime.rules.map { it.src })
        assertTrue(runtime.incomplete)
        val personal = SUMHUiAdapter.userRules(snapshot).associateBy { it.src }
        assertTrue("/saved" in personal)
        assertNull(personal.getValue("/saved").hideState)
        assertNull(personal.getValue("/missing-state").hideState)
    }

    @Test
    fun structuredSnapshotPreservesNewlinePathsThatLegacyTextCannotRepresent() {
        val path = "/data/line\nbreak \r"
        val state = JSONObject().put("path", path).put("management", 0).put("binding", 2)
        val snapshot = SUMHManager.RuleSnapshot(JSONObject().put("user_hide", JSONArray().put(state)), listOf(path))
        assertEquals(path, SUMHUiAdapter.activeRules(snapshot).rules.single().src)
        assertEquals(path, SUMHUiAdapter.userRules(snapshot).single().src)
    }
}
