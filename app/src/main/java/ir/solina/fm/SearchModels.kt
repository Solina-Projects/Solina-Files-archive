package ir.solina.fm

/** Supported file categories for advanced search. */
enum class FileType {
    ALL, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK
}

/**
 * Immutable filters for a filesystem search.
 *
 * A blank query with the default filters is considered empty. A maximum-size-only
 * filter is supported too, so isEmpty checks every criterion rather than just min size.
 */
data class SearchCriteria(
    val queryText: String = "",
    val fileTypes: Set<FileType> = setOf(FileType.ALL),
    val minSizeBytes: Long = 0L,
    val maxSizeBytes: Long = Long.MAX_VALUE,
    val modifiedSinceDays: Int? = null,
    val searchInSubfolders: Boolean = true
) {
    fun isEmpty(): Boolean =
        queryText.isBlank() &&
            (fileTypes.isEmpty() || FileType.ALL in fileTypes) &&
            minSizeBytes <= 0L &&
            maxSizeBytes == Long.MAX_VALUE &&
            modifiedSinceDays == null
}
