package mihon.feature.novelreader.tts

import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.TextToSpeech
import ai.moonshine.voice.TranscriberOption
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import logcat.LogPriority
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

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
    private var preparedVoice: String? = null

    @Volatile
    private var playback: Thread? = null

    @Volatile
    private var synthesiser: Thread? = null

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
                AssetDownloader().ensureModelPresent(assetRoot, ModelSpec.tts(LANGUAGE, voice)) {
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
            }
            synthesizer?.close()
            synthesizer = TextToSpeech(
                LANGUAGE,
                assetRoot.absolutePath,
                listOf(TranscriberOption(VOICE, voice)),
            )
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
        // One clip of lookahead: enough to hide synthesis latency behind the previous sentence,
        // small enough that a seek throws away at most one sentence of wasted work.
        val clips = ArrayBlockingQueue<Clip>(1)

        val synthesis = Thread(Runnable {
            try {
                script.sentences.drop(fromIndex).forEach { sentence ->
                    if (active != generation) return@Runnable
                    // A sentence the model chokes on becomes a silent clip rather than an error:
                    // one bad line must not end the chapter's playback.
                    val clip = runCatching {
                        val result = engine.synthesize(sentence.text)
                        Clip(sentence, result.samples ?: FloatArray(0), result.sampleRateHz)
                    }.getOrElse {
                        logcat(LogPriority.WARN, it) { "Moonshine failed on sentence ${sentence.index}" }
                        Clip(sentence, FloatArray(0), 0)
                    }
                    while (active == generation) {
                        if (clips.offer(clip, POLL_MS, TimeUnit.MILLISECONDS)) break
                    }
                }
                while (active == generation && !clips.offer(Clip.End, POLL_MS, TimeUnit.MILLISECONDS)) {
                    // Keep offering the end marker until the player takes it or this run is retired.
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "novel-tts-synth")

        val player = Thread(Runnable {
            var track: AudioTrack? = null
            try {
                while (active == generation) {
                    val clip = clips.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                    if (clip === Clip.End) break
                    val sentence = clip.sentence ?: continue
                    listener.onSentenceStarted(sentence.index)
                    if (clip.samples.isEmpty() || clip.sampleRate <= 0) {
                        listener.onSentenceFinished(sentence.index)
                        continue
                    }
                    track = track.reusableFor(clip.sampleRate, rate)
                    play(track, clip) { active == generation }
                    listener.onSentenceFinished(sentence.index)
                }
                if (active == generation) listener.onFinished()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                logcat(LogPriority.ERROR, error) { "Moonshine playback failed" }
                if (active == generation) listener.onError(error.message ?: "Không phát được giọng AI")
            } finally {
                track?.let { open ->
                    runCatching { open.stop() }
                    open.release()
                }
            }
        }, "novel-tts-play")

        synthesis.isDaemon = true
        player.isDaemon = true
        synthesis.start()
        player.start()
        playback = player
        synthesiser = synthesis
    }

    /**
     * Writes [clip] to [track] and returns once the hardware has actually played it.
     *
     * Waiting for playback rather than for the write to finish is what keeps the sentence highlight
     * honest: `write` returns as soon as the buffer has been handed over, which is up to a sentence
     * early, and returning there would light up the next sentence over audio still playing this one.
     */
    private fun play(track: AudioTrack, clip: Clip, active: () -> Boolean) {
        val samples = clip.samples
        track.play()
        var offset = 0
        while (offset < samples.size && active()) {
            val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) throw IllegalStateException("AudioTrack.write returned $written")
            offset += written
            if (written == 0) Thread.sleep(DRAIN_TICK_MS)
        }
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_NS
        while (active() && System.nanoTime() < deadline) {
            if (track.playbackHeadPosition >= samples.size - 1) break
            Thread.sleep(DRAIN_TICK_MS)
        }
        track.stop()
        track.flush()
    }

    /**
     * Reuses the existing track when the sample rate matches, and applies the speaking rate to it.
     *
     * Voices differ in sample rate, so the track cannot simply be built once; but rebuilding it per
     * sentence would add an audible gap, which is exactly what the pipelining above exists to avoid.
     *
     * Rate lives here rather than in synthesis because Moonshine's `synthesize` ignores per-call
     * options: [AudioTrack.setPlaybackParams] time-stretches instead, which keeps the voice's pitch
     * where the model put it. Resampling would work too but would turn a slow read into a bass
     * rumble and a fast one into a chipmunk.
     */
    private fun AudioTrack?.reusableFor(sampleRate: Int, rate: Float): AudioTrack {
        if (this != null && this.sampleRate == sampleRate) return applyRate(rate)
        this?.let { stale ->
            runCatching { stale.stop() }
            stale.release()
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        require(minBuffer > 0) { "AudioTrack.getMinBufferSize failed for $sampleRate Hz" }
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * BUFFER_MULTIPLIER)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .applyRate(rate)
    }

    /**
     * Sets the track's speaking rate, leaving it alone if the device refuses the value.
     *
     * A failure here is cosmetic — the chapter still reads, just at the model's own pace — so it
     * must not take down playback.
     */
    private fun AudioTrack.applyRate(rate: Float): AudioTrack = apply {
        // Reading the speed back can itself throw when the track has never had one set, so the
        // whole round trip is guarded rather than just the write.
        runCatching {
            if (playbackParams.speed != rate) playbackParams = playbackParams.setSpeed(rate)
        }.onFailure { logcat(LogPriority.WARN, it) { "AudioTrack refused speed $rate" } }
    }

    /**
     * Retires the current run.
     *
     * The counter is bumped first so both workers see themselves as stale before they are nudged;
     * interrupting only wakes them from a poll or a sleep, it does not decide anything.
     */
    override fun stop() {
        generation++
        playback?.interrupt()
        synthesiser?.interrupt()
        playback = null
        synthesiser = null
    }

    override fun release() {
        stop()
        runCatching { synthesizer?.close() }
        synthesizer = null
        preparedVoice = null
    }

    /** A synthesized sentence waiting to be played; [End] closes the queue. */
    private class Clip(
        val sentence: SpeechSentence?,
        val samples: FloatArray,
        val sampleRate: Int,
    ) {
        companion object {
            val End = Clip(null, FloatArray(0), 0)
        }
    }

    private companion object {
        const val ASSET_DIR = "novel-tts"
        const val LANGUAGE = "vi"
        const val G2P_ROOT = "g2p_root"
        const val VOICE = "voice"
        const val POLL_MS = 100L
        const val DRAIN_TICK_MS = 16L
        const val BUFFER_MULTIPLIER = 4
        val DRAIN_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(60)

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
