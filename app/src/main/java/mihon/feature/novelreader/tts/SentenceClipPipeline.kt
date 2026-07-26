package mihon.feature.novelreader.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The synthesize-ahead player shared by every engine that produces one PCM clip per sentence.
 *
 * Synthesis runs one sentence ahead of playback: enough lookahead to hide synthesis (or network)
 * latency behind the sentence currently playing, small enough that a seek throws away at most one
 * sentence of wasted work. The sentence being reported to the listener is literally the buffer
 * being written to the audio track, which is what keeps the reader's highlight honest.
 *
 * Extracted from the on-device engine when the online one arrived: the two differ only in where a
 * clip's samples come from.
 */
internal object SentenceClipPipeline {

    class Clip(
        val sentence: SpeechSentence?,
        val samples: FloatArray,
        val sampleRate: Int,
    ) {
        companion object {
            val End = Clip(null, FloatArray(0), 0)
        }
    }

    /** The two worker threads of a run, so an engine can interrupt them on stop. */
    class Handle(private val synthesis: Thread, private val playback: Thread) {
        fun interrupt() {
            playback.interrupt()
            synthesis.interrupt()
        }
    }

    /**
     * Starts reading [script] from [fromIndex], fetching each sentence's audio through
     * [synthesize].
     *
     * [active] is polled constantly; the engine flips it to retire the run. [trackRate] is applied
     * as AudioTrack time-stretch — an engine whose rate is already baked into the audio passes 1.
     *
     * [synthesize] runs on the synthesis thread. Returning a clip with empty samples skips the
     * sentence silently (per-sentence resilience is the engine's call); *throwing* ends the run
     * with [NovelTtsListener.onError] — for failures the engine judges fatal, like an online
     * service that cannot be reached at all.
     */
    fun start(
        script: SpeechScript,
        fromIndex: Int,
        trackRate: Float,
        listener: NovelTtsListener,
        active: () -> Boolean,
        engineName: String,
        synthesize: (SpeechSentence) -> Clip,
    ): Handle {
        val clips = ArrayBlockingQueue<Clip>(1)

        val synthesis = Thread(Runnable {
            try {
                script.sentences.drop(fromIndex).forEach { sentence ->
                    if (!active()) return@Runnable
                    val clip = try {
                        synthesize(sentence)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Runnable
                    } catch (error: Throwable) {
                        logcat(LogPriority.ERROR, error) { "$engineName synthesis failed fatally" }
                        if (active()) listener.onError(error.message ?: "Không tổng hợp được giọng đọc")
                        return@Runnable
                    }
                    while (active()) {
                        if (clips.offer(clip, POLL_MS, TimeUnit.MILLISECONDS)) break
                    }
                }
                while (active() && !clips.offer(Clip.End, POLL_MS, TimeUnit.MILLISECONDS)) {
                    // Keep offering the end marker until the player takes it or the run is retired.
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "novel-tts-synth")

        val playback = Thread(Runnable {
            var track: AudioTrack? = null
            try {
                while (active()) {
                    val clip = clips.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                    if (clip === Clip.End) break
                    val sentence = clip.sentence ?: continue
                    listener.onSentenceStarted(sentence.index)
                    if (clip.samples.isEmpty() || clip.sampleRate <= 0) {
                        listener.onSentenceFinished(sentence.index)
                        continue
                    }
                    track = track.reusableFor(clip.sampleRate, trackRate)
                    play(track, clip.samples, active)
                    listener.onSentenceFinished(sentence.index)
                }
                if (active()) listener.onFinished()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                logcat(LogPriority.ERROR, error) { "$engineName playback failed" }
                if (active()) listener.onError(error.message ?: "Không phát được giọng đọc")
            } finally {
                track?.let { open ->
                    runCatching { open.stop() }
                    open.release()
                }
            }
        }, "novel-tts-play")

        synthesis.isDaemon = true
        playback.isDaemon = true
        synthesis.start()
        playback.start()
        return Handle(synthesis, playback)
    }

    /**
     * Reuses the existing track when the sample rate matches, and applies the speaking rate to it.
     *
     * Voices differ in sample rate, so the track cannot simply be built once; but rebuilding it per
     * sentence would add an audible gap, which is exactly what the pipelining exists to avoid.
     *
     * Time-stretching with [AudioTrack.setPlaybackParams] keeps the voice's pitch where the model
     * put it: resampling would turn a slow read into a bass rumble and a fast one into a chipmunk.
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
     * A failure here is cosmetic — the chapter still reads, just at the audio's own pace — so it
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
     * Writes [samples] to [track] and returns once the hardware has actually played them.
     *
     * Waiting for playback rather than for the write to finish is what keeps the sentence highlight
     * honest: `write` returns as soon as the buffer has been handed over, which is up to a sentence
     * early, and returning there would light up the next sentence over audio still playing this one.
     */
    private fun play(track: AudioTrack, samples: FloatArray, active: () -> Boolean) {
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

    private const val POLL_MS = 100L
    private const val DRAIN_TICK_MS = 16L
    private const val BUFFER_MULTIPLIER = 4
    private val DRAIN_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(60)
}
