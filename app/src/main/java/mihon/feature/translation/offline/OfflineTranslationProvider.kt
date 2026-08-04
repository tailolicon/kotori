package mihon.feature.translation.offline

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.feature.translation.provider.BubbleTranslation
import mihon.feature.translation.provider.ProseContext
import mihon.feature.translation.provider.ProseTranslation
import mihon.feature.translation.provider.TranslationContext
import mihon.feature.translation.provider.TranslationProvider
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * On-device HY-MT via llama.cpp. OCR stays on ML Kit; [supportsVisionOcr] is false.
 *
 * One bubble per generation (sequential). Source is always echoed for realignToOcr.
 * Instances are cached by [mihon.feature.translation.provider.TranslationProviders] so the
 * 1.1 GB model is not reloaded every page.
 */
class OfflineTranslationProvider(
    private val modelPath: () -> String?,
    private val threadCount: () -> Int,
) : TranslationProvider {

    override val displayName: String = "Offline (HY-MT)"
    override val supportsVisionOcr: Boolean = false

    private val inferenceMutex = Mutex()
    private val cancelled = AtomicBoolean(false)
    private var loadedPath: String? = null
    private var loadedThreads: Int = -1

    override suspend fun translateLines(
        texts: List<String>,
        context: TranslationContext,
    ): List<BubbleTranslation> = withIOContext {
        if (texts.isEmpty()) return@withIOContext emptyList()
        cancelled.set(false)
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                cancelled.set(true)
                LlamaNative.cancel()
            }
        }
        try {
            inferenceMutex.withLock {
                ensureLoadedLocked()
                texts.map { source ->
                    currentCoroutineContext().ensureActive()
                    if (cancelled.get()) return@map BubbleTranslation(source, "")
                    if (source.isBlank()) return@map BubbleTranslation(source, "")
                    // Raw user content only — native applies chat template once.
                    val prompt = OfflinePrompts.singleLine(context, source)
                    val raw = LlamaNative.complete(
                        prompt = prompt,
                        maxTokens = OfflineModelSpec.MAX_NEW_TOKENS_BUBBLE,
                        temperature = OfflineModelSpec.TEMPERATURE,
                        topK = OfflineModelSpec.TOP_K,
                        topP = OfflineModelSpec.TOP_P,
                        repeatPenalty = OfflineModelSpec.REPEAT_PENALTY,
                    )
                    val translation = OfflinePrompts.cleanBubble(raw.orEmpty(), source)
                    BubbleTranslation(source = source, translation = translation)
                }
            }
        } finally {
            cancelHandle?.dispose()
            if (currentCoroutineContext()[Job]?.isCancelled == true) {
                LlamaNative.cancel()
            }
        }
    }

    override suspend fun translateProse(
        paragraphs: List<String>,
        context: TranslationContext,
        prose: ProseContext,
    ): ProseTranslation = withIOContext {
        if (paragraphs.isEmpty()) return@withIOContext ProseTranslation("")
        cancelled.set(false)
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                cancelled.set(true)
                LlamaNative.cancel()
            }
        }
        try {
            inferenceMutex.withLock {
                ensureLoadedLocked()
                val out = paragraphs.map { paragraph ->
                    currentCoroutineContext().ensureActive()
                    if (cancelled.get()) return@map ""
                    if (paragraph.isBlank()) return@map ""
                    val prompt = OfflinePrompts.proseParagraph(context, prose, paragraph)
                    val raw = LlamaNative.complete(
                        prompt = prompt,
                        maxTokens = OfflineModelSpec.MAX_NEW_TOKENS_PROSE,
                        temperature = OfflineModelSpec.TEMPERATURE,
                        topK = OfflineModelSpec.TOP_K,
                        topP = OfflineModelSpec.TOP_P,
                        repeatPenalty = OfflineModelSpec.REPEAT_PENALTY,
                    )
                    OfflinePrompts.cleanProse(raw.orEmpty(), paragraph)
                }
                ProseTranslation(out.joinToString("\n\n"))
            }
        } finally {
            cancelHandle?.dispose()
            if (currentCoroutineContext()[Job]?.isCancelled == true) {
                LlamaNative.cancel()
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        LlamaNative.cancel()
    }

    /**
     * Unload native resources. Takes [inferenceMutex] so this cannot race [ensureLoadedLocked]
     * or an in-flight complete on this instance.
     */
    fun release() {
        cancelled.set(true)
        LlamaNative.cancel()
        runBlocking {
            inferenceMutex.withLock {
                runCatching { LlamaNative.unload() }
                loadedPath = null
                loadedThreads = -1
            }
        }
    }

    /** Must be called while holding [inferenceMutex]. */
    private fun ensureLoadedLocked() {
        val path = modelPath()
            ?: error("offline_model_missing")
        if (!LlamaNative.isAvailable()) {
            error("offline_runtime_unavailable")
        }
        val threads = threadCount().coerceIn(OfflineModelSpec.MIN_THREADS, OfflineModelSpec.MAX_THREADS)
        if (loadedPath == path && loadedThreads == threads) return

        LlamaNative.unload()
        val ok = LlamaNative.load(
            modelPath = path,
            nCtx = OfflineModelSpec.DEFAULT_CONTEXT,
            nThreads = threads,
        )
        if (!ok) {
            loadedPath = null
            loadedThreads = -1
            error("offline_model_load_failed")
        }
        loadedPath = path
        loadedThreads = threads
        logcat { "Offline model loaded: $path threads=$threads" }
    }
}
