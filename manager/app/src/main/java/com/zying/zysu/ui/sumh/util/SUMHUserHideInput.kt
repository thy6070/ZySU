package com.zying.zysu.ui.sumh.util

const val SUMH_HIDE_BATCH_LIMIT = 100

enum class SUMHHidePathError { NotAbsolute, Root, InvalidComponent, ControlCharacter, TooLong, Protected }

/** Validate new definitions without resolving symlinks or rewriting a user's path. */
fun validateSUMHHidePath(path: String): SUMHHidePathError? = when {
    !path.startsWith('/') -> SUMHHidePathError.NotAbsolute
    path == "/" -> SUMHHidePathError.Root
    path.any { it.isISOControl() } -> SUMHHidePathError.ControlCharacter
    path.toByteArray(Charsets.UTF_8).size >= 4096 -> SUMHHidePathError.TooLong
    path.substring(1).split('/').any { it.isEmpty() || it == "." || it == ".." } ->
        SUMHHidePathError.InvalidComponent
    path.substring(1).split('/').any { it.toByteArray(Charsets.UTF_8).size > 255 } ->
        SUMHHidePathError.TooLong
    path == "/system/bin/su" -> SUMHHidePathError.Protected
    else -> null
}

data class SUMHHideImportLine(
    val number: Int,
    val path: String,
    val error: SUMHHidePathError? = null,
    val duplicate: Boolean = false,
)

data class SUMHHideImportPreview(val lines: List<SUMHHideImportLine>) {
    val paths: List<String> get() = lines.filter { it.error == null && !it.duplicate }.map { it.path }
    val canApply: Boolean get() = lines.none { it.error != null } && paths.size in 1..SUMH_HIDE_BATCH_LIMIT
}

/** Empty lines are ignored; all remaining lines keep their original positions for feedback. */
fun previewSUMHHideImport(text: String, existing: Set<String>): SUMHHideImportPreview {
    val seen = existing.toMutableSet()
    return SUMHHideImportPreview(text.lineSequence().mapIndexedNotNull { index, line ->
        val path = line.trim()
        if (path.isEmpty()) return@mapIndexedNotNull null
        val error = validateSUMHHidePath(path)
        SUMHHideImportLine(index + 1, path, error, duplicate = error == null && !seen.add(path))
    }.toList())
}
