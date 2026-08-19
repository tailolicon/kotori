package mihon.feature.translation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import mihon.feature.translation.provider.ProviderRateLimited
import mihon.feature.translation.provider.ProviderRejected
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.decoder.ImageDecoder
import java.io.ByteArrayInputStream
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
     * Second, tighter gate for work that is large enough to matter to the heap.
     *
     * A stitched webtoon strip is [MAX_STRIP_PIXELS] pixels — about 48 MB as ARGB_8888, and it is
     * held twice while it is drawn and sliced. Four of those at once is most of an Android heap, so
     * strips keep the old limit while ordinary pages get the wider one. Always acquired *after*
     * [pageSlots] and released before it, so the two can never deadlock against each other.
     */
    private val largeWorkSlots = Semaphore(MAX_LARGE_PAGES_IN_FLIGHT)

    private val workGate = TranslationWorkGate()

    /**
     * Marks [mangaId] as the series currently being translated. Any in-flight work for a
     * different series is invalidated so it cannot keep occupying the page slots.
     */
    fun beginSession(mangaId: Long) {
        workGate.begin(mangaId)
    }

    /**
     * The reader is looking at [mangaId]. If a previous series still owns the gate, every page
     * of the one on screen would be skipped with no error — that is the "I pressed translate and
     * nothing moved" failure.
     */
    fun claim(mangaId: Long) {
        if (workGate.activeMangaId != mangaId) {
            logcat { "Claiming translation session for $mangaId (was ${workGate.activeMangaId})" }
            workGate.begin(mangaId)
        }
    }

    /** User asked again: drop the circuit breaker so a stale 429 cannot silence the new run. */
    fun clearBackoff() {
        backoffUntilMillis = 0L
        consecutiveFailures = 0
    }

    /**
     * Drops in-flight work for [mangaId] (or every series if null). Used when the reader
     * closes or the user turns translation off.
     */
    fun cancelWork(mangaId: Long? = null) {
        workGate.cancel(mangaId)
        _status.value = TranslationStatus.Idle
    }

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
        if (!enabled) cancelWork(mangaId)
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
        android.util.Log.e(
            "KotoriTL",
            "translatePageRun manga=$mangaId chapter=$chapterId pages=${pages.size} " +
                "enabled=${isEnabled(mangaId)} limited=${isRateLimited()}",
        )
        if (pages.isEmpty() || isRateLimited() || !isEnabled(mangaId)) return
        claim(mangaId)
        val generation = workGate.generation
        val stamp = cacheKey(mangaId)

        val pending = pages.filterNot { (index, _) ->
            val target = cache.pageFile(mangaId, chapterId, index, stamp)
            cache.isCached(target) || noneMarker(target).exists()
        }
        android.util.Log.e("KotoriTL", "pending=${pending.size} stamp=$stamp")
        reportPagesHandled(pages.size - pending.size)
        if (pending.isEmpty()) return

        var group = mutableListOf<Pair<Int, Bitmap>>()
        var groupPixels = 0L

        // Groups are started, not awaited.
        //
        // Almost all of a page's wall clock is one HTTP response — measured at 8-10 s against
        // roughly half a second of on-device work — and the loop used to wait out every one of them
        // before decoding the next page. [pageSlots] existed to bound how many pages could be in
        // flight and never had more than one to bound. Launching here is what finally lets the
        // permit count mean something, and it is the difference between eight seconds a page and
        // eight seconds for as many pages as the semaphore allows.
        coroutineScope {
            val inFlightGroups = mutableListOf<Job>()

            fun flush() {
                if (group.isEmpty()) return
                val batch = group
                group = mutableListOf()
                groupPixels = 0
                // The images themselves decide this, not the viewer setting. Gating it on "the reader
                // is in webtoon mode" meant a batch never held more than one page in any other mode,
                // so the classifier never saw a seam to judge — and a source that cuts every page
                // into two files had every balloon straddling a cut translated as two halves, one of
                // them discarded as an edge sliver. ContinuousPageClassifier measures the pixels
                // either side of the seam; unrelated full pages have blank or mismatched edges and
                // are still translated singly.
                val continuous = ContinuousPageClassifier.shouldJoin(batch.map { it.second })
                if (continuous) {
                    inFlightGroups += launch { translateGroup(mangaId, chapterId, stamp, generation, batch) }
                } else {
                    batch.forEach { page ->
                        inFlightGroups += launch {
                            translateGroup(mangaId, chapterId, stamp, generation, listOf(page))
                        }
                    }
                }
            }

            try {
                for ((index, open) in pending) {
                    currentCoroutineContext().ensureActive()
                    if (isRateLimited() || !workGate.allows(mangaId, generation) || !isEnabled(mangaId)) {
                        break
                    }
                    val bitmap = decode(open) ?: continue
                    val pixels = bitmap.width.toLong() * bitmap.height
                    // A page that fills the budget by itself simply travels alone, exactly as before.
                    if (groupPixels > 0 && groupPixels + pixels > MAX_STRIP_PIXELS) flush()
                    group += index to bitmap
                    groupPixels += pixels
                    if (groupPixels >= MAX_STRIP_PIXELS || group.size >= MAX_STRIP_PAGES) flush()
                    // Decoding runs ahead of translation, and a decoded page is tens of megabytes.
                    // Without this the loop would read a whole chapter into memory while the first
                    // few pages were still waiting on the network.
                    if (inFlightGroups.count { it.isActive } >= MAX_PAGES_IN_FLIGHT) {
                        inFlightGroups.firstOrNull { it.isActive }?.join()
                    }
                }
                if (workGate.allows(mangaId, generation) && isEnabled(mangaId) && !isRateLimited()) {
                    flush()
                }
                // Every launched group recycles its own bitmaps; the ones below are the remainder
                // that never became a group, so nothing may be recycled until the jobs are done.
                inFlightGroups.joinAll()
            } finally {
                group.forEach { (_, bitmap) -> bitmap.recycle() }
            }
        }
    }

    private suspend fun translateGroup(
        mangaId: Long,
        chapterId: Long,
        stamp: String,
        generation: Int,
        group: List<Pair<Int, Bitmap>>,
    ) {
        val pixels = group.sumOf { (_, bitmap) -> bitmap.width.toLong() * bitmap.height }
        val large = pixels > LARGE_WORK_PIXELS
        pageSlots.withPermit {
            if (large) largeWorkSlots.acquire()
            try {
                if (!workGate.allows(mangaId, generation) || !isEnabled(mangaId) || isRateLimited()) {
                    return@withPermit
                }
                val translated = translator.translateStrip(
                    group.map { it.second },
                    "chapter=$chapterId pages=${group.joinToString { it.first.toString() }}",
                )
                if (!workGate.allows(mangaId, generation) || !isEnabled(mangaId)) {
                    translated.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
                    logcat { "Discarded in-flight strip for chapter $chapterId after cache invalidation" }
                    return@withPermit
                }
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
                reportPagesHandled(group.size)
                val mode = if (group.size == 1) "at native page resolution" else "as one continuous strip"
                logcat { "Translated ${group.size} page(s) $mode for chapter $chapterId" }
            } catch (e: PageTranslator.NothingToTranslate) {
                // The whole run had no dialogue: record that for each page so it is never retried.
                if (workGate.allows(mangaId, generation) && isEnabled(mangaId)) {
                    group.forEach { (index, _) ->
                        runCatching {
                            noneMarker(cache.pageFile(mangaId, chapterId, index, stamp)).createNewFile()
                        }
                    }
                }
                consecutiveFailures = 0
                reportPagesHandled(group.size)
            } catch (e: ProviderRateLimited) {
                armBackoff(e)
                _status.value = TranslationStatus.Failed(e.message ?: "Dịch thất bại")
            } catch (e: ProviderRejected) {
                backoffSettings = providerSettings()
                backoffUntilMillis = System.currentTimeMillis() + REJECTED_PAUSE_SECONDS * 1000
                _status.value = TranslationStatus.Failed(e.message ?: "Dịch thất bại")
            } catch (e: Throwable) {
                logcat { "Strip translation failed for chapter $chapterId: ${e.message}" }
                reportPagesHandled(group.size)
                _status.value = TranslationStatus.Failed(friendlyMessage(e))
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    backoffSettings = providerSettings()
                    backoffUntilMillis = System.currentTimeMillis() + FAILURE_PAUSE_SECONDS * 1000
                    logcat { "Pausing translation for ${FAILURE_PAUSE_SECONDS}s after repeated failures" }
                }
            } finally {
                if (large) largeWorkSlots.release()
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
        val stamp = cacheKey(mangaId)
        val target = cache.pageFile(mangaId, chapterId, pageIndex, stamp)
        if (cache.isCached(target)) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        // A page known to have no translatable dialogue is a result, not a failure — remembering it
        // on disk is what stops the pipeline re-detecting and re-asking the provider on every view.
        val noneMarker = noneMarker(target)
        if (noneMarker.exists()) return null
        if (isRateLimited() || !isEnabled(mangaId)) return null
        claim(mangaId)
        val generation = workGate.generation

        val pageKey = "$mangaId/$chapterId/$pageIndex/$stamp"
        return pageSlots.withPermit {
            mutexFor(pageKey).withLock {
                // Re-check: a prefetch pass may have produced it while we waited.
                if (cache.isCached(target)) return@withLock target
                if (isRateLimited() || !workGate.allows(mangaId, generation) || !isEnabled(mangaId)) {
                    return@withLock null
                }

                val source = decode(openSource) ?: return@withLock null
                try {
                    val translated = translator.translate(source, "chapter=$chapterId page=$pageIndex")
                    if (!workGate.allows(mangaId, generation) || !isEnabled(mangaId)) {
                        if (translated !== source && !translated.isRecycled) translated.recycle()
                        logcat { "Discarded in-flight page $pageIndex after cache invalidation" }
                        return@withLock null
                    }
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
                    if (workGate.allows(mangaId, generation) && isEnabled(mangaId)) {
                        runCatching { noneMarker.createNewFile() }
                    }
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
        cache.isCached(cache.pageFile(mangaId, chapterId, pageIndex, cacheKey(mangaId)))

    private fun cacheKey(mangaId: Long): String =
        preferences.outputStamp() +
            "|g${preferences.globalCacheGeneration.get()}.${preferences.cacheGeneration(mangaId).get()}"

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

    @Volatile private var progressLabel = ""

    @Volatile private var progressTotal = 0

    private val progressDone = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Starts the counter for one chapter.
     *
     * The number the reader sees has to mean "pages you can now look at", and it used to mean
     * "pages fetched": the loop counted a page the moment its bytes arrived, then handed forty of
     * them to the translator in one go. So the panel raced to 7/42 while the first page was still
     * the only one rendered, and sat there for as long as the batch took. Counting is therefore
     * done here, where pages are actually finished, and the total is one chapter — not a running
     * sum over the look-ahead window, which grew under the fraction as it went.
     */
    fun beginChapterProgress(label: String, totalPages: Int) {
        progressLabel = label
        progressTotal = totalPages
        progressDone.set(0)
        fetchedPages = 0
        publishProgress()
    }

    @Volatile private var fetchedPages = 0

    /**
     * [pages] of the chapter have been downloaded but not yet translated.
     *
     * Reported separately because the two numbers mean different things to the reader and conflating
     * them is what made the panel lie: it counted downloads and said "7/42 trang" while one page was
     * rendered. Downloads move the label, never the bar.
     */
    fun reportFetched(pages: Int) {
        if (progressTotal <= 0) return
        fetchedPages = pages
        publishProgress()
    }

    /** [pages] more pages of the current chapter are done — translated, blank, or given up on. */
    fun reportPagesHandled(pages: Int) {
        if (pages <= 0 || progressTotal <= 0) return
        progressDone.addAndGet(pages)
        publishProgress()
    }

    private fun publishProgress() {
        val total = progressTotal
        if (total <= 0) return
        // A quota or credentials message is the one line that explains why pages stopped; a
        // progress bar must not paint over it.
        if (_status.value is TranslationStatus.Failed) return
        val done = progressDone.get().coerceAtMost(total)
        val label = if (fetchedPages > done) "$progressLabel · đang tải $fetchedPages" else progressLabel
        _status.value = TranslationStatus.Working(done, total, label)
    }

    /** The look-ahead finished or was abandoned; stop reporting a chapter that is no longer running. */
    fun finishProgress() {
        progressTotal = 0
        if (_status.value is TranslationStatus.Working) _status.value = TranslationStatus.Idle
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

    /**
     * Drops cached pages for [mangaId] and makes any in-flight writer unable to put them back.
     *
     * Deleting files alone is not enough: the viewer may still hold a page open (delete then
     * fails), and a strip that started before the tap finishes minutes later and recreates the
     * same names. Bumping the generation changes the on-disk key; leftover files become orphans.
     */
    fun discardTranslations(mangaId: Long) {
        workGate.invalidateInFlight()
        preferences.bumpCacheGeneration(mangaId)
        cache.clearManga(mangaId)
        _status.value = TranslationStatus.Idle
        logcat { "Discarded translations for manga $mangaId" }
    }

    fun clearFor(mangaId: Long) {
        discardTranslations(mangaId)
    }

    /**
     * Drops every series' translations.
     *
     * Bumps the global generation for the same reason [discardTranslations] bumps the per-series
     * one: a page the viewer still holds open cannot be deleted on every filesystem, and a strip
     * that started before the tap will finish afterwards and write the old names back. Changing the
     * key makes whatever survives unreachable instead of trusting the delete.
     */
    fun clearAll() {
        workGate.invalidateInFlight()
        preferences.bumpGlobalCacheGeneration()
        cache.clearAll()
        _status.value = TranslationStatus.Idle
        logcat { "Discarded every translated page" }
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
        val bytes = runCatching { openSource().use { it.readBytes() } }
            .onFailure { android.util.Log.e("KotoriTL", "read failed: ${it.message}") }
            .getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            android.util.Log.e("KotoriTL", "empty page bytes")
            return null
        }
        val factory = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (factory != null) return factory
        val decoder = runCatching {
            ImageDecoder.newInstance(ByteArrayInputStream(bytes))
        }.getOrNull()
        val decoded = decoder?.decode()
        decoder?.recycle()
        if (decoded == null) {
            android.util.Log.e(
                "KotoriTL",
                "decode failed bytes=${bytes.size} magic=${bytes.take(8).joinToString()}",
            )
        }
        return decoded
    }

    private companion object {
        /**
         * Pause after a daily-quota 429. Half an hour, not the full day: the user may fix the cause
         * at any moment (new key, paid tier, different model), and a re-arm costs one request.
         */
        const val DAILY_QUOTA_PAUSE_SECONDS = 30L * 60
        /**
         * Pages translated at once.
         *
         * Two was chosen when the loop awaited each group anyway, so it was never reached. With
         * groups actually running concurrently this is what turns "eight seconds a page" into
         * "eight seconds for four pages", and it is the whole of the speed answer: the provider
         * call is the cost, and the only way to shorten it is to overlap it with other pages'.
         *
         * Four is bounded by memory rather than by the endpoint — see [largeWorkSlots], which keeps
         * webtoon strips at the old figure.
         */
        const val MAX_PAGES_IN_FLIGHT = 4

        /** Concurrency for stitched strips, whose bitmaps are an order of magnitude larger. */
        const val MAX_LARGE_PAGES_IN_FLIGHT = 2

        /** Above this, a group counts as large. An ordinary comic page sits well under it. */
        const val LARGE_WORK_PIXELS = 8_000_000L

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
