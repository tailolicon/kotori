package app.kotori.extension.all.hitomi

/**
 * Hitomi listings are binary `.nozomi` files: packed 4-byte big-endian gallery IDs.
 * Pagination is an HTTP Range of 25 IDs (100 bytes). If the server ignores Range and
 * returns the whole file, we slice it ourselves so page 2+ still works.
 */
internal object HitomiNozomi {

    const val PAGE_SIZE = 25
    const val ID_BYTES = 4

    fun byteRange(page: Int): Pair<Int, Int> {
        val from = (page - 1).coerceAtLeast(0) * PAGE_SIZE * ID_BYTES
        val to = from + PAGE_SIZE * ID_BYTES - 1
        return from to to
    }

    fun parseIds(bytes: ByteArray): List<Long> {
        val ids = ArrayList<Long>(bytes.size / ID_BYTES)
        var i = 0
        while (i + 3 < bytes.size) {
            val id = ((bytes[i].toLong() and 0xff) shl 24) or
                ((bytes[i + 1].toLong() and 0xff) shl 16) or
                ((bytes[i + 2].toLong() and 0xff) shl 8) or
                (bytes[i + 3].toLong() and 0xff)
            if (id > 0) ids += id
            i += ID_BYTES
        }
        return ids
    }

    fun slicePage(bytes: ByteArray, page: Int, contentRange: String?): Pair<List<Long>, Boolean> {
        val (from, to) = byteRange(page)
        // A file longer than one page with no Content-Range is the whole listing; slice it.
        val fullFile = contentRange.isNullOrBlank() && bytes.size > PAGE_SIZE * ID_BYTES
        val slice = if (fullFile) {
            val end = minOf(to + 1, bytes.size)
            if (from >= bytes.size) ByteArray(0) else bytes.copyOfRange(from, end)
        } else {
            bytes
        }
        val ids = parseIds(slice)
        val hasNext = when {
            fullFile -> page * PAGE_SIZE < bytes.size / ID_BYTES
            !contentRange.isNullOrBlank() -> hasNextFromRange(ids.size, contentRange)
            // Range was honoured (exactly one page of IDs) but the server omitted Content-Range.
            // A full page means there is more; a genuine last page is short.
            else -> ids.size >= PAGE_SIZE
        }
        return ids to hasNext
    }

    fun hasNextFromRange(idsOnPage: Int, contentRange: String?): Boolean {
        if (idsOnPage < PAGE_SIZE) return false
        val total = parseTotalBytes(contentRange) ?: return true
        val end = parseRangeEnd(contentRange)
        return end == null || end + 1 < total
    }

    internal fun parseTotalBytes(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val slash = contentRange.lastIndexOf('/')
        if (slash < 0 || slash == contentRange.lastIndex) return null
        return contentRange.substring(slash + 1).toLongOrNull()
    }

    internal fun parseRangeEnd(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val dash = contentRange.indexOf('-')
        val slash = contentRange.lastIndexOf('/')
        if (dash < 0 || slash <= dash) return null
        return contentRange.substring(dash + 1, slash).trim().toLongOrNull()
    }
}
