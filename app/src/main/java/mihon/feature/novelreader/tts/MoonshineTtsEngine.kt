package mihon.feature.novelreader.tts

import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.GraphemeToPhonemizer
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.TextToSpeech
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.TtsSynthesisResult
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import logcat.LogPriority
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * On-device neural speech via Moonshine's Kokoro/Piper voices.
 *
 * This is the reason the listening feature is worth using: the system engine reads a novel like a
 * satnav, while these models carry sentence intonation and emotion. The cost is a model that has to
 * arrive first, so nothing here is bundled — the voice is fetched on first use into the app's own
 * storage and every later chapter plays entirely offline from that cache.
 *
 * Synthesis is done a sentence at a time, one sentence ahead of playback. That pipelining is what
 * makes it usable — a whole chapter synthesized up front would take far too long to start, and
 * synthesizing strictly on demand would leave an audible gap between every sentence. It also makes
 * the karaoke highlight exact at sentence level: the sentence being highlighted is literally the
 * buffer being written to the audio track.
 */
class MoonshineTtsEngine(context: Context) : NovelTtsEngine {

    override val id = NovelTtsEngineId.NEURAL

    private val appContext = context.applicationContext
    private val assetRoot: File by lazy { File(appContext.filesDir, ASSET_DIR).apply { mkdirs() } }

    private var synthesizer: TextToSpeech? = null

    /**
     * The same G2P the synthesizer would run internally, held separately so the phoneme string can
     * be repaired between text and audio — see [VietnameseTonePhonemes] for why that repair is the
     * difference between Vietnamese and fluent nonsense.
     */
    private var phonemizer: GraphemeToPhonemizer? = null
    private var preparedVoice: String? = null

    private var handle: SentenceClipPipeline.Handle? = null

    /**
     * Invalidation counter for a run of playback.
     *
     * Both worker threads compare it against the value they were started with, which is how a stop
     * or a seek is honoured without killing threads mid-write: they simply notice they are stale and
     * unwind. A monotonic counter rather than a flag because a seek starts the next run immediately,
     * and the outgoing run must not be able to mistake the new run's flag for its own.
     */
    @Volatile
    private var generation = 0

    override val isReady: Boolean get() = synthesizer != null

    /**
     * Voices Moonshine publishes for Vietnamese, marking which are already on disk.
     *
     * Reported straight from the native catalogue rather than a hardcoded list, so a voice added in
     * a later library release shows up without a code change.
     */
    override fun voices(): List<NovelVoice> = runCatching {
        val json = TextToSpeech.getTtsVoices(LANGUAGE, listOf(TranscriberOption(G2P_ROOT, assetRoot.absolutePath)))
        val entries = JSONObject(json).optJSONArray(LANGUAGE) ?: return emptyList()
        (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val voiceId = entry.optString("id").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val downloaded = when (entry.optString("state")) {
                "found" -> true
                "missing" -> false
                else -> return@mapNotNull null
            }
            NovelVoice(
                id = voiceId,
                label = voiceId.toLabel(),
                downloaded = downloaded,
                sizeLabel = if (downloaded) null else "cần tải",
            )
            // Best first, so the picker leads with it and defaultVoice() lands on it. Ordering by
            // label instead put whichever voice sorted first alphabetically in front of a better
            // one, which on a fresh install is the voice that then gets downloaded and read with.
        }.sortedWith(compareBy({ !it.downloaded }, { -it.id.qualityRank() }, { it.label }))
    }.getOrElse {
        logcat(LogPriority.WARN, it) { "Moonshine voice catalogue unavailable" }
        emptyList()
    }

    /**
     * Downloads whatever [voiceId] still needs, then builds the synthesizer.
     *
     * The download is the slow, failable part and the only one the listener can see, so it reports
     * per-file progress; everything after it is fast enough to look instant.
     */
    override suspend fun prepare(
        voiceId: String?,
        onProgress: (NovelTtsPreparation) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress(NovelTtsPreparation.Starting)
        // Asking the catalogue rather than hardcoding an id means a voice being renamed or added in
        // a library update cannot leave the engine trying to download something that isn't there.
        val voice = voiceId ?: defaultVoice() ?: run {
            onProgress(NovelTtsPreparation.Failed("Không có giọng AI tiếng Việt nào"))
            return@withContext false
        }
        if (synthesizer != null && preparedVoice == voice) {
            onProgress(NovelTtsPreparation.Ready)
            return@withContext true
        }
        try {
            // The downloader blocks and bails out on the thread's interrupt flag, which coroutine
            // cancellation does not raise by itself; runInterruptible bridges the two, so leaving
            // the reader mid-download actually stops it rather than letting it finish unseen.
            runInterruptible {
                val downloader = AssetDownloader()
                val report = AssetDownloader.ProgressListener {
                        key, fileIndex, totalFiles, bytesDone, bytesTotal ->
                    onProgress(
                        NovelTtsPreparation.Downloading(
                            file = key.substringAfterLast('/'),
                            index = fileIndex,
                            total = totalFiles,
                            // The manifest reports -1 when it does not know a file's size, which is
                            // an indeterminate bar rather than a zero-length one.
                            fraction = bytesTotal.takeIf { it > 0 }?.let { bytesDone.toFloat() / it },
                        ),
                    )
                }
                // Two models, and both are required. The voice turns phonemes into audio and never
                // sees the text; it is this one that turns Vietnamese letters into those phonemes.
                // Without it the phonemizer falls back to its default dialect, and the voice then
                // reads fluent, confident speech that is not Vietnamese — which sounds like a
                // broken voice rather than like a missing download.
                downloader.ensureModelPresent(assetRoot, ModelSpec.g2p(LANGUAGE), report)
                downloader.ensureModelPresent(assetRoot, ModelSpec.tts(LANGUAGE, voice), report)
            }
            // Note: `g2p_dialect` is NOT passed here even though the native logs print one. It is
            // an internal value the library derives from the language ("vi" → "vi-VN") and not an
            // accepted option key — the option parser throws `Unknown G2P option` on it, which
            // would fail this whole prepare and silently push the reader onto the system voice.
            val nextSynthesizer = TextToSpeech(appContext)
                .language(LANGUAGE)
                .voice(voice)
                .modelsFrom(assetRoot)
            try {
                nextSynthesizer.load()
            } catch (error: Throwable) {
                nextSynthesizer.close()
                throw error
            }
            synthesizer?.close()
            synthesizer = nextSynthesizer
            phonemizer?.close()
            phonemizer = runCatching {
                GraphemeToPhonemizer(LANGUAGE, assetRoot.absolutePath, null)
            }.getOrElse {
                // Losing the phonemizer only loses the tone repair below, not playback itself.
                logcat(LogPriority.WARN, it) { "Vietnamese phonemizer unavailable; tones degrade" }
                null
            }
            preparedVoice = voice
            onProgress(NovelTtsPreparation.Ready)
            true
        } catch (error: Throwable) {
            logcat(LogPriority.ERROR, error) { "Moonshine TTS could not be prepared" }
            synthesizer = null
            preparedVoice = null
            onProgress(NovelTtsPreparation.Failed(error.message ?: "Không tải được giọng đọc AI"))
            false
        }
    }

    /** Prefers a voice already on disk so a first run can start without waiting on a download. */
    private fun defaultVoice(): String? {
        val catalogue = voices()
        return (catalogue.firstOrNull { it.downloaded } ?: catalogue.firstOrNull())?.id
    }

    override fun speak(
        script: SpeechScript,
        fromIndex: Int,
        rate: Float,
        listener: NovelTtsListener,
    ) {
        val engine = synthesizer ?: return listener.onError("Giọng AI chưa sẵn sàng")
        if (script.isEmpty) return listener.onFinished()

        stop()
        val active = ++generation
        handle = SentenceClipPipeline.start(
            script = script,
            fromIndex = fromIndex,
            trackRate = rate,
            listener = listener,
            active = { active == generation },
            engineName = "Moonshine",
        ) { sentence ->
            // A sentence the model chokes on becomes a silent clip rather than an error: one bad
            // line must not end the chapter's playback.
            runCatching {
                val result = synthesizeWithTones(engine, sentence.text)
                SentenceClipPipeline.Clip(sentence, result.samples ?: FloatArray(0), result.sampleRateHz)
            }.getOrElse {
                logcat(LogPriority.WARN, it) { "Moonshine failed on sentence ${sentence.index}" }
                SentenceClipPipeline.Clip(sentence, FloatArray(0), 0)
            }
        }
    }

    /**
     * Synthesizes a sentence with its tones translated to what the model was trained on.
     *
     * The pipeline is text → IPA (the library's own Vietnamese G2P) → tone repair → audio. Going
     * through [TextToSpeech.synthesizeFromPhonemes] instead of `synthesize` is what creates the
     * point to intervene at: the plain call runs the same G2P internally but gives no chance to fix
     * the tone marks before they are dropped. If the phonemizer is missing, the plain call is still
     * made — quality degrades, playback survives.
     */
    private fun synthesizeWithTones(engine: TextToSpeech, text: String): TtsSynthesisResult {
        val g2p = phonemizer ?: return engine.synthesize(text)
        return runCatching {
            val ipa = g2p.toIpa(text)
            engine.synthesizeFromPhonemes(VietnameseTonePhonemes.toEspeakTones(ipa))
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "Tone-repaired synthesis failed; using plain path" }
            engine.synthesize(text)
        }
    }

    /**
     * Retires the current run.
     *
     * The counter is bumped first so both workers see themselves as stale before they are nudged;
     * interrupting only wakes them from a poll or a sleep, it does not decide anything.
     */
    override fun stop() {
        generation++
        handle?.interrupt()
        handle = null
    }

    override fun release() {
        stop()
        runCatching { synthesizer?.close() }
        runCatching { phonemizer?.close() }
        synthesizer = null
        phonemizer = null
        preparedVoice = null
    }

    private companion object {
        const val ASSET_DIR = "novel-tts"
        const val LANGUAGE = "vi"
        const val G2P_ROOT = "g2p_root"
        const val VOICE = "voice"

        /**
         * Turns a catalogue id into something a reader can actually choose between.
         *
         * Ids come in two shapes: `kokoro_vf_hue`, where what follows the engine is a gender and a
         * name, and `piper_vi_VN-vais1000-medium`, where it is a locale, the model's own name and
         * the size it was trained at. Reading the second as though it were the first cut the name
         * at its first dash, so every Vietnamese Piper voice came out as "VN · Piper" — identical
         * rows with no way to tell the medium-quality voice from the low one.
         */
        fun String.toLabel(): String {
            val engine = substringBefore('_').replaceFirstChar(Char::uppercase)
            val voice = substringAfter('_')
            val gender = when {
                voice.startsWith("vf") || voice.contains("female") -> "Nữ"
                voice.startsWith("vm") || voice.contains("male") -> "Nam"
                else -> null
            }
            // A dash marks the Piper shape: the locale before it is noise the language picker has
            // already accounted for, and the last segment may be the quality tier.
            val model = if ('-' in voice) voice.substringAfter('-') else voice.substringAfter('_')
            val quality = QUALITIES[model.substringAfterLast('-', "")]
            val name = (if (quality != null) model.substringBeforeLast('-') else model)
                .replace('_', ' ')
                .replaceFirstChar(Char::uppercase)
                .ifEmpty { voice }
            return listOfNotNull(name, gender, quality, engine).joinToString(" · ")
        }

        /** Piper publishes one voice at several sizes; the tier is the last segment of its id. */
        val QUALITIES = mapOf(
            "x_low" to "rất thấp",
            "low" to "thấp",
            "medium" to "trung bình",
            "high" to "cao",
        )

        /**
         * How good a voice is expected to sound, for ordering the picker and choosing a default.
         *
         * The tiers are model size, and they are audible: Piper's `low` voices are 16 kHz while
         * `medium` is 22.05 kHz. Ids with no tier are Kokoro's, which are not the small models, so
         * they rank alongside `medium` rather than below everything.
         */
        fun String.qualityRank(): Int = when (substringAfterLast('-', "")) {
            "high" -> 3
            "medium" -> 2
            "low" -> 1
            "x_low" -> 0
            else -> 2
        }
    }
}
