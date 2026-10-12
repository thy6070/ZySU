package com.zying.zysu.superkey

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SuperKeyValidationTest {
    @Test
    fun asciiKeysMustFitTheKernelFieldWithoutTruncation() {
        assertEquals(64, encodeSuperKey("a".repeat(64))?.size)
        assertNull(encodeSuperKey("a".repeat(65)))
    }

    @Test
    fun unicodeLimitCountsUtf8BytesInsteadOfCharacters() {
        assertEquals(64, encodeSuperKey("密".repeat(21) + "a")?.size)
        assertNull(encodeSuperKey("密".repeat(22)))
        assertEquals(64, encodeSuperKey("🔑".repeat(16))?.size)
        assertNull(encodeSuperKey("🔑".repeat(17)))
    }

    @Test
    fun supplementaryCharactersUseStandardUtf8ForAuthentication() {
        assertContentEquals(
            byteArrayOf(0xf0.toByte(), 0x9f.toByte(), 0x94.toByte(), 0x91.toByte()),
            encodeSuperKey("🔑"),
        )
    }

    @Test
    fun nullCharactersAndMalformedUnicodeCannotBeSilentlyChanged() {
        assertNull(encodeSuperKey("key\u0000suffix"))
        assertNull(encodeSuperKey("key\uD800"))
        assertNull(encodeSuperKey("\uDC00key"))
    }

    @Test
    fun emptyKeyRemainsAvailableForSignatureOnlyPatching() {
        assertContentEquals(byteArrayOf(), encodeSuperKey(""))
    }

    @Test
    fun whitespaceOnlyKeysAreNonemptyKernelCredentials() {
        assertContentEquals(
            byteArrayOf(0x20, 0x09, 0xe3.toByte(), 0x80.toByte(), 0x80.toByte()),
            encodeSuperKey(" \t\u3000"),
        )
    }
}
