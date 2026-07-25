package mihon.feature.novelreader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Speech through Android's own engine.
 *
 * Always available and instant to start, which is why it stays the default and the fallback, but its
 * quality is entirely the device's: a phone with Google's neural vi-VN voices sounds good, one
 * without sounds like a satnav. The one thing it does better than the neural engine is word timing —
 * [UtteranceProgressListener.onRangeStart] reports the exact character span being spoken, so the
 * karaoke highlight here is measured rather than estimated.
 */
class SystemTtsEngine(context: Context) : NovelTtsEngine {

    override val id = NovelTtsEngineId.SYSTEM

    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    private var initialised = false

    private var script: SpeechScript = SpeechScript.Empty
    private var listener: NovelTtsListener? = null

    /**
     * Bumped on every stop, pause and seek. Utterance callbacks carry the session they were queued
     * in, so a late callback from an abandoned queue — which Android does deliver — is recognised
     * and ignored instead of moving the highlight backwards.
     */
    private var session = 0

    override val isReady: Boolean get() = initialised

    override fun voices(): List<NovelVoice> {
        val available = engine?.voices ?: return emptyList()
        return available
            .filter { it.locale.language == VIETNAMESE.language && !it.isNetworkConnectionRequired }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
            .map { voice -> NovelVoice(id = voice.name, label = voice.label(), downloaded = true) }
    }

    override suspend fun prepare(
        voiceId: String?,
        onProgress: (NovelTtsPreparation) -> Unit,
    ): Boolean {
        onProgress(NovelTtsPreparation.Starting)
        val ready = initialised || awaitInit()
        if (!ready) {
            onProgress(NovelTtsPreparation.Failed("Thiết bị chưa cài giọng đọc nào"))
            return false
        }
        applyVoice(voiceId)
        onProgress(NovelTtsPreparation.Ready)
        return true
    }

    /**
     * The init callback can fire before the constructor returns, so it configures the instance it is
     * handed rather than the field — reading `engine` there would race with the assignment below and
     * silently leave the engine on the default locale with no progress listener.
     */
    private suspend fun awaitInit(): Boolean = suspendCancellableCoroutine { continuation ->
        lateinit var created: TextToSpeech
        created = TextToSpeech(appContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                created.language =
                    if (created.isLanguageAvailable(VIETNAMESE) >= TextToSpeech.LANG_AVAILABLE) {
                        VIETNAMESE
                    } else {
                        Locale.getDefault()
                    }
                created.setOnUtteranceProgressListener(progressListener)
                engine = created
                initialised = true
            }
            if (continuation.isActive) continuation.resume(ok)
        }
        continuation.invokeOnCancellation { created.shutdown() }
    }

    private fun applyVoice(voiceId: String?) {
        val target = voiceId ?: return
        engine?.voices?.firstOrNull { it.name == target }?.let { engine?.voice = it }
    }

    override fun speak(
        script: SpeechScript,
        fromIndex: Int,
        rate: Float,
        listener: NovelTtsListener,
    ) {
        val tts = engine ?: return listener.onError("Giọng hệ thống chưa sẵn sàng")
        if (script.isEmpty) return listener.onFinished()

        this.script = script
        this.listener = listener
        session++
        val active = session

        tts.setSpeechRate(rate)
        // A slightly lowered pitch reads as narration rather than announcement, which is what a
        // novel wants; the difference is audible on the flat default voices in particular.
        tts.setPitch(NARRATION_PITCH)

        script.sentences.drop(fromIndex).forEachIndexed { offset, sentence ->
            tts.speak(
                sentence.text,
                if (offset == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                Bundle(),
                utteranceId(active, sentence.index),
            )
        }
    }

    override fun stop() {
        session++
        engine?.stop()
    }

    override fun release() {
        session++
        listener = null
        engine?.stop()
        engine?.shutdown()
        engine = null
        initialised = false
    }

    private val progressListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            val (utteranceSession, index) = utteranceId.parse() ?: return
            if (utteranceSession != session) return
            listener?.onSentenceStarted(index)
        }

        /**
         * The engine reports the character span it is about to speak, which is exactly the karaoke
         * highlight — no estimation needed. The span is in sentence coordinates because each
         * sentence was queued as its own utterance.
         */
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val (utteranceSession, index) = utteranceId.parse() ?: return
            if (utteranceSession != session) return
            val sentence = script[index] ?: return
            val word = sentence.words.indexOfFirst { start < it.last + 1 && end > it.first }
            listener?.onWordSpoken(index, word)
        }

        override fun onDone(utteranceId: String?) {
            val (utteranceSession, index) = utteranceId.parse() ?: return
            if (utteranceSession != session) return
            listener?.onSentenceFinished(index)
            if (index == script.sentences.lastIndex) listener?.onFinished()
        }

        @Deprecated("Deprecated in Android")
        override fun onError(utteranceId: String?) = reportError(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = reportError(utteranceId)

        private fun reportError(utteranceId: String?) {
            val utteranceSession = utteranceId.parse()?.first ?: return
            if (utteranceSession != session) return
            listener?.onError("Giọng hệ thống đọc lỗi")
        }
    }

    private fun utteranceId(session: Int, index: Int) = "$PREFIX:$session:$index"

    private fun String?.parse(): Pair<Int, Int>? {
        val parts = this?.split(':') ?: return null
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val session = parts[1].toIntOrNull() ?: return null
        val index = parts[2].toIntOrNull() ?: return null
        return session to index
    }

    private fun Voice.label(): String {
        val quality = when {
            quality >= Voice.QUALITY_VERY_HIGH -> "rất cao"
            quality >= Voice.QUALITY_HIGH -> "cao"
            quality >= Voice.QUALITY_NORMAL -> "trung bình"
            else -> "thấp"
        }
        return "${name.substringAfterLast('#').ifEmpty { name }} · $quality"
    }

    private companion object {
        const val PREFIX = "novel"
        const val NARRATION_PITCH = 0.96f
        val VIETNAMESE: Locale = Locale.forLanguageTag("vi-VN")
    }
}
