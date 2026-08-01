package mihon.feature.translation

import android.content.Context
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.security.MessageDigest

/**
 * On-disk store for translated pages and chapters.
 *
 * Lives in the cache directory on purpose: a translated page is derived data that can always be
 * rebuilt, so it should be the first thing the system reclaims under storage pressure rather than
 * competing with the user's actual downloads.
 *
 * Layout is `translated/<mangaId>/<chapterId>/<stamp>-<page>.<ext>`. Keying on a settings stamp means
 * switching provider or target language misses cleanly instead of showing pages translated under the
 * old settings.
 */
class TranslationCache(
    context: Context,
    private val preferences: TranslationPreferences,
) {

    private val root = File(context.cacheDir, DIRECTORY).apply { mkdirs() }

    fun pageFile(mangaId: Long, chapterId: Long, pageIndex: Int, stamp: String): File =
        File(chapterDir(mangaId, chapterId), "${hash(stamp)}-$pageIndex.webp")

    fun chapterTextFile(mangaId: Long, chapterId: Long, stamp: String): File =
        File(chapterDir(mangaId, chapterId), "${hash(stamp)}-text.json")

    fun chapterDir(mangaId: Long, chapterId: Long): File =
        File(root, "$mangaId/$chapterId").apply { mkdirs() }

    fun isCached(file: File): Boolean = file.exists() && file.length() > 0

    /** Drops every translation for one manga, e.g. when the user turns the feature off for it. */
    fun clearManga(mangaId: Long) {
        File(root, mangaId.toString()).deleteRecursively()
    }

    /**
     * Records that the reader just closed on this series, starting its expiry clock.
     *
     * The marker is a separate file rather than the directory's own timestamp because writing pages
     * into a directory keeps refreshing that timestamp, which would mean a long series never expires.
     */
    fun markClosed(mangaId: Long) {
        val dir = File(root, mangaId.toString())
        if (!dir.exists()) return
        runCatching {
            File(dir, CLOSED_MARKER).apply {
                if (!exists()) createNewFile()
                setLastModified(System.currentTimeMillis())
            }
        }
    }

    /**
     * Deletes translations for series the reader has not returned to within the retention window.
     *
     * A series still open, or closed more recently than the window, is left alone. Series with no
     * marker at all get one now rather than being deleted immediately, so an interrupted session does
     * not lose its work.
     */
    fun evictStale() {
        val retentionHours = preferences.cacheRetentionHours.get()
        if (retentionHours <= 0) return

        val cutoff = System.currentTimeMillis() - retentionHours * MILLIS_PER_HOUR
        root.listFiles()?.forEach { mangaDir ->
            if (!mangaDir.isDirectory) return@forEach
            val marker = File(mangaDir, CLOSED_MARKER)
            when {
                !marker.exists() -> runCatching { marker.createNewFile() }
                marker.lastModified() < cutoff -> {
                    if (mangaDir.deleteRecursively()) {
                        logcat { "Expired translated pages for manga ${mangaDir.name}" }
                    }
                }
            }
        }
    }

    fun clearAll() {
        root.deleteRecursively()
        root.mkdirs()
    }

    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Evicts least-recently-used files until the cache fits its budget.
     *
     * Eviction is per file rather than per chapter so that a partially read chapter keeps the pages
     * around the reader's current position — those are the ones most likely to be needed again.
     */
    fun trimToSize() {
        val budget = preferences.cacheSizeMb.get().coerceAtLeast(64).toLong() * BYTES_PER_MB
        // Expiry markers are excluded: they are the oldest files in every directory, so trimming would
        // delete them first and reset each series' expiry clock.
        val files = root.walkTopDown().filter { it.isFile && it.name != CLOSED_MARKER }.toMutableList()
        var total = files.sumOf { it.length() }
        if (total <= budget) return

        files.sortBy { it.lastModified() }
        for (file in files) {
            if (total <= budget) break
            val length = file.length()
            if (file.delete()) total -= length
        }
        logcat { "Trimmed translation cache to ${total / BYTES_PER_MB} MiB" }

        // Remove the now-empty chapter and manga directories left behind.
        root.listFiles()?.forEach { mangaDir ->
            mangaDir.listFiles()?.forEach { chapterDir ->
                if (chapterDir.isDirectory && chapterDir.listFiles().isNullOrEmpty()) chapterDir.delete()
            }
            if (mangaDir.isDirectory && mangaDir.listFiles().isNullOrEmpty()) mangaDir.delete()
        }
    }

    private fun hash(stamp: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(stamp.toByteArray())
        return digest.take(HASH_BYTES).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val DIRECTORY = "translated"
        const val CLOSED_MARKER = ".closed"
        const val BYTES_PER_MB = 1024L * 1024L
        const val MILLIS_PER_HOUR = 60L * 60L * 1000L
        const val HASH_BYTES = 6
    }
}
