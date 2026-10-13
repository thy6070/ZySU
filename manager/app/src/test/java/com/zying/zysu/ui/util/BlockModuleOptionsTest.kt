package com.zying.zysu.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlockModuleOptionsTest {
    @Test
    fun uncheckedNeverPassesDetectedOrInvalidNames() {
        assertEquals("", effectiveBlockModules(false, "mkp,vr"))
        assertEquals("", effectiveBlockModules(false, "mkp; reboot"))
        assertEquals("mkp,vr", effectiveBlockModules(true, "mkp,vr"))
        assertNull(effectiveBlockModules(true, "mkp; reboot"))
    }

    @Test
    fun suggestionsContainOnlyLoadedKnownModules() {
        assertEquals("mkp,vr", suggestedBlockModules("mkp 20 0 - Live 0\nunknown 10 0\nvr 12 0\nmkp 20 0"))
        assertEquals("", suggestedBlockModules("permission denied"))
    }

    @Test
    fun normalizationRejectsInjectionAndTruncation() {
        assertEquals("vr,mkp", normalizeBlockModules(" vr, mkp,vr "))
        assertEquals("", normalizeBlockModules("  "))
        for (input in listOf(",mkp", "mkp,", "vr,,mkp", "vr\nmkp", "a-b", "$(reboot)", "'", "模块", "x".repeat(65))) {
            assertNull(normalizeBlockModules(input), input)
        }
        val maximum = listOf("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(60)).joinToString(",")
        assertEquals(maximum, normalizeBlockModules(maximum))
        assertNull(normalizeBlockModules(maximum + "d"))
    }
}
