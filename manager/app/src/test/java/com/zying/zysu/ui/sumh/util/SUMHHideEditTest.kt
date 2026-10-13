package com.zying.zysu.ui.sumh.util

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SUMHHideEditTest {
    @Test
    fun replacementIsAcceptedBeforeOriginalIsRemoved() = runTest {
        val calls = mutableListOf<String>()
        val result = runSUMHHideEdit("/old", "/new ", { calls += "prepare" },
            { calls += "add:$it" }, { calls += "remove:$it" })
        assertEquals(listOf("prepare", "add:/new ", "remove:/old"), calls)
        assertNull(result.error)
        assertTrue(result.originalRemoved)
    }
    @Test
    fun addFailureLeavesOriginalUntouchedAndReportsPartialPersistence() = runTest {
        var removals = 0
        val result = runSUMHHideEdit("/old", "/new", {}, { error("saved but rejected") }, { removals++ })
        assertEquals(0, removals)
        assertFalse(result.originalRemoved)
        assertEquals("saved but rejected", result.error)
    }
    @Test
    fun deletionFailureRetainsReplacementWithoutRollingBack() = runTest {
        val result = runSUMHHideEdit("/old", "/new", {}, {}, { error("removal failed") })
        assertTrue(result.replacementSaved)
        assertFalse(result.originalRemoved)
        assertEquals("removal failed", result.error)
    }
    @Test
    fun failedPreflightDoesNotCreateAnExtraRuleAndUnchangedPathIsNoop() = runTest {
        var calls = 0
        val rejected = runSUMHHideEdit("/old", "/new", { error("unsupported") }, { calls++ }, { calls++ })
        assertNotNull(rejected.error)
        val unchanged = runSUMHHideEdit("/old", "/old", { calls++ }, { calls++ }, { calls++ })
        assertNull(unchanged.error)
        assertEquals(0, calls)
    }
}
