package com.zying.zysu.ui.util

import java.io.File
import java.io.IOException
import java.io.InputStream

/** Copies a selected document without leaving a partial image on failure. */
internal fun copyPatchInputToTemporaryFile(
    directory: File,
    prefix: String,
    suffix: String,
    openInput: () -> InputStream?,
): File {
    val destination = File.createTempFile(prefix, suffix, directory)
    try {
        val input = openInput() ?: throw IOException("Cannot open the selected file")
        input.use { source ->
            destination.outputStream().use(source::copyTo)
        }
        if (destination.length() == 0L) {
            throw IOException("The selected file is empty")
        }
        return destination
    } catch (error: Exception) {
        destination.delete()
        throw error
    }
}
