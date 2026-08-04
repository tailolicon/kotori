package mihon.feature.translation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import mihon.feature.translation.provider.ProviderRateLimited
import mihon.feature.translation.provider.ProviderRejected
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream

/** Coarse progress for the reader's translate control. */
sealed interface TranslationStatus {
    data object Idle : TranslationStatus
    data class Working(val completed: Int, val total: Int, val label: String) : TranslationStatus
    data class Failed(val message: String) : TranslationStatus
}

/**
 * Owns the translated-page cache and the background work that keeps it warm.
 *
 * The reader never waits for a network round trip at a chapter boundary: turning translation on
 * translates the chapter being read and then keeps [TRANSLATION_PREFETCH_CHAPTERS] chapters ahead
 * translated, so reading stays continuous.
 */
class TranslationManager(
    private val context: Application = Injekt.get(),
    val preferences: TranslationPreferences = Injekt.get(),
) {

    private val cache = TranslationCache(context, preferences)
    private val translator = PageTranslator(context, preferences)

    /**
     * How many pages may be in flight at once.
     *
     * This used to be a plain mutex, which made the whole feature strictly sequential: a page could
     * not start while the page before it waited on an HTTP response, and waiting is most of a page's
     * wall-clock time. [PageTranslator] now guards the single-lane on-device stages itself, so the
     * only thing left to bound here is memory — each page in flight holds a full-size bitmap, and a
     * webtoon strip is tens of megabytes.
     */
    private val pageSlots = Semaphore(MAX_PAGES_IN_FLIGHT)

    /**
     * One mutex per page being worked on, so two callers cannot translate the same page twice.
     *
     * Without it, the reader arriving at a page the prefetch is already translating would pay for it
     * a second time — the same request, the same quota, discarded.
     */
    private val inFlight = mutableMapOf<String, Mutex>()
    private val inFlightGuard = Mutex()

    private suspend fun mutexFor(key: String): Mutex = inFlightGuard.withLock {
        inFlight.getOrPut(key) { Mutex() }
    }

    private val _status = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    val status: StateFlow<TranslationStatus> = _status.asStateFlow()

    /**
     * Circuit breaker armed by a provider quota error.
     *
     * Without it a 429 is the single most expensive failure the feature can have: nothing is cached
     * on failure, so every page view and every prefetch pass retries every page — one doomed request
     * every couple of seconds, all of it billed against the very quota that is already exhausted. The
     * observed result was the free tier's entire daily budget burning down in the background while
     * the reader just saw untranslated pages.
     *
     * The breaker is keyed on the settings in force when it was armed, so entering a new API key or
     * switching provider/model resumes translation immediately instead of serving out the penalty.
     */
    @Volatile private var backoffUntilMillis = 0L

    @Volatile private var backoffSettings = ""

    /** Reset by any page that succeeds, so an isolated failure never accumulates into a pause. */
    @Volatile private var consecutiveFailures = 0

    fun isRateLimited(): Boolean =
        System.currentTimeMillis() < backoffUntilMillis && backoffSettings == providerSettings()

    private fun providerSettings(): String =
        preferences.outputStamp() + "|" +
            preferences.geminiApiKey.get().hashCode() + "|" +
            preferences.groqApiKey.get().hashCode()

    private fun armBackoff(e: ProviderRateLimited) {
        val pauseSeconds = when {
            e.dailyQuota -> DAILY_QUOTA_PAUSE_SECONDS
            else -> (e.retryAfterSeconds ?: DEFAULT_RATE_PAUSE_SECONDS).coerceIn(15, 15 * 60)
        }
        backoffSettings = providerSettings()
        backoffUntilMillis = System.currentTimeMillis() + pauseSeconds * 1000
        logcat { "Provider rate-limited; pausing translation for ${pauseSeconds}s" }
    }

    // ── Per-manga enablement ────────────────────────────────────────────────────────────────────

    fun isEnabled(mangaId: Long): Boolean = preferences.enabledForManga(mangaId).get()

    fun setEnabled(mangaId: Long, enabled: Boolean) {
        preferences.enabledForManga(mangaId).set(enabled)
        if (!enabled) _status.value = TranslationStatus.Idle
    }

    fun isNovelEnabled(mangaId: Long): Boolean = preferences.novelEnabledForManga(mangaId).get()

    fun setNovelEnabled(mangaId: Long, enabled: Boolean) {
        preferences.novelEnabledForManga(mangaId).set(enabled)
    }

    fun hasCredentials(): Boolean {
        val type = preferences.provider.get()
        if (type == TranslationProviderType.OFFLINE) {
            // Authoritative: re-verify the file and keep the preference flag honest.
            val ready = offlineModelStore.hasValidModel()
            if (preferences.offlineModelReady.get() != ready) {
                preferences.offlineModelReady.set(ready)
            }
            return ready
        }
        return preferences.hasCredentialsFor(type)
    }

    val offlineModelStore: mihon.feature.translation.offline.OfflineModelStore by lazy {
        uy.kohesive.injekt.Injekt.get()
    }

    /**
     * Cancel offline inference, release the **cached** native provider, then delete the GGUF.
     * Must not construct a fresh provider (that would miss the loaded engine state).
     */
    suspend fun deleteOfflineModel() {
        offlineModelStore.cancelDownload()
        mihon.feature.translation.provider.TranslationProviders.releaseOffline()
        offlineModelStore.delete()
        preferences.offlineModelReady.set(false)
    }

    fun providerName(): String = translator.currentProvider().displayName

    // ── Page translation ────────────────────────────────────────────────────────────────────────

    /**
     * Returns a file holding the translated page, translating it first if necessary.
     *
     * Returns null when the page has no dialogue or translation failed — callers fall back to the
     * original artwork, which is always better than an error placeholder mid-chapter.
     */
    /**
     * Translates a run of consecutive pages together, joined into one tall image.
     *
     * This is the prefetch path. Manhwa sources deliver a chapter as dozens of short images, and the
     * provider charges per image regardless of its size — forty slices of one page cost forty times
     * what the whole page does. Joining them before sending is what keeps a daily allowance of 500
     * requests worth hundreds of chapters instead of five.
     *
     * Pages already cached are skipped; the rest are grouped up to [MAX_STRIP_PIXELS] and handed to
     * [PageTranslator.translateStrip]. Each result is written to its own cache entry, so everything
     * downstream — the reader, the cache, eviction — is unchanged.
     *
     * A group that fails is not retried page by page: the failure is almost always the provider, and
     * retrying would spend the same quota to reach the same place.
     */
    suspend fun translatePageRun(
        mangaId: Long,
        chapterId: Long,
        pages: List<Pair<Int, () -> InputStream>>,
    ) {
        if (pages.isEmpty() || isRateLimited()) return
        val stamp = preferences.outputStamp()

        val pending = pages.filterNot { (index, _) ->
            val target = cache.pageFile(mangaId, chapterId, index, stamp)
            cache.isCached(target) || noneMarker(target).exists()
        }
        if (pending.isEmpty()) return

        var group = mutableListOf<Pair<Int, Bitmap>>()
        var groupPixels = 0L

        suspend fun flush() {
            if (group.isEmpty()) return
            val batch = group
            group = mutableListOf()
            groupPixels = 0
            translateGroup(mangaId, chapterId, stamp, batch)
        }

        try {
            for ((index, open) in pending) {
                if (isRateLimited()) break
                val bitmap = decode(open) ?: continue
                val pixels = bitmap.width.toLong() * bitmap.height
                // A page that fills the budget by itself simply travels alone, exactly as before.
                if (groupPixels > 0 && groupPixels + pixels > MAX_STRIP_PIXELS) flush()
                group += index to bitmap
                groupPixels += pixels
                if (groupPixels >= MAX_STRIP_PIXELS || group.size >= MAX_STRIP_PAGES) flush()
            }
            flush()
        } finally {
            group.forEach { (_, bitmap) -> bitmap.recycle() }
        }
    }

    private suspend fun translateGroup(
        mangaId: Long,
        chapterId: Long,
        stamp: String,
        group: List<Pair<Int, Bitmap>>,
    ) {
        pageSlots.withPermit {
            try {
                val translated = translator.translateStrip(group.map { it.second })
                translated.forEachIndexed { position, bitmap ->
                    val index = group[position].first
                    val target = cache.pageFile(mangaId, chapterId, index, stamp)
                    try {
                        write(bitmap, target)
                    } finally {
                        bitmap.recycle()
                    }
                }
                cache.trimToSize()
                consecutiveFailures = 0
                logcat { "Translated ${group.size} page(s) as one strip for chapter $chapterId" }
            } catch (e: PageTranslator.NothingToTranslate) {
                // The whole run had no dialogue: record that for each page so it is never retried.
                group.forEach { (index, _) ->
                    runCatching { noneMarker(cache.pageFile(mangaId, chapterId, index, stamp)).createNewFile() }
                }
                consecutiveFailures = 0
            } catch (e: ProviderRateLimited) {
                armBackoff(e)
                _status.value = TranslationStatus.Failed(e.message ?: "Dịch thất bại")
            } catch (e: ProviderRejected) {
                backoffSettings = providerSettings()
                backoffUntilMillis = System.currentTimeMillis() + REJECTED_PAUSE_SECONDS * 1000
                _status.value = TranslationStatus.Failed(e.message ?: "Dịch thất bại")
            } catch (e: Throwable) {
                logcat { "Strip translation failed for chapter $chapterId: ${e.message}" }
                _status.value = TranslationStatus.Failed(friendlyMessage(e))
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    backoffSettings = providerSettings()
                    backoffUntilMillis = System.currentTimeMillis() + FAILURE_PAUSE_SECONDS * 1000
                    logcat { "Pausing translation for ${FAILURE_PAUSE_SECONDS}s after repeated failures" }
                }
            } finally {
                group.forEach { (_, bitmap) -> bitmap.recycle() }
            }
        }
    }

    suspend fun translatedPage(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        openSource: () -> InputStream,
    ): File? {
        val stamp = preferences.outputStamp()
        val target = cache.pageFile(mangaId, chapterId, pageIndex, stamp)
        if (cache.isCached(target)) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        // A page known to have no translatable dialogue is a result, not a failure — remembering it
        // on disk is what stops the pipeline re-detecting and re-asking the provider on every view.
        val noneMarker = noneMarker(target)
        if (noneMarker.exists()) return null
        if (isRateLimited()) return null

        val pageKey = "$mangaId/$chapterId/$pageIndex/$stamp"
        return pageSlots.withPermit {
            mutexFor(pageKey).withLock {
                // Re-check: a prefetch pass may have produced it while we waited.
                if (cache.isCached(target)) return@withLock target
                if (isRateLimited()) return@withLock null

                val source = decode(openSource) ?: return@withLock null
                try {
                    val translated = translator.translate(source)
                    val written = try {
                        write(translated, target)
                    } finally {
                        if (translated !== source) translated.recycle()
                    }
                    if (!written) return@withLock null
                    cache.trimToSize()
                    consecutiveFailures = 0
                    target
                } catch (e: PageTranslator.NothingToTranslate) {
                    logcat { "Page $pageIndex of chapter $chapterId has no dialogue" }
                    runCatching { noneMarker.createNewFile() }
                    consecutiveFailures = 0
                    null
                } catch (e: ProviderRateLimited) {
                    armBackoff(e)
                    _status.value = TranslationStatus.Failed(e.message ?: "Dịch thất bại")
                    null
                } catch (e: ProviderRejected) {
                    // Nothing improves until the credentials change, and the breaker is keyed on those,
                    // so this pauses until the user edits them and resumes the moment they do.
                    backoffSettings = providerSettings()
                    backoffUntilMillis = System.currentTimeMillis() + REJECTED_PAUSE_SECONDS * 1000
                    logcat { "Provider rejected the credentials; pausing until settings change" }
                    _status.value = TranslationStatus.Failed(e.message ?: "Dịch thất bại")
                    null
                } catch (e: Throwable) {
                    logcat { "Translation failed for page $pageIndex: ${e.message}" }
                    _status.value = TranslationStatus.Failed(friendlyMessage(e))
                    // A fault that is not about this page — a model that will not load, no network —
                    // fails every page identically, and the reader retries each one on every view. The
                    // observed result was three pages failing in a loop several times a second, forever.
                    // Pausing after a run of identical failures costs nothing when the fault is real and
                    // recovers on its own when it is not.
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        backoffSettings = providerSettings()
                        backoffUntilMillis = System.currentTimeMillis() + FAILURE_PAUSE_SECONDS * 1000
                        logcat { "Pausing translation for ${FAILURE_PAUSE_SECONDS}s after repeated failures" }
                    }
                    null
                } finally {
                    source.recycle()
                }
            }
        }
    }

    /**
     * Turns an internal failure into something a reader can act on.
     *
     * The detector's own message is a protobuf parser error naming a file path — accurate, and
     * useless to the person holding the phone, who needs to know whether to wait, retry or
     * reinstall.
     */
    private fun friendlyMessage(e: Throwable): String {
        val raw = e.message.orEmpty()
        return when {
            "onnx" in raw.lowercase() || "protobuf" in raw.lowercase() ->
                "Không nạp được mô hình nhận diện khung thoại. Hãy cài lại ứng dụng — bản dịch sẽ " +
                    "chạy lại ngay sau đó."
            e is java.io.IOException ->
                "Mất kết nối khi đang dịch. Sẽ thử lại khi có mạng."
            else -> raw.ifBlank { "Dịch thất bại" }
        }
    }

    /**
     * Sibling of the page file recording "translated: nothing to draw". Same stamp key as the page
     * itself, so a provider or language change invalidates the verdict along with the artwork.
     */
    private fun noneMarker(target: File): File =
        File(target.parentFile, target.nameWithoutExtension + ".none")

    fun isPageCached(mangaId: Long, chapterId: Long, pageIndex: Int): Boolean =
        cache.isCached(cache.pageFile(mangaId, chapterId, pageIndex, preferences.outputStamp()))

    // ── Progress reporting ──────────────────────────────────────────────────────────────────────

    /**
     * Publishes prefetch progress for the reader's translate panel.
     *
     * The prefetch loop itself lives in the reader, which is the only place that can fetch pages
     * through the chapter loader; this class just owns the state the UI observes.
     */
    fun updateStatus(status: TranslationStatus) {
        _status.value = status
    }

    /**
     * Called when the reader closes on a series: starts that series' expiry clock and sweeps any
     * series whose window has already passed.
     *
     * Sweeping here rather than on a timer means the work happens when the user has just stopped
     * reading, which is exactly when the device is idle and the pages are least likely to be wanted.
     */
    fun onReaderClosed(mangaId: Long) {
        runCatching {
            cache.markClosed(mangaId)
            cache.evictStale()
        }
    }

    fun clearFor(mangaId: Long) {
        cache.clearManga(mangaId)
    }

    fun clearAll() {
        cache.clearAll()
    }

    fun cacheSizeBytes(): Long = cache.sizeBytes()

    fun textCacheFile(mangaId: Long, chapterId: Long, stamp: String): File =
        cache.chapterTextFile(mangaId, chapterId, stamp)

    fun release() {
        translator.close()
    }

    /**
     * Writes the translated page, choosing a format that can actually hold it.
     *
     * WebP is the right default — lossless, and far smaller than PNG on flat comic art — but its
     * container caps each side at 16383 pixels, and a webtoon strip routinely runs past 20000. Over
     * that limit `compress` does not throw: it returns false, leaving a zero-length file behind. The
     * cache treats an empty file as a miss, so the page was translated again on every single view, at
     * full API cost, and the reader showed the untranslated artwork every time — the feature looked
     * simply broken on exactly the series it matters most for.
     *
     * PNG has no such limit and is still lossless, so oversized pages fall back to it.
     *
     * Lossy WebP is not an option at any size: it puts ringing around every line and glyph edge, which
     * on flat-toned art is precisely where the eye goes, and the page reads softer than the original
     * even where nothing was translated.
     */
    private fun write(bitmap: Bitmap, target: File): Boolean {
        val oversized = bitmap.width > MAX_WEBP_SIDE || bitmap.height > MAX_WEBP_SIDE
        val format = if (oversized) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.WEBP_LOSSLESS
        if (oversized) {
            logcat { "Page is ${bitmap.width}x${bitmap.height}; WebP cannot hold it, writing PNG" }
        }

        val ok = runCatching {
            target.parentFile?.mkdirs()
            target.outputStream().use { out -> bitmap.compress(format, 100, out) }
        }.getOrElse {
            logcat { "Failed to write translated page ${target.name}: ${it.message}" }
            false
        }

        // A half-written file would be indistinguishable from a real cache entry once it had any
        // length at all, so clear it and let the page be retried.
        if (!ok) {
            logcat { "Encoder rejected ${bitmap.width}x${bitmap.height} page as $format" }
            target.delete()
        }
        return ok
    }

    /**
     * Decodes a page at native resolution.
     *
     * Subsampling is a last resort against running out of memory, not a performance tactic. Capping
     * by long edge was: a webtoon strip is legitimately several thousand pixels tall, so the cap fired
     * on ordinary pages and halved their width, and the reader then displayed a translated page
     * visibly softer than the original. The budget is on total pixels instead, set high enough that
     * only genuinely enormous images are touched.
     */
    private fun decode(openSource: () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { openSource().use { BitmapFactory.decodeStream(it, null, bounds) } }
            .onFailure { logcat { "Could not read page bounds: ${it.message}" } }

        val megapixels = bounds.outWidth.toLong() * bounds.outHeight
        var sampleSize = 1
        while (megapixels / (sampleSize.toLong() * sampleSize) > MAX_WORKING_PIXELS) sampleSize *= 2
        if (sampleSize > 1) {
            logcat { "Page is ${bounds.outWidth}x${bounds.outHeight}; subsampling by $sampleSize" }
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { openSource().use { BitmapFactory.decodeStream(it, null, options) } }
            .onFailure { logcat { "Could not decode page: ${it.message}" } }
            .getOrNull()
    }

    private companion object {
        /**
         * Pause after a daily-quota 429. Half an hour, not the full day: the user may fix the cause
         * at any moment (new key, paid tier, different model), and a re-arm costs one request.
         */
        const val DAILY_QUOTA_PAUSE_SECONDS = 30L * 60
        /**
         * Pages translated at once. Two is enough to keep one page reading while another
         * waits on the network, and low enough that their bitmaps — tens of megabytes each
         * for a webtoon strip — cannot exhaust the heap.
         */
        const val MAX_PAGES_IN_FLIGHT = 2

        /**
         * Pixel budget for one stitched strip, and a page-count guard beside it.
         *
         * The ceiling is memory, not the API: a strip is held as ARGB_8888 while it is drawn and
         * again while it is sliced, so 12 MP is about 50 MB per copy. Going wider buys nothing
         * anyway — the provider prices images by count, so the saving is already banked once the
         * pages are joined at all.
         */
        const val MAX_STRIP_PIXELS = 12_000_000L
        const val MAX_STRIP_PAGES = 20

        /** Pause after a per-minute 429 that carried no server-suggested delay. */
        const val DEFAULT_RATE_PAUSE_SECONDS = 60L

        /** Long pause after rejected credentials; the settings-keyed breaker ends it sooner. */
        const val REJECTED_PAUSE_SECONDS = 60L * 60

        /** Identical failures in a row before translation pauses instead of retrying every page. */
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val FAILURE_PAUSE_SECONDS = 5L * 60

        /** ~48 MP: above any real comic page, below what would exhaust the heap. */
        const val MAX_WORKING_PIXELS = 48_000_000L

        /** Hard limit on either side of a WebP canvas, imposed by the format itself. */
        const val MAX_WEBP_SIDE = 16_383
    }
}
