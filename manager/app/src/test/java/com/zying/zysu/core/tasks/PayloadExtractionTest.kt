package com.zying.zysu.core.tasks

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PayloadExtractionTest {
    @Test fun replaceScattersBlocksAndVerifiesThePartitionHash() = assertExtracted(0, true)
    @Test fun bzip2ScattersBlocksAndVerifiesThePartitionHash() = assertExtracted(1, true)
    @Test fun xzScattersBlocksAndVerifiesThePartitionHash() = assertExtracted(8, true)
    @Test fun replaceScattersBlocksWithoutAnOptionalHash() = assertExtracted(0, false)
    @Test fun bzip2ScattersBlocksWithoutAnOptionalHash() = assertExtracted(1, false)
    @Test fun xzScattersBlocksWithoutAnOptionalHash() = assertExtracted(8, false)

    @Test
    fun everyEncodingRejectsTruncatedDecodedDataWithoutAHash() {
        for (type in listOf(0, 1, 8)) {
            assertInvalid(payload(type, false, data.copyOf(data.size - 1)))
        }
    }

    @Test
    fun everyEncodingRejectsExcessDecodedDataWithoutAHash() {
        for (type in listOf(0, 1, 8)) {
            assertInvalid(payload(type, false, data + byteArrayOf(99)))
        }
    }

    @Test
    fun destinationExtentsCannotExtendThePartition() {
        assertInvalid(payload(0, false, secondExtentStart = 6))
    }

    private fun assertExtracted(type: Int, withHash: Boolean) {
        val expected = ByteArray(6 * BLOCK_SIZE)
        // Extents deliberately run backwards and leave untouched gaps.
        data.copyInto(expected, 3 * BLOCK_SIZE, 0, BLOCK_SIZE)
        data.copyInto(expected, 0, BLOCK_SIZE, data.size)
        val logs = mutableListOf<String>()
        RemoteImageFixtures.withOutput { output ->
            RemoteImageFixtures.channel(payload(type, withHash)).use { channel ->
                Payload(channel).extract(output, "boot", {}, logs::add)
            }
            assertContentEquals(expected, output.readBytes())
            assertEquals(if (withHash) "Hash verification passed" else "Hash verification skipped", logs.last())
        }
    }

    private fun assertInvalid(bytes: ByteArray) {
        RemoteImageFixtures.withOutput { output ->
            RemoteImageFixtures.channel(bytes).use { channel ->
                assertFailsWith<IOException> { Payload(channel).extract(output, "boot", {}, {}) }
            }
        }
    }

    private fun payload(
        type: Int,
        withHash: Boolean,
        decoded: ByteArray = data,
        secondExtentStart: Int = 0,
    ): ByteArray {
        val expected = ByteArray(6 * BLOCK_SIZE)
        data.copyInto(expected, 3 * BLOCK_SIZE, 0, BLOCK_SIZE)
        data.copyInto(expected, 0, BLOCK_SIZE, data.size)
        val encoded = ByteArrayOutputStream().also { out ->
            when (type) {
                0 -> out.write(decoded)
                1 -> BZip2CompressorOutputStream(out).use { it.write(decoded) }
                8 -> XZCompressorOutputStream(out).use { it.write(decoded) }
            }
        }.toByteArray()
        val partitionInfo = Proto().number(1, expected.size.toLong()).apply {
            if (withHash) bytes(2, MessageDigest.getInstance("SHA-256").digest(expected))
        }
        val operation = Proto().number(1, type.toLong()).number(2, 0)
            .number(3, encoded.size.toLong())
            .message(6, Proto().number(1, 3).number(2, 1))
            .message(6, Proto().number(1, secondExtentStart.toLong()).number(2, 2))
        val zero = Proto().number(1, 6)
            .message(6, Proto().number(1, 2).number(2, 1))
            .message(6, Proto().number(1, 4).number(2, 2))
        val partition = Proto().bytes(1, "boot".toByteArray())
            .message(7, partitionInfo).message(8, operation).message(8, zero)
        val manifest = Proto().number(3, BLOCK_SIZE.toLong()).message(13, partition).toByteArray()
        return ByteArrayOutputStream().apply {
            write("CrAU".toByteArray())
            write(ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
                .putLong(2).putLong(manifest.size.toLong()).putInt(1).array())
            write(manifest)
            write(0) // Metadata signature area; extraction does not authenticate it.
            write(encoded)
        }.toByteArray()
    }

    private class Proto {
        private val output = ByteArrayOutputStream()
        private fun varint(value: Long) {
            var remaining = value
            while (remaining > 127) {
                output.write((remaining.toInt() and 127) or 128)
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }
        fun number(field: Int, value: Long) = apply {
            varint((field shl 3).toLong())
            varint(value)
        }
        fun bytes(field: Int, value: ByteArray) = apply {
            varint(((field shl 3) or 2).toLong())
            varint(value.size.toLong())
            output.write(value)
        }
        fun message(field: Int, value: Proto) = bytes(field, value.toByteArray())
        fun toByteArray(): ByteArray = output.toByteArray()
    }

    companion object {
        private const val BLOCK_SIZE = 4096
        private val data = ByteArray(3 * BLOCK_SIZE) { ((it / BLOCK_SIZE) * 31 + it % 251).toByte() }
    }
}
