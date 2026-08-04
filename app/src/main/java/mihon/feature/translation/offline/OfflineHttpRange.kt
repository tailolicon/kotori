package mihon.feature.translation.offline

/**
 * Pure helpers for Range / Content-Range decisions during GGUF download resume.
 * Extracted so unit tests can cover offset validation without OkHttp.
 */
object OfflineHttpRange {

    data class ContentRange(
        val start: Long,
        val end: Long,
        val total: Long?,
    )

    sealed class ResumePlan {
        /** Append body bytes starting at [fileOffset] (must equal Content-Range start). */
        data class Append(val fileOffset: Long, val total: Long?) : ResumePlan()

        /**
         * Do not write this response body. Close it and issue a fresh full GET (no Range).
         * Used when 206 is malformed or the server started at the wrong offset.
         */
        data object RetryFullGet : ResumePlan()

        /** Truncate local partial and write this body from offset 0 (HTTP 200). */
        data object WriteFromStart : ResumePlan()
    }

    private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")

    fun parseContentRange(header: String?): ContentRange? {
        if (header.isNullOrBlank()) return null
        val match = CONTENT_RANGE.matchEntire(header.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val totalRaw = match.groupValues[3]
        val total = if (totalRaw == "*") null else totalRaw.toLongOrNull()
        return ContentRange(start, end, total)
    }

    /**
     * @param existing local partial length before this response
     * @param httpCode 200 or 206
     * @param contentRangeHeader raw Content-Range for 206
     */
    fun plan(
        existing: Long,
        httpCode: Int,
        contentRangeHeader: String?,
    ): ResumePlan = when (httpCode) {
        200 -> ResumePlan.WriteFromStart
        206 -> {
            val parsed = parseContentRange(contentRangeHeader)
            when {
                parsed == null -> ResumePlan.RetryFullGet
                parsed.start != existing -> ResumePlan.RetryFullGet
                else -> ResumePlan.Append(existing, parsed.total)
            }
        }
        else -> ResumePlan.RetryFullGet
    }
}
