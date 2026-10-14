package com.zying.zysu.ui.sumh.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SUMHMapsRuleInputTest {
    @Test
    fun acceptsFullUnsignedDecimalRange() {
        assertEquals(0uL, parseSUMHMapsNumber("0"))
        assertEquals(ULong.MAX_VALUE, parseSUMHMapsNumber("18446744073709551615"))
        assertEquals(ULong.MAX_VALUE, parseSUMHMapsNumber(" 18446744073709551615 "))
        assertEquals(16uL, parseSUMHMapsNumber("00016"))
    }

    @Test
    fun rejectsNegativeMalformedAndOverflowValues() {
        for (value in listOf("", " ", "-1", "+1", "1.2", "abc", "0x", "0x10", "1 2", "١", "18446744073709551616")) {
            assertNull(parseSUMHMapsNumber(value), value)
        }
    }

    @Test
    fun rejectsInvalidDeviceInsteadOfSilentlyMatchingAllDevices() {
        assertNull(parseSUMHMapsRuleInput("1", "oops", "2", "0", "/system/lib64/lib.so"))
        assertNull(parseSUMHMapsRuleInput("1", "0", "2", "", "/system/lib64/lib.so"))
        assertNotNull(parseSUMHMapsRuleInput("0", "0", "0", "0", "/"))
        assertNotNull(parseSUMHMapsRuleInput("1", "4294967295", "2", "4294967295", "/file"))
        assertNull(parseSUMHMapsRuleInput("1", "4294967296", "2", "0", "/file"))
        assertNull(parseSUMHMapsRuleInput("1", "0", "2", "4294967296", "/file"))
    }

    @Test
    fun preservesUnsignedBitPatternAndPathForJni() {
        val input = assertNotNull(parseSUMHMapsRuleInput("18446744073709551615", "0", "9223372036854775808", "16", "/system/lib file.so"))
        assertEquals(-1L, input.targetInode)
        assertEquals(Long.MIN_VALUE, input.spoofedInode)
        assertEquals(16L, input.spoofedDevice)
        assertEquals("/system/lib file.so", input.path)
    }

    @Test
    fun rejectsRelativeAndControlCharacterPaths() {
        assertEquals(SUMHMapsPathError.AbsolutePath, validateSUMHMapsPath(""))
        assertEquals(SUMHMapsPathError.AbsolutePath, validateSUMHMapsPath("relative.so"))
        for (path in listOf("/file\u0000suffix", "/file\nnext", "/file\t", "/file\r")) {
            assertEquals(SUMHMapsPathError.ControlCharacter, validateSUMHMapsPath(path))
        }
        assertNull(validateSUMHMapsPath("/file name.so"))
        assertNull(validateSUMHMapsPath("/"))
    }

    @Test
    fun enforcesTerminatedUtf8BufferInsteadOfCharacterCount() {
        assertNull(validateSUMHMapsPath("/" + "a".repeat(254)))
        assertEquals(SUMHMapsPathError.TooLong, validateSUMHMapsPath("/" + "a".repeat(255)))
        assertNull(validateSUMHMapsPath("/" + "中".repeat(84) + "ab"))
        assertEquals(SUMHMapsPathError.TooLong, validateSUMHMapsPath("/" + "中".repeat(85)))
    }

    @Test
    fun pathRulesUseDifferentTargetAndReplacementLimits() {
        assertTrue(validateSUMHMapsPathRule("/" + "a".repeat(4094), "/" + "中".repeat(84) + "ab", emptyList()).valid)
        assertEquals(SUMHMapsPathError.TooLong,
            validateSUMHMapsPathRule("/" + "中".repeat(1365), "/replacement", emptyList()).targetError)
        assertEquals(SUMHMapsPathError.TooLong,
            validateSUMHMapsPathRule("/target", "/" + "a".repeat(255), emptyList()).replacementError)
        for (path in listOf("relative", "/file\u0000suffix", "/file\nnext", "/file\r")) {
            assertFalse(validateSUMHMapsPathRule(path, "/replacement", emptyList()).valid)
            assertFalse(validateSUMHMapsPathRule("/target", path, emptyList()).valid)
        }
    }

    @Test
    fun pathRuleDuplicatesAndLimitAreLiteral() {
        assertTrue(validateSUMHMapsPathRule("/target\t", "/replacement\t", emptyList()).valid)
        assertTrue(validateSUMHMapsPathRule("/target ", "/replacement ", listOf("/target")).valid)
        assertTrue(validateSUMHMapsPathRule("/target", "/replacement", listOf("/target")).duplicateTarget)
        assertFalse(validateSUMHMapsPathRule("/target", "/replacement", listOf("/target")).valid)
        assertFalse(validateSUMHMapsPathRule("/new", "/replacement", List(256) { "/$it" }).valid)
        assertTrue(validateSUMHMapsPathRule("/new", "/replacement", List(255) { "/$it" }).valid)
    }

    @Test
    fun managedMapsRequiresItsOwnExplicitCapability() {
        assertEquals(SUMHFeatureSupport.Unsupported, sumhManagedMapsSupport(true, setOf("maps_spoof")))
        assertEquals(SUMHFeatureSupport.Supported, sumhManagedMapsSupport(true, setOf("managed_maps")))
        assertEquals(SUMHFeatureSupport.Unknown, sumhManagedMapsSupport(true, null))
        assertEquals(SUMHFeatureSupport.KernelUnavailable, sumhManagedMapsSupport(false, setOf("managed_maps")))
    }

    @Test
    fun editingExcludesOnlyItsOriginalTargetFromDuplicatesAndCount() {
        val targets = List(256) { "/$it" }
        assertTrue(validateSUMHMapsPathRule("/0", "/changed ", targets, "/0").valid)
        assertTrue(validateSUMHMapsPathRule("/new", "/changed", targets, "/0").valid)
        assertTrue(validateSUMHMapsPathRule("/1", "/changed", targets, "/0").duplicateTarget)
        assertFalse(validateSUMHMapsPathRule("/new", "/changed", targets).valid)
    }
}
