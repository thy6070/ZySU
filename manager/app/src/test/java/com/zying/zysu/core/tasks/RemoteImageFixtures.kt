package com.zying.zysu.core.tasks

import com.zying.zysu.core.utils.DataSourceChannel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object RemoteImageFixtures {
    fun channel(data: ByteArray): DataSourceChannel {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            if (request.method == "HEAD") {
                response.code(200).message("OK")
                    .header("Content-Length", data.size.toString())
                    .header("Accept-Ranges", "bytes")
                    .body(byteArrayOf().toResponseBody()).build()
            } else {
                val range = checkNotNull(request.header("Range"))
                    .removePrefix("bytes=").split('-').map(String::toInt)
                check(range[0] >= 0 && range[1] < data.size && range[0] <= range[1])
                response.code(206).message("Partial Content")
                    .header("Content-Range", "bytes ${range[0]}-${range[1]}/${data.size}")
                    .body(data.copyOfRange(range[0], range[1] + 1)
                        .toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
        }.build()
        return DataSourceChannel(client, "https://fixture.invalid/firmware.zip")
    }

    fun bootImage(): ByteArray = ByteArray(8192).also { image ->
        ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("ANDROID!".toByteArray())
            putInt(8, 4096)
            putInt(20, 1580)
            putInt(40, 3)
        }
        "Linux version 6.6.30-android15-8-test\u0000".toByteArray().copyInto(image, 4096)
    }

    fun zip(vararg entries: Pair<String, ByteArray>, method: Int = ZipEntry.STORED): ByteArray {
        val result = ByteArrayOutputStream()
        ZipOutputStream(result).use { output ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name).apply {
                    this.method = method
                    if (method == ZipEntry.STORED) {
                        size = data.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(data) }.value
                    }
                }
                output.putNextEntry(entry)
                output.write(data)
                output.closeEntry()
            }
        }
        return result.toByteArray()
    }

    fun withOutput(test: (File) -> Unit) {
        val output = Files.createTempFile("remote-image-test-", ".img").toFile()
        try {
            test(output)
        } finally {
            output.delete()
        }
    }
}
