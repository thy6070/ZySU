package com.zying.zysu.core.tasks

import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExtractImageTest {
    @Test
    fun storedBootIsProbedBeforeItsLocalHeaderHasBeenOpened() {
        val boot = RemoteImageFixtures.bootImage()
        val initBoot = byteArrayOf(1, 2, 3)
        val archive = RemoteImageFixtures.zip("boot.img" to boot, "init_boot.img" to initBoot)
        RemoteImageFixtures.channel(archive).use { channel ->
            val result = ExtractImage.probe(channel)
            assertEquals(listOf("boot", "init_boot"), result.partitions)
            assertEquals("android15-6.6", result.kmi)
        }
        assertExtractedImage(archive, "boot", boot)
        assertExtractedImage(archive, "init_boot", initBoot)
    }

    @Test
    fun storedBootInsideFactoryImageZipUsesTheInnerDataOffset() {
        val boot = RemoteImageFixtures.bootImage()
        val inner = RemoteImageFixtures.zip("boot.img" to boot)
        val archive = RemoteImageFixtures.zip(
            "factory/readme.txt" to "firmware".toByteArray(),
            "factory/image-device-test.zip" to inner,
        )
        RemoteImageFixtures.channel(archive).use { channel ->
            val result = ExtractImage.probe(channel)
            assertEquals(listOf("boot"), result.partitions)
            assertEquals("android15-6.6", result.kmi)
        }
        assertExtractedImage(archive, "boot", boot)
    }

    @Test
    fun deflatedBootKeepsManualKmiFallbackAndStillExtractsCorrectly() {
        val boot = RemoteImageFixtures.bootImage()
        val archive = RemoteImageFixtures.zip("boot.img" to boot, method = ZipEntry.DEFLATED)
        RemoteImageFixtures.channel(archive).use { channel ->
            val result = ExtractImage.probe(channel)
            assertEquals(listOf("boot"), result.partitions)
            assertNull(result.kmi)
        }
        assertExtractedImage(archive, "boot", boot)
    }

    private fun assertExtractedImage(archive: ByteArray, partition: String, expected: ByteArray) {
        RemoteImageFixtures.withOutput { output ->
            RemoteImageFixtures.channel(archive).use { channel ->
                ExtractImage(output) {}.consume(channel, partition)
            }
            assertContentEquals(expected, output.readBytes())
        }
    }
}
