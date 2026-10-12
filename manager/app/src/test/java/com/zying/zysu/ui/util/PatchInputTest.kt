package com.zying.zysu.ui.util

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PatchInputTest {
    private fun withDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("patch-input-test-").toFile()
        try {
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun providerReturningNullDoesNotLeaveAnEmptyImage() = withDirectory { directory ->
        assertFailsWith<IOException> {
            copyPatchInputToTemporaryFile(directory, "boot-", ".img") { null }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun providerAccessFailureIsPropagatedAndCleansTheTemporaryFile() = withDirectory { directory ->
        val error = assertFailsWith<SecurityException> {
            copyPatchInputToTemporaryFile(directory, "lkm-", ".ko") {
                throw SecurityException("URI permission was revoked")
            }
        }
        assertEquals("URI permission was revoked", error.message)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun emptySelectedFileIsRejectedAndItsStreamIsClosed() = withDirectory { directory ->
        var closed = false
        val input = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        assertFailsWith<IOException> {
            copyPatchInputToTemporaryFile(directory, "boot-", ".img") { input }
        }
        assertTrue(closed)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun failedReadClosesTheProviderStreamAndRemovesPartialImage() = withDirectory { directory ->
        var closed = false
        var firstRead = true
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("provider disconnected")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!firstRead) throw IOException("provider disconnected")
                firstRead = false
                buffer[offset] = 42
                return 1
            }

            override fun close() {
                closed = true
            }
        }
        assertFailsWith<IOException> {
            copyPatchInputToTemporaryFile(directory, "boot-", ".img") { input }
        }
        assertTrue(closed)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun copiesBytesClosesTheStreamAndKeepsConcurrentInputsDistinct() = withDirectory { directory ->
        val expected = ByteArray(20000) { it.toByte() }
        var closed = false
        val input = object : ByteArrayInputStream(expected) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        val first = copyPatchInputToTemporaryFile(directory, "boot-", ".img") { input }
        val second = copyPatchInputToTemporaryFile(directory, "boot-", ".img") {
            byteArrayOf(99).inputStream()
        }
        assertTrue(closed)
        assertNotEquals(first, second)
        assertContentEquals(expected, first.readBytes())
        assertContentEquals(byteArrayOf(99), second.readBytes())
    }
}
