package mihon.feature.novelreader.tts

/**
 * A voice the listener can pick.
 *
 * [downloaded] exists because the neural engine's voices are fetched on demand: the picker has to
 * be able to show a voice that is available *after* a download without pretending it is playable
 * right now.
 */
data class NovelVoice(
    val id: String,
    val label: String,
    val downloaded: Boolean,
    val sizeLabel: String? = null,
)

/** What the engine is doing while it gets itself ready, so the UI can say something truthful. */
sealed interface NovelTtsPreparation {
    data object Starting : NovelTtsPreparation
    data class Downloading(val file: String, val index: Int, val total: Int, val fraction: Float?) :
        NovelTtsPreparation
    data object Ready : NovelTtsPreparation
    data class Failed(val message: String) : NovelTtsPreparation
}

/**
 * Callbacks an engine raises as it reads, driving the highlight.
 *
 * All of these arrive on a background thread; the controller marshals them to the main thread. They
 * are deliberately expressed in script coordinates rather than engine ones, so the reader never has
 * to know which engine is speaking.
 *
 * The sentence is the only unit reported. A finer-grained one is available from the system engine
 * and estimable from the neural one, but it made the highlight twitch word by word while the audio
 * moved a sentence at a time, and it repainted the whole chapter on every word to do it.
 */
interface NovelTtsListener {
    /** [index] into [SpeechScript.sentences] began playing. */
    fun onSentenceStarted(index: Int)

    /** [index] finished; the next sentence follows unless this was the last. */
    fun onSentenceFinished(index: Int)

    /** The queue ran out — playback is over. */
    fun onFinished()

    fun onError(message: String)
}

/**
 * A speech backend for the novel reader.
 *
 * Two exist and they differ enormously in cost and quality, which is exactly why the reader talks to
 * this interface instead of to either one: the system engine is instant, free and often robotic,
 * while the neural engine sounds far better but has to fetch a model first. The reader can offer
 * both, start on whichever is ready, and fall back without any of the calling code changing.
 */
interface NovelTtsEngine {

    val id: NovelTtsEngineId

    /** True once [prepare] has succeeded and [speak] will produce audio. */
    val isReady: Boolean

    /** Voices for the reader's language; empty until [prepare] has run. */
    fun voices(): List<NovelVoice>

    /**
     * Gets the engine into a state where it can speak, reporting progress as it goes.
     *
     * Suspending and idempotent: calling it again for a voice that is already in place is cheap, so
     * the caller may re-prepare whenever the chosen voice changes without special-casing.
     */
    suspend fun prepare(voiceId: String?, onProgress: (NovelTtsPreparation) -> Unit): Boolean

    /**
     * Reads [script] from [fromIndex] onwards at [rate], reporting to [listener].
     *
     * Implementations must return promptly rather than blocking until the chapter is read; playback
     * belongs on the engine's own threads.
     */
    fun speak(script: SpeechScript, fromIndex: Int, rate: Float, listener: NovelTtsListener)

    /** Halts playback and abandons anything queued. Safe to call when nothing is playing. */
    fun stop()

    /** Frees native and system resources. The engine is unusable afterwards. */
    fun release()
}

enum class NovelTtsEngineId(val label: String) {
    /** Android's own [android.speech.tts.TextToSpeech]; whatever voice the device has. */
    SYSTEM("Giọng hệ thống"),

    /** Moonshine's on-device neural voices — works offline, but the model must be downloaded. */
    NEURAL("Giọng AI offline"),

    /** Microsoft's neural voices, streamed — by far the best Vietnamese, but needs the network. */
    EDGE("Giọng mạng (hay nhất)");

    /**
     * Engines to try, best first, when this one cannot be readied.
     *
     * Each engine has a failure mode the others don't share — the online one needs a network, the
     * on-device one a model download, the system one an installed Vietnamese voice — so the chain
     * covers all of them before giving up.
     */
    fun fallbacks(): List<NovelTtsEngineId> = when (this) {
        EDGE -> listOf(NEURAL, SYSTEM)
        NEURAL -> listOf(EDGE, SYSTEM)
        SYSTEM -> listOf(EDGE, NEURAL)
    }
}
