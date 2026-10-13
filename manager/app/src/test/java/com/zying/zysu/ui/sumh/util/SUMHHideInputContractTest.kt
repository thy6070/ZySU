package com.zying.zysu.ui.sumh.util

import kotlin.test.*

class SUMHHideInputContractTest {
    @Test
    fun acceptsControllerSupportedPathsWithoutRewritingThem() {
        for (path in listOf("/", "/system/bin/su", "//data/a", "/data/a/", "/data/./a", "/data/../a",
            "/data/name ", "/data/ space", "/data/tab\t", "/data/new\nline", "/data/配置")) {
            assertNull(validateSUMHHidePath(path), path)
        }
        assertEquals(SUMHHidePathError.NotAbsolute, validateSUMHHidePath("data/a"))
        assertEquals(SUMHHidePathError.NulCharacter, validateSUMHHidePath("/data/a\u0000"))
    }

    @Test
    fun enforcesTheControllerUtf8Limit() {
        assertNull(validateSUMHHidePath("/" + "界".repeat(1364) + "ab"))
        assertEquals(SUMHHidePathError.TooLong, validateSUMHHidePath("/" + "界".repeat(1365)))
    }

    @Test
    fun importPreservesSpacesAndLineNumbersAndSkipsExactDuplicates() {
        val preview = previewSUMHHideImport("\n/data/new \r\n/data/existing\n/data/new \n", setOf("/data/existing"))
        assertEquals(listOf(2, 3, 4), preview.lines.map { it.number })
        assertEquals(listOf(false, true, true), preview.lines.map { it.duplicate })
        assertEquals(listOf("/data/new "), preview.paths)
        assertTrue(preview.canApply)
    }

    @Test
    fun invalidLinesDoNotBlockValidImportsAndLargeListsAreNotTruncated() {
        val mixed = previewSUMHHideImport("/data/good\nrelative", emptySet())
        assertTrue(mixed.canApply)
        assertEquals(listOf("/data/good"), mixed.paths)
        assertNotNull(mixed.lines[1].error)
        assertFalse(previewSUMHHideImport(" \n", emptySet()).canApply)
        assertFalse(previewSUMHHideImport("/data/existing", setOf("/data/existing")).canApply)
        val preview = previewSUMHHideImport((1..250).joinToString("\n") { "/data/$it" }, emptySet())
        assertEquals(250, preview.paths.size)
        assertTrue(preview.canApply)
    }
}
