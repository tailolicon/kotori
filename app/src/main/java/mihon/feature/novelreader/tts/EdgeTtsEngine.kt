package mihon.feature.novelreader.tts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Microsoft's neural voices, streamed from the service Edge's own "Read aloud" uses.
 *
 * This engine exists because it is the only one whose Vietnamese actually sounds like a person: the
 * on-device models read Vietnamese at satnav quality at best, while HoaiMy and NamMinh are the same
 * voices Windows narrates with, and the multilingual set reads Vietnamese with real prosody. The
 * price is the network — nothing is downloaded, every sentence is fetched as it is about to be
 * heard — so this engine refuses to prepare when offline and the controller falls back to the
 * on-device ones.
 *
 * The endpoint is unofficial in the way Edge itself uses it: authenticated by a shipped client
 * token and a proof-of-time hash rather than an account. Microsoft occasionally rotates the scheme;
 * [EdgeTtsProtocol] documents where to look when that happens.
 */
class EdgeTtsEngine(context: Context) : NovelTtsEngine {

    override val id = NovelTtsEngineId.EDGE

    private val appContext = context.applicationContext

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Milliseconds to add to the device clock when talking to the service.
     *
     * The proof-of-time hash is only valid within a five-minute window of the server's clock, and
     * device clocks — emulators above all — drift. The correction is learned from the `Date` header
     * of a rejected handshake and applied from then on.
     */
    @Volatile
    private var clockSkewMs = 0L

    @Volatile
    private var ready = false

    /** The voice chosen in the picker, captured on [prepare] — the controller re-prepares on every
     * voice change, so this and the picker cannot disagree. */
    @Volatile
    private var preferredVoice: String? = null

    @Volatile
    private var generation = 0

    private var handle: SentenceClipPipeline.Handle? = null

    override val isReady: Boolean get() = ready

    /**
     * Every Microsoft voice that reads Vietnamese: the two native ones first — they carry the
     * accent — then the multilingual voices, which read Vietnamese with a trace of their own but
     * with the most expressive delivery the service has. Nothing is downloaded, so every voice is
     * immediately playable.
     */
    override fun voices(): List<NovelVoice> = VOICES.map { (voiceId, label) ->
        NovelVoice(id = voiceId, label = label, downloaded = true)
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
            onProgress(NovelTtsPreparation.Failed("Giọng mạng cần kết nối internet"))
            return false
        }

        // Actually synthesize something rather than just checking for a network. The service can
        // refuse a handshake that a connectivity check says should work — it gates on the Edge
        // version this claims to be — and finding that out here is what lets the controller fall
        // back to an on-device voice, instead of the reader pressing play and getting silence.
        return withContext(Dispatchers.IO) {
            runCatching { fetchMp3(PROBE_TEXT, voiceId ?: VOICES.keys.first(), rate = 1f) }
                .onSuccess {
                    ready = true
                    onProgress(NovelTtsPreparation.Ready)
                }
                .onFailure { error ->
                    logcat(LogPriority.WARN, error) { "Edge TTS unavailable" }
                    onProgress(NovelTtsPreparation.Failed(error.readable()))
                }
                .isSuccess
        }
    }

    /**
     * What to tell the reader when the service will not talk to us.
     *
     * The raw exception is a transport detail — "Expected HTTP 101 response but was '403
     * Forbidden'" says nothing to someone who wants to listen to a novel — so the shapes that
     * actually occur are named, and anything else falls back to a plain statement.
     */
    private fun Throwable.readable(): String {
        val detail = message.orEmpty()
        return when {
            "403" in detail || "401" in detail ->
                "Dịch vụ giọng Microsoft từ chối kết nối — thử lại sau hoặc dùng giọng khác"
            "timeout" in detail.lowercase() || this is java.net.SocketTimeoutException ->
                "Giọng mạng phản hồi quá chậm"
            this is java.net.UnknownHostException ->
                "Không truy cập được dịch vụ giọng Microsoft"
            else -> "Không dùng được giọng mạng"
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
        val voice = VOICES.keys.firstOrNull { it == preferredVoice } ?: VOICES.keys.first()
        var delivered = false

        handle = SentenceClipPipeline.start(
            script = script,
            fromIndex = fromIndex,
            // The rate is baked into the SSML so the voice re-times its own prosody; stretching
            // the waveform here as well would apply it twice.
            trackRate = 1f,
            listener = listener,
            active = { active == generation },
            engineName = "Edge TTS",
        ) { sentence ->
            try {
                val mp3 = fetchMp3(sentence.text, voice, rate)
                val pcm = Mp3Pcm.decode(mp3)
                delivered = true
                SentenceClipPipeline.Clip(sentence, pcm.samples, pcm.sampleRate)
            } catch (error: IOException) {
                // A blip mid-chapter skips one sentence; a service that has produced nothing at
                // all is dead and the run should say so instead of playing minutes of silence.
                if (!delivered) throw error
                logcat(LogPriority.WARN, error) { "Edge TTS dropped sentence ${sentence.index}" }
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

    /**
     * Synthesizes one sentence over a fresh WebSocket, retrying once with a corrected clock.
     *
     * A connection per sentence mirrors how the service is meant to be used (Edge opens one per
     * utterance) and keeps failure isolated; the pipeline's lookahead hides the handshake latency.
     */
    private fun fetchMp3(text: String, voice: String, rate: Float): ByteArray {
        var lastError: IOException? = null
        repeat(2) { attempt ->
            try {
                return requestOnce(text, voice, rate)
            } catch (error: IOException) {
                lastError = error
                logcat(LogPriority.WARN, error) { "Edge TTS attempt ${attempt + 1} failed" }
            }
        }
        throw lastError ?: IOException("Edge TTS failed")
    }

    private fun requestOnce(text: String, voice: String, rate: Float): ByteArray {
        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var failure: IOException? = null

        val request = Request.Builder()
            .url(EdgeTtsProtocol.connectionUrl(System.currentTimeMillis() + clockSkewMs))
            .header("User-Agent", EdgeTtsProtocol.USER_AGENT)
            .header("Origin", EdgeTtsProtocol.ORIGIN)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(EdgeTtsProtocol.speechConfigMessage())
                    webSocket.send(
                        EdgeTtsProtocol.ssmlMessage(
                            EdgeTtsProtocol.requestId(),
                            EdgeTtsProtocol.ssml(text, voice, rate),
                        ),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        webSocket.close(1000, null)
                        done.countDown()
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Binary frames open with a big-endian header length, then the headers, then
                    // the payload; only frames whose headers name the audio path carry sound.
                    if (bytes.size < 2) return
                    val headerLength = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                    if (bytes.size < 2 + headerLength) return
                    val headers = bytes.substring(2, 2 + headerLength).utf8()
                    if (headers.contains("Path:audio")) {
                        audio.write(bytes.substring(2 + headerLength).toByteArray())
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // A rejected handshake carries the server's clock; learning the skew is what
                    // lets the retry succeed on devices whose clock is minutes out.
                    response?.header("Date")?.let { date ->
                        runCatching {
                            @Suppress("DEPRECATION")
                            clockSkewMs = Date(date).time - System.currentTimeMillis()
                        }
                    }
                    failure = t as? IOException ?: IOException(t.message, t)
                    done.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    done.countDown()
                }
            },
        )

        if (!done.await(REQUEST_TIMEOUT_S, TimeUnit.SECONDS)) {
            socket.cancel()
            throw IOException("Giọng mạng không phản hồi")
        }
        failure?.let { throw it }
        if (audio.size() == 0) throw IOException("Giọng mạng không trả về âm thanh")
        return audio.toByteArray()
    }

    companion object {
        private const val REQUEST_TIMEOUT_S = 20L

        /** Shortest thing worth synthesizing to prove the service is reachable. */
        private const val PROBE_TEXT = "."

        /** Voice id → picker label, in the order the picker should offer them. */
        private val VOICES: Map<String, String> = linkedMapOf(
            "vi-VN-HoaiMyNeural" to "Hoài My · Nữ · Tiếng Việt",
            "vi-VN-NamMinhNeural" to "Nam Minh · Nam · Tiếng Việt",
            "en-US-AvaMultilingualNeural" to "Ava · Nữ · Đa ngôn ngữ",
            "en-US-EmmaMultilingualNeural" to "Emma · Nữ · Đa ngôn ngữ",
            "en-US-AndrewMultilingualNeural" to "Andrew · Nam · Đa ngôn ngữ",
            "en-US-BrianMultilingualNeural" to "Brian · Nam · Đa ngôn ngữ",
            "de-DE-SeraphinaMultilingualNeural" to "Seraphina · Nữ · Đa ngôn ngữ",
            "fr-FR-VivienneMultilingualNeural" to "Vivienne · Nữ · Đa ngôn ngữ",
            "pt-BR-ThalitaMultilingualNeural" to "Thalita · Nữ · Đa ngôn ngữ",
            "de-DE-FlorianMultilingualNeural" to "Florian · Nam · Đa ngôn ngữ",
            "fr-FR-RemyMultilingualNeural" to "Rémy · Nam · Đa ngôn ngữ",
            "it-IT-GiuseppeMultilingualNeural" to "Giuseppe · Nam · Đa ngôn ngữ",
            "ko-KR-HyunsuMultilingualNeural" to "Hyunsu · Nam · Đa ngôn ngữ",
            "en-AU-WilliamMultilingualNeural" to "William · Nam · Đa ngôn ngữ",
        )
    }

}
