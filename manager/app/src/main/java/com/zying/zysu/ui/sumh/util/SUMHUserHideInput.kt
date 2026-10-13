package com.zying.zysu.ui.sumh.util

enum class SUMHHidePathError { NotAbsolute, NulCharacter, TooLong }

/** Match the controller's storage contract; path resolution belongs to the kernel. */
fun validateSUMHHidePath(path: String): SUMHHidePathError? = when {
    !path.startsWith('/') -> SUMHHidePathError.NotAbsolute
    '\u0000' in path -> SUMHHidePathError.NulCharacter
    path.toByteArray(Charsets.UTF_8).size >= 4096 -> SUMHHidePathError.TooLong
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
    val canApply: Boolean get() = paths.isNotEmpty()
}

/** Empty lines are ignored; all remaining lines keep their original positions for feedback. */
fun previewSUMHHideImport(text: String, existing: Set<String>): SUMHHideImportPreview {
    val seen = existing.toMutableSet()
    return SUMHHideImportPreview(text.lineSequence().mapIndexedNotNull { index, line ->
        // Preserve filename spaces. Blank lines are separators, not definitions.
        val path = line
        if (path.isBlank()) return@mapIndexedNotNull null
        val error = validateSUMHHidePath(path)
        SUMHHideImportLine(index + 1, path, error, duplicate = error == null && !seen.add(path))
    }.toList())
}
