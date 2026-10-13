package com.zying.zysu.ui.sumh.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SUMHHideBatchTest {
    @Test
    fun recordsPartialFailuresAndContinuesInInputOrderWithoutRepeatingPaths() = runTest {
        val visited = mutableListOf<String>()
        val result = runSUMHHideBatch(SUMHHideAction.Add, listOf("/a", "/b", "/a", "/c")) { path ->
            visited += path
            if (path == "/b") error("saved, but kernel rejected it")
        }
        assertEquals(listOf("/a", "/b", "/c"), visited)
        assertEquals(visited, result.items.map { it.path })
        assertTrue(result.items[0].succeeded)
        assertFalse(result.items[1].succeeded)
        assertEquals("saved, but kernel rejected it", result.items[1].error)
        assertTrue(result.items[2].succeeded)
    }

    @Test
    fun validatesAllPathsBeforeAnyMutation() = runTest {
        var calls = 0
        for (paths in listOf(emptyList(), listOf("/good", "relative"))) {
            assertFailsWith<IllegalArgumentException> {
                runSUMHHideBatch(SUMHHideAction.Add, paths) { calls++ }
            }
        }
        assertEquals(0, calls)
    }

    @Test
    fun largeBatchesReportProgressAndCanStopBeforeTheNextItem() = runTest {
        val paths = (1..150).map { "/data/$it" }
        val progress = mutableListOf<Int>()
        var calls = 0
        val result = runSUMHHideBatch(SUMHHideAction.Add, paths,
            shouldContinue = { calls < 102 }, onProgress = { done, total ->
                assertEquals(150, total); progress += done
            }) { calls++ }
        assertEquals(102, calls)
        assertEquals((0..102).toList(), progress)
        assertEquals(102, result.items.count { it.succeeded })
        assertTrue(result.items.drop(102).all { it.error == "Not attempted: stopped by user" })
    }

    @Test
    fun existingLegacyPathsRemainRemovable() = runTest {
        val paths = listOf("/data/legacy/", "/system/bin/su")
        val result = runSUMHHideBatch(SUMHHideAction.Remove, paths) { }
        assertTrue(result.items.all { it.succeeded })
        assertEquals(paths, result.items.map { it.path })
    }

    @Test
    fun transportFailureStopsFurtherRequestsAndKeepsEveryPathInTheResult() = runTest {
        val visited = mutableListOf<String>()
        val result = runSUMHHideBatch(SUMHHideAction.Add, listOf("/a", "/b", "/c", "/d")) { path ->
            visited += path
            if (path == "/b") throw IOException("Controller timeout; outcome unknown")
        }
        assertEquals(listOf("/a", "/b"), visited)
        assertEquals(listOf("/a", "/b", "/c", "/d"), result.items.map { it.path })
        assertTrue(result.items.first().succeeded)
        assertEquals("Controller timeout; outcome unknown", result.items[1].error)
        assertTrue(result.items.drop(2).all { it.error?.startsWith("Not attempted") == true })
    }

    @Test
    fun cancellationIsNotReportedAsAPerItemFailure() = runTest {
        var calls = 0
        assertFailsWith<CancellationException> {
            runSUMHHideBatch(SUMHHideAction.Retry, listOf("/a", "/b")) {
                calls++
                throw CancellationException()
            }
        }
        assertEquals(1, calls)
    }
}
