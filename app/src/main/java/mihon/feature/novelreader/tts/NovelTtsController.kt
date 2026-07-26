package mihon.feature.novelreader.tts

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the reader needs to draw the player and light up the sentence being read. */
data class NovelTtsState(
    val status: NovelTtsStatus = NovelTtsStatus.IDLE,
    val engineId: NovelTtsEngineId = NovelTtsEngineId.SYSTEM,
    val voiceId: String? = null,
    val voices: List<NovelVoice> = emptyList(),
    val rate: Float = 1f,
    /** Index into [SpeechScript.sentences], or -1 when nothing is playing. */
    val sentence: Int = -1,
    val preparation: NovelTtsPreparation? = null,
    val message: String? = null,
) {
    val isActive: Boolean get() = status == NovelTtsStatus.PLAYING || status == NovelTtsStatus.PAUSED
}

enum class NovelTtsStatus {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    ERROR,
}

/**
 * Drives listening for one chapter: owns the engines, holds the playback position and exposes it as
 * Compose state.
 *
 * Position is kept here rather than in an engine because it has to survive things the engines do not
 * share — switching from the system voice to the neural one, or a download completing mid-chapter —
 * and because a seek is expressed the same way regardless of who is speaking: stop, move the index,
 * start again from there. Engines therefore only ever play forward from a given sentence and report
 * where they are; all the position logic lives in one place.
 */
class NovelTtsController(
    context: Context,
    private val preferences: NovelTtsPreferences,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val system by lazy { SystemTtsEngine(appContext) }
    private val neural by lazy { MoonshineTtsEngine(appContext) }
    private val edge by lazy { EdgeTtsEngine(appContext) }

    private var prepareJob: Job? = null
    private var script: SpeechScript = SpeechScript.Empty

    /** Where playback should resume from. Kept apart from [state] so a pause cannot lose it. */
    private var cursor = 0

    /**
     * Engines that have already failed to prepare on their own.
     *
     * Fallback exists in every direction, so a device where no engine can read Vietnamese would
     * otherwise hand the chapter around the circle forever instead of saying so.
     */
    private val failedEngines = mutableSetOf<NovelTtsEngineId>()

    var state by mutableStateOf(
        NovelTtsState(
            engineId = preferences.engine.get(),
            voiceId = preferences.voiceId.get().takeIf(String::isNotEmpty),
            rate = preferences.rate.get(),
        ),
    )
        private set

    private val engine: NovelTtsEngine
        get() = when (state.engineId) {
            NovelTtsEngineId.EDGE -> edge
            NovelTtsEngineId.NEURAL -> neural
            NovelTtsEngineId.SYSTEM -> system
        }

    /** Replaces the chapter being read. Any current playback stops: the text under it is gone. */
    fun setScript(script: SpeechScript) {
        this.script = script
        stop()
    }

    // ============================== Transport ==============================

    fun toggle() {
        when (state.status) {
            NovelTtsStatus.PLAYING -> pause()
            NovelTtsStatus.PREPARING -> Unit
            else -> play(cursor)
        }
    }

    /** Starts (or restarts) at [from], preparing the engine first if it is not ready yet. */
    fun play(from: Int = cursor) {
        if (script.isEmpty) return
        cursor = from.coerceIn(0, script.size - 1)
        prepareJob?.cancel()
        prepareJob = scope.launch {
            val active = engine
            if (!active.isReady) {
                state = state.copy(status = NovelTtsStatus.PREPARING, message = null)
                val ok = active.prepare(state.voiceId) { progress ->
                    scope.launch { onPreparation(progress) }
                }
                // A neural voice that will not load is a bad reason to lose the chapter's audio, so
                // fall back to the system engine rather than leaving the reader with nothing.
                // Any engine can be the one this device cannot use — the online voice needs a
                // network, the on-device one a model download, the system one an installed
                // Vietnamese voice — so a failure moves down the chain rather than ending the
                // chapter's audio.
                if (!ok) {
                    failedEngines += active.id
                    val next = active.id.fallbacks().firstOrNull { it !in failedEngines }
                    if (next != null) {
                        fallBackTo(next)
                        return@launch
                    }
                    state = state.copy(status = NovelTtsStatus.ERROR)
                    return@launch
                }
            }
            state = state.copy(
                status = NovelTtsStatus.PLAYING,
                voices = withContext(Dispatchers.IO) { active.voices() },
                preparation = null,
                message = null,
                sentence = cursor,
            )
            active.speak(script, cursor, state.rate, listener)
        }
    }

    fun pause() {
        engine.stop()
        state = state.copy(status = NovelTtsStatus.PAUSED)
    }

    fun stop() {
        prepareJob?.cancel()
        system.stop()
        neural.stop()
        edge.stop()
        cursor = 0
        state = state.copy(
            status = NovelTtsStatus.IDLE,
            sentence = -1,
            preparation = null,
        )
    }

    /** Jumps to [index] and keeps playing if it already was — the tap-a-sentence gesture. */
    fun seekTo(index: Int) {
        if (script.isEmpty) return
        val target = index.coerceIn(0, script.size - 1)
        cursor = target
        state = state.copy(sentence = target)
        if (state.status == NovelTtsStatus.PLAYING || state.status == NovelTtsStatus.PREPARING) {
            engine.stop()
            play(target)
        } else {
            state = state.copy(status = NovelTtsStatus.PAUSED)
        }
    }

    fun skip(delta: Int) = seekTo(cursor + delta)

    // ============================== Settings ==============================

    fun setRate(value: Float) {
        val rate = value.coerceIn(MIN_RATE, MAX_RATE)
        preferences.rate.set(rate)
        state = state.copy(rate = rate)
        // Rate is baked into synthesis, so a change only takes effect on the next utterance; restart
        // from the current sentence so the listener hears it immediately instead of a minute later.
        if (state.status == NovelTtsStatus.PLAYING) play(cursor)
    }

    fun setEngine(id: NovelTtsEngineId) {
        if (id == state.engineId) return
        val wasPlaying = state.status == NovelTtsStatus.PLAYING
        // An explicit choice re-arms the automatic fallback: the reader may well be switching
        // because they have just fixed what an engine was missing — network, voice, model.
        failedEngines.clear()
        engine.stop()
        preferences.engine.set(id)
        // Voice ids are engine-specific, so carrying one across would ask the new engine for a voice
        // it has never heard of; clearing it lets the engine pick its own default.
        preferences.voiceId.set("")
        state = state.copy(
            engineId = id,
            voiceId = null,
            voices = emptyList(),
            status = NovelTtsStatus.IDLE,
            preparation = null,
            message = null,
        )
        if (wasPlaying) play(cursor)
    }

    fun setVoice(voiceId: String) {
        if (voiceId == state.voiceId) return
        val wasPlaying = state.status == NovelTtsStatus.PLAYING
        val active = engine
        active.stop()
        preferences.voiceId.set(voiceId)
        state = state.copy(voiceId = voiceId, status = NovelTtsStatus.PREPARING, message = null)
        prepareJob?.cancel()
        prepareJob = scope.launch {
            val ok = active.prepare(voiceId) { progress -> scope.launch { onPreparation(progress) } }
            state = state.copy(
                status = if (ok) NovelTtsStatus.PAUSED else NovelTtsStatus.ERROR,
                voices = withContext(Dispatchers.IO) { active.voices() },
                preparation = null,
            )
            if (ok && wasPlaying) play(cursor)
        }
    }

    /** Loads the voice catalogue for the picker without committing to playing anything. */
    fun refreshVoices() {
        scope.launch {
            val active = engine
            val available = withContext(Dispatchers.IO) {
                if (active.isReady) active.voices() else emptyList()
            }
            if (available.isNotEmpty()) state = state.copy(voices = available)
        }
    }

    fun release() {
        prepareJob?.cancel()
        system.release()
        neural.release()
        edge.release()
        scope.cancel()
    }

    // ============================== Engine callbacks ==============================

    private fun onPreparation(progress: NovelTtsPreparation) {
        state = when (progress) {
            is NovelTtsPreparation.Failed -> state.copy(preparation = null, message = progress.message)
            NovelTtsPreparation.Ready -> state.copy(preparation = null)
            else -> state.copy(preparation = progress)
        }
    }

    /** Moves to [id] after the other engine could not be readied, keeping the reason on screen. */
    private fun fallBackTo(id: NovelTtsEngineId) {
        val reason = state.message ?: "${state.engineId.label} chưa dùng được"
        preferences.engine.set(id)
        // Voice ids are engine-specific; carrying one over would ask the new engine for a voice it
        // has never heard of.
        preferences.voiceId.set("")
        state = state.copy(
            engineId = id,
            voiceId = null,
            voices = emptyList(),
            preparation = null,
            message = "$reason — chuyển sang ${id.label.lowercase()}",
        )
        play(cursor)
    }

    private val listener = object : NovelTtsListener {
        override fun onSentenceStarted(index: Int) = post {
            cursor = index
            state = state.copy(status = NovelTtsStatus.PLAYING, sentence = index)
        }

        override fun onSentenceFinished(index: Int) = post {
            // Park the cursor on the next sentence so a pause here resumes forward rather than
            // repeating the line that just finished.
            if (index < script.size - 1) cursor = index + 1
        }

        override fun onFinished() = post {
            cursor = 0
            state = state.copy(status = NovelTtsStatus.IDLE, sentence = -1)
        }

        override fun onError(message: String) = post {
            state = state.copy(status = NovelTtsStatus.ERROR, message = message)
        }

        private fun post(block: () -> Unit) {
            scope.launch { block() }
        }
    }

    companion object {
        const val MIN_RATE = 0.6f
        const val MAX_RATE = 2f
    }
}
