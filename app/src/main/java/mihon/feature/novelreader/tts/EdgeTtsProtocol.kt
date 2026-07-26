package mihon.feature.novelreader.tts

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The wire format of Microsoft Edge's read-aloud service, kept apart from the engine so the parts
 * that can be wrong on their own — the anti-abuse token, the SSML, the rate arithmetic — are plain
 * functions a unit test can pin down.
 *
 * The service is the one Edge's own "Read aloud" uses: a WebSocket that takes SSML and streams back
 * audio. It authenticates with a client token that ships inside Edge plus a proof-of-time hash
 * ([secMsGec]) instead of an account, which is what makes it usable here — but also means Microsoft
 * can change the scheme in any Edge release. The scheme is implemented to match the widely-used
 * `edge-tts` project, which tracks such changes; if voices suddenly stop, look there first.
 */
internal object EdgeTtsProtocol {

    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    /**
     * The Edge release the handshake claims to be.
     *
     * The service gates on it: a version it considers stale is refused with `403 Forbidden` before
     * the WebSocket upgrade, so this has to be kept roughly current. It is the first thing to bump
     * when the engine starts failing to connect.
     */
    private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    private const val CHROMIUM_MAJOR_VERSION = "143"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"

    /** Read-aloud requests carry the identity of Edge's built-in TTS extension. */
    const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    private const val WSS_BASE =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

    /** Seconds between the Windows epoch (1601) and the Unix epoch (1970). */
    private const val WIN_EPOCH_OFFSET_S = 11_644_473_600L

    /** 100-nanosecond ticks per five minutes — the granularity the server rounds to. */
    private const val TICK_WINDOW = 3_000_000_000L

    /**
     * The proof-of-time hash the endpoint requires: SHA-256 of the current Windows file time,
     * rounded down to a five-minute window, concatenated with the client token.
     *
     * Time comes in as a parameter because the caller may need to apply a correction: the server
     * rejects hashes from clocks more than a window out, and phones (emulators especially) drift.
     */
    fun secMsGec(unixMs: Long): String {
        var ticks = (unixMs / 1000 + WIN_EPOCH_OFFSET_S) * 10_000_000L
        ticks -= ticks % TICK_WINDOW
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ticks$TRUSTED_CLIENT_TOKEN".toByteArray())
        return digest.joinToString("") { "%02X".format(it) }
    }

    fun secMsGecVersion(): String = "1-$CHROMIUM_FULL_VERSION"

    fun connectionUrl(unixMs: Long): String =
        WSS_BASE +
            "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&ConnectionId=${requestId()}" +
            "&Sec-MS-GEC=${secMsGec(unixMs)}" +
            "&Sec-MS-GEC-Version=${secMsGecVersion()}"

    fun requestId(): String = UUID.randomUUID().toString().replace("-", "")

    /**
     * First message on every connection: which format the audio should come back in.
     *
     * MP3 rather than raw PCM because the read-aloud endpoint is only known to serve its compressed
     * formats reliably; the engine decodes it on-device.
     */
    fun speechConfigMessage(): String =
        "X-Timestamp:${timestamp()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            """{"context":{"synthesis":{"audio":{"metadataoptions":{""" +
            """"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},""" +
            """"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""

    fun ssmlMessage(requestId: String, ssml: String): String =
        "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            // The trailing Z is not a mistake: Edge itself appends it here and the service expects
            // the malformed value. Removing it is rejected.
            "X-Timestamp:${timestamp()}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml

    fun ssml(text: String, voice: String, rate: Float, pitch: String = "+0Hz"): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='vi-VN'>" +
            "<voice name='$voice'>" +
            "<prosody pitch='$pitch' rate='${ratePercent(rate)}' volume='+0%'>" +
            escape(text) +
            "</prosody></voice></speak>"

    /**
     * The player's 0.6×–2× factor as the signed percentage SSML wants: 1.25 → `+25%`.
     *
     * Rate goes into the SSML rather than into AudioTrack time-stretching because the voice then
     * re-times its own prosody — pauses and emphasis scale naturally instead of the whole waveform
     * being sped up.
     */
    fun ratePercent(rate: Float): String {
        val percent = ((rate - 1f) * 100).roundToInt()
        return if (percent >= 0) "+$percent%" else "$percent%"
    }

    fun escape(text: String): String = buildString(text.length) {
        text.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\'' -> append("&apos;")
                '"' -> append("&quot;")
                else -> append(char)
            }
        }
    }

    /**
     * A JavaScript `Date.toString()` in UTC, which is the shape the service parses.
     *
     * Built explicitly rather than from the platform default because both the locale and the zone
     * matter: a device in Vietnamese locale would otherwise send Vietnamese day names, and one in
     * local time would send an offset the service reads as skew.
     */
    private fun timestamp(): String = SimpleDateFormat(
        "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
