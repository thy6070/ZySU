package com.zying.zysu.ui.sumh.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
}
