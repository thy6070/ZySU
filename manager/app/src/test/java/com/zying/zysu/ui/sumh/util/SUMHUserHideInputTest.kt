package com.zying.zysu.ui.sumh.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SUMHUserHideInputTest {
    @Test
    fun rejectsRootAmbiguousComponentsControlsAndProtectedPath() {
        assertEquals(SUMHHidePathError.Root, validateSUMHHidePath("/"))
        assertEquals(SUMHHidePathError.NotAbsolute, validateSUMHHidePath("data/a"))
        for (path in listOf("//data/a", "/data/a/", "/data/./a", "/data/../a")) {
            assertEquals(SUMHHidePathError.InvalidComponent, validateSUMHHidePath(path), path)
        }
        for (path in listOf("/data/a\u0000", "/data/a\n", "/data/a\t")) {
            assertEquals(SUMHHidePathError.ControlCharacter, validateSUMHHidePath(path))
        }
        assertEquals(SUMHHidePathError.Protected, validateSUMHHidePath("/system/bin/su"))
        assertNull(validateSUMHHidePath("/storage/emulated/0/My Files/配置"))
    }

    @Test
    fun enforcesUtf8ComponentAndTotalPathLimits() {
        assertNull(validateSUMHHidePath("/" + "界".repeat(85)))
        assertEquals(SUMHHidePathError.TooLong, validateSUMHHidePath("/" + "界".repeat(86)))
        val prefix = (1..15).joinToString("", postfix = "/") { "/" + "a".repeat(255) }
        assertNull(validateSUMHHidePath(prefix + "a".repeat(254)))
        assertEquals(SUMHHidePathError.TooLong, validateSUMHHidePath(prefix + "a".repeat(255)))
    }

    @Test
    fun previewKeepsLineNumbersAndSkipsExistingAndRepeatedPaths() {
        val preview = previewSUMHHideImport("\n /data/new \r\n/data/existing\n/data/new\n", setOf("/data/existing"))
        assertEquals(listOf(2, 3, 4), preview.lines.map { it.number })
        assertEquals(listOf(false, true, true), preview.lines.map { it.duplicate })
        assertEquals(listOf("/data/new"), preview.paths)
        assertTrue(preview.canApply)
    }

    @Test
    fun invalidLinesBlockTheWholePreviewAndNothingIsSilentlyTruncated() {
        assertFalse(previewSUMHHideImport("/data/good\nrelative", emptySet()).canApply)
        assertFalse(previewSUMHHideImport(" \n", emptySet()).canApply)
        assertFalse(previewSUMHHideImport("/data/existing", setOf("/data/existing")).canApply)
        val text = (1..SUMH_HIDE_BATCH_LIMIT).joinToString("\n") { "/data/$it" }
        assertTrue(previewSUMHHideImport(text, emptySet()).canApply)
        val overflow = previewSUMHHideImport("$text\n/data/overflow", emptySet())
        assertEquals(101, overflow.paths.size)
        assertFalse(overflow.canApply)
    }
}
