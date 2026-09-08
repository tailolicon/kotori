package mihon.feature.novelreader.tts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Streams the Vietnamese-only CapCut voice catalogue through CapCut's editor TTS service. */
class CapCutTtsEngine(context: Context) : NovelTtsEngine {

    override val id = NovelTtsEngineId.CAPCUT

    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var ready = false

    @Volatile
    private var preferredVoice: String? = null

    @Volatile
    private var generation = 0

    private var handle: SentenceClipPipeline.Handle? = null

    override val isReady: Boolean get() = ready

    override fun voices(): List<NovelVoice> = CapCutTtsProtocol.VOICES.map { voice ->
        NovelVoice(id = voice.id, label = voice.label, downloaded = true, sizeLabel = "CapCut")
    }

    override suspend fun prepare(
        voiceId: String?,
        onProgress: (NovelTtsPreparation) -> Unit,
    ): Boolean {
        onProgress(NovelTtsPreparation.Starting)
        preferredVoice = voiceId
        ready = false

        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val online = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!online) {
            onProgress(NovelTtsPreparation.Failed("Giọng CapCut cần kết nối internet"))
            return false
        }

        return withContext(Dispatchers.IO) {
            runCatching { fetchMp3(PROBE_TEXT, resolve(voiceId), 1f) }
                .onSuccess {
                    ready = true
                    onProgress(NovelTtsPreparation.Ready)
                }
                .onFailure { error ->
                    logcat(LogPriority.WARN, error) { "CapCut TTS unavailable" }
                    onProgress(NovelTtsPreparation.Failed(error.readable()))
                }
                .isSuccess
        }
    }

    override fun speak(
        script: SpeechScript,
        fromIndex: Int,
        rate: Float,
        listener: NovelTtsListener,
    ) {
        if (script.isEmpty) return listener.onFinished()
        stop()
        val active = ++generation
        val voice = resolve(preferredVoice)
        var delivered = false

        handle = SentenceClipPipeline.start(
            script = script,
            fromIndex = fromIndex,
            trackRate = 1f,
            listener = listener,
            active = { active == generation },
            engineName = "CapCut TTS",
        ) { sentence ->
            try {
                val decoded = Mp3Pcm.decode(fetchMp3(sentence.text, voice, rate))
                delivered = true
                SentenceClipPipeline.Clip(sentence, decoded.samples, decoded.sampleRate)
            } catch (error: IOException) {
                if (!delivered) throw error
                logcat(LogPriority.WARN, error) { "CapCut TTS dropped sentence ${sentence.index}" }
                SentenceClipPipeline.Clip(sentence, FloatArray(0), 0)
            }
        }
    }

    override fun stop() {
        generation++
        handle?.interrupt()
        handle = null
    }

    override fun release() {
        stop()
        client.dispatcher.executorService.shutdown()
    }

    private fun resolve(voiceId: String?): CapCutTtsProtocol.Voice =
        CapCutTtsProtocol.VOICES.firstOrNull { it.id == voiceId }
            ?: CapCutTtsProtocol.VOICES.first { it.id == DEFAULT_VOICE }

    private fun fetchMp3(text: String, voice: CapCutTtsProtocol.Voice, rate: Float): ByteArray {
        var last: IOException? = null
        repeat(2) { attempt ->
            try {
                return fetchOnce(text, voice, rate)
            } catch (error: IOException) {
                last = error
                logcat(LogPriority.WARN, error) { "CapCut TTS attempt ${attempt + 1} failed" }
            }
        }
        throw last ?: IOException("CapCut TTS failed")
    }

    private fun fetchOnce(text: String, voice: CapCutTtsProtocol.Voice, rate: Float): ByteArray {
        val created = post(CapCutTtsProtocol.newTask(text, voice, rate))
        val ticket = CapCutTtsProtocol.ticket(created)
            ?: throw IOException("CapCut không tạo được tác vụ giọng đọc")

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(POLL_TIMEOUT_S)
        while (System.nanoTime() < deadline) {
            if (Thread.interrupted()) throw InterruptedException()
            val response = post(CapCutTtsProtocol.queryTask(ticket))
            when (CapCutTtsProtocol.status(response)) {
                "success", "succeed", "done", "finished", "complete" -> {
                    val url = CapCutTtsProtocol.speechUrl(response)
                        ?: throw IOException("CapCut không trả về tệp âm thanh")
                    return download(url)
                }
                "failed", "fail", "error" -> throw IOException("CapCut từ chối tạo giọng đọc")
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw SocketTimeoutException("CapCut phản hồi quá chậm")
    }

    private fun post(spec: CapCutTtsProtocol.RequestSpec): String {
        val request = Request.Builder()
            .url(spec.url)
            .post(spec.body.toRequestBody(JSON))
            .apply { spec.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("CapCut HTTP ${response.code}")
            response.body.string().takeIf(String::isNotBlank)
                ?: throw IOException("CapCut trả về phản hồi trống")
        }
    }

    private fun download(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Không tải được âm thanh CapCut (${response.code})")
            response.body.bytes().takeIf { it.size >= MIN_AUDIO_BYTES }
                ?: throw IOException("Âm thanh CapCut bị trống")
        }
    }

    private fun Throwable.readable(): String {
        val detail = message.orEmpty().lowercase()
        return when {
            this is UnknownHostException -> "Không truy cập được dịch vụ giọng CapCut"
            this is SocketTimeoutException || "timeout" in detail || "quá chậm" in detail ->
                "Giọng CapCut phản hồi quá chậm"
            "401" in detail || "403" in detail || "từ chối" in detail ->
                "Dịch vụ CapCut từ chối yêu cầu — thử giọng khác hoặc thử lại sau"
            else -> "Không dùng được giọng CapCut"
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val DEFAULT_VOICE = "BV074_streaming"
        private const val PROBE_TEXT = "Xin chào."
        private const val POLL_TIMEOUT_S = 30L
        private const val POLL_INTERVAL_MS = 750L
        private const val MIN_AUDIO_BYTES = 64
    }
}
