package mihon.feature.novelreader.tts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher

/**
 * The small, account-free subset of CapCut's editor TTS protocol used by the novel reader.
 *
 * Ported from the MIT-licensed K07VN/capcut-tts-api implementation vendored by OmniCast. Only TTS
 * task creation/querying is carried over: Kotori never uploads media, serializes a CapCut session,
 * or asks for non-Vietnamese voices.
 */
internal object CapCutTtsProtocol {

    data class Voice(val id: String, val label: String, val resourceId: String)

    data class RequestSpec(val url: String, val headers: Map<String, String>, val body: String)

    data class Ticket(val id: String, val token: String)

    /** Every entry tagged vi-VN in OmniCast's captured CapCut Voice.json, and no other locale. */
    val VOICES: List<Voice> = listOf(
        Voice("BV421_vivn_streaming", "Nhỏ Ngọt Ngào", "7252594014782755330"),
        Voice("vi_female_huong", "Giọng Nữ Phổ Thông", "7264854897953083905"),
        Voice("BV074_streaming_dsp", "Giọng Bé", "7550087831092251920"),
        Voice("BV074_streaming", "Cô Gái Hoạt Ngôn", "7102355709945188865"),
        Voice("vi-VN-HoaiMyNeural", "Hoài My", "7371666434650280464"),
        Voice("vi-VN-NamMinhNeural", "Nam Minh", "7371666524727153168"),
        Voice("BV075_streaming_vibrato_dsp", "Việt Méo", "7569450639810465040"),
        Voice("BV562_streaming", "Mai", "7483736254694035984"),
        Voice("multi_female_yangguangnv_uranus_bigtts", "Ban Mai", "7637456432522218773"),
        Voice("multi_female_richgirl_uranus_bigtts", "Review Phim new", "7637460351541447956"),
        Voice("multi_female_quanweinv_uranus_bigtts", "Bản Tin 1", "7637458743197732117"),
        Voice("multi_female_stokie_uranus_bigtts", "Review Phim 4", "7637456729696996628"),
        Voice("multi_female_sisi_uranus_bigtts", "Bản Tin nữ", "7637455857285860629"),
        Voice("multi_female_daqi_uranus_bigtts", "Review Phim 3", "7637451983389019409"),
        Voice("multi_female_xyf04auto_uranus_bigtts", "Review Phim 2", "7637458743197732117"),
        Voice("multi_female_kiwi_uranus_bigtts", "Sunny Idol", "7637457995882089749"),
        Voice("BV075_streaming_demon_dsp", "Kenny Đại Đế", "7569442422665661712"),
        Voice("BV075_streaming_robot_dsp", "Robot VN", "7538698409633516816"),
        Voice("multi_male_felipe_uranus_bigtts", "Giọng Nam Trầm", "7637456729696996628"),
        Voice("multi_female_peiqi_uranus_bigtts", "Giọng Gái Mới Lớn", "7637458789033151751"),
        Voice("multi_female_xinwenjieshuo_uranus_bigtts", "Nam bản tin", "7637455039719640327"),
        Voice("multi_female_tianmeijieshuo_uranus_bigtts", "Quên Tên Tự Test", "7637460417295469832"),
        Voice("BV075_streaming", "Thanh Niên Tự Tin", "7102355803792740865"),
        Voice("BV560_streaming", "Alex Đại Đế", "7483736167565758992"),
    )

    fun newTask(
        text: String,
        voice: Voice,
        rate: Float,
        nowSeconds: Long = System.currentTimeMillis() / 1_000,
        bindId: String = UUID.randomUUID().toString(),
        contextId: String = UUID.randomUUID().toString(),
        traceId: String = UUID.randomUUID().toString().replace("-", ""),
    ): RequestSpec {
        val babi = buildJsonObject {
            put("feature_entrance", "editor")
            put("feature_entrance_detail", "editor-feature-text_to_speech")
            put("feature_key", "text_to_speech")
            put("scenario", "video_editor")
        }.toString()
        val ssml = ssml(text, voice, rate)
        val extraInfo = buildJsonObject { put("benefit_info", buildJsonObject {}) }.toString()
        val payload = buildJsonObject {
            put("audio_format", "mp3")
            put("babi_param", babi)
            put("credit_disable", false)
            put("extra_info", extraInfo)
            put("need_merge_voice", false)
            put("need_subtitle_timestamp", false)
            put("scene", "text_to_speech")
            put("ssml", ssml)
            put("sign", encrypt(signInput(ssml, extraInfo)))
        }
        val body = buildJsonObject {
            put("bind_id", bindId)
            put("can_queue", true)
            put("enter_from", "text_to_speech")
            put(
                "tasks",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("context", contextId)
                            put("payload", payload.toString())
                            put("req_key", REQUEST_KEY)
                            put("task_version", TASK_VERSION)
                        },
                    )
                },
            )
        }.toString()
        return request(
            path = "/lv/v1/common_task/new",
            body = body,
            nowSeconds = nowSeconds,
            traceId = traceId,
            includeRegion = true,
            babi = babi,
        )
    }

    fun queryTask(
        ticket: Ticket,
        nowSeconds: Long = System.currentTimeMillis() / 1_000,
        traceId: String = UUID.randomUUID().toString().replace("-", ""),
    ): RequestSpec {
        val body = buildJsonObject {
            put(
                "tasks",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("bind_id", "")
                            put("id", ticket.id)
                            put("req_key", REQUEST_KEY)
                            put("task_version", TASK_VERSION)
                            put("token", ticket.token)
                        },
                    )
                },
            )
        }.toString()
        return request(
            path = "/lv/v1/common_task/query",
            body = body,
            nowSeconds = nowSeconds,
            traceId = traceId,
            includeRegion = false,
            babi = null,
        )
    }

    fun ticket(response: String): Ticket? = firstTask(response)?.let { task ->
        val id = task.string("id") ?: return@let null
        val token = task.string("token") ?: return@let null
        Ticket(id, token)
    }

    fun status(response: String): String? = firstTask(response)?.string("status")?.lowercase()

    fun speechUrl(response: String): String? {
        val task = firstTask(response) ?: return null
        val payload = when (val raw = task["payload"]) {
            is JsonObject -> raw
            is JsonPrimitive -> raw.contentOrNull?.let(::parseObject)
            else -> null
        } ?: return null
        val subtitles = payload["audio_subtitles"] as? JsonArray
        subtitles.orEmpty().forEach { item ->
            val block = item as? JsonObject ?: return@forEach
            listOf("speech_url", "url", "audio_url").forEach { key ->
                block.string(key)?.takeIf { it.startsWith("http") }?.let { return it }
            }
        }
        return listOf("speech_url", "audio_url", "url")
            .firstNotNullOfOrNull { key -> task.string(key)?.takeIf { it.startsWith("http") } }
    }

    fun ssml(text: String, voice: Voice, rate: Float): String =
        "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"en-US\">\n" +
            "    <voice name=\"${voice.id}\" mock_tone_info=\"\" platform=\"sami\" " +
            "resource_id=\"${voice.resourceId}\" emotion=\"\" emotion_scale=\"0\" style=\"\" role=\"\" " +
            "moyin_emotion=\"\" is_clone_tone=\"false\" need_subtitle_timestamp=\"false\">\n" +
            "        <prosody rate=\"${rate.coerceIn(0.5f, 2f).trimmed()}\">${escapeXml(text)}</prosody>\n" +
            "    </voice>\n" +
            "</speak>"

    fun signInput(ssml: String, extraInfo: String): String =
        "appid:$AID&did:$DEVICE_ID&creditDisable:false&ssml:${md5(ssml)}&extraInfo:$extraInfo"

    private fun request(
        path: String,
        body: String,
        nowSeconds: Long,
        traceId: String,
        includeRegion: Boolean,
        babi: String?,
    ): RequestSpec {
        val url = (BASE_URL + path).toHttpUrl().newBuilder().apply {
            DEVICE_QUERY.forEach { (name, value) -> addQueryParameter(name, value) }
            if (includeRegion) addQueryParameter("region", REGION)
            babi?.let { addQueryParameter("babi_param", it) }
        }.build().toString()
        val trace = traceId.padEnd(32, '0').take(32)
        val headers = linkedMapOf(
            "content-type" to "application/json",
            "appvr" to APP_VERSION,
            "ch" to CHANNEL,
            "device-time" to nowSeconds.toString(),
            "lan" to LANGUAGE,
            "loc" to REGION,
            "pf" to PLATFORM,
            "sign-ver" to "1",
            "tdid" to DEVICE_ID,
            "x-ss-stub" to md5(body),
            "x-ss-dp" to AID,
            "x-khronos" to nowSeconds.toString(),
            "x-tt-trace-id" to "00-$trace-${trace.take(16)}-01",
            "user-agent" to USER_AGENT,
            "store-country-code" to REGION.lowercase(),
            "store-country-code-src" to "did",
            "is-dispatch-us-ttp" to "0",
            "is-app-region-us-ttp" to "0",
            "app-sdk-version" to APP_VERSION,
            "appid" to AID,
            "sign" to md5("9e2c|${path.takeLast(7)}|3|$APP_VERSION|$nowSeconds|$DEVICE_ID|11ac"),
        )
        return RequestSpec(url, headers, body)
    }

    private fun firstTask(response: String): JsonObject? {
        val root = parseObject(response) ?: return null
        val data = root["data"] as? JsonObject ?: return null
        return (data["tasks"] as? JsonArray)?.firstOrNull() as? JsonObject
    }

    private fun parseObject(value: String): JsonObject? =
        runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun escapeXml(text: String): String = buildString(text.length) {
        text.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '\"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                },
            )
        }
    }

    private fun encrypt(value: String): String {
        val der = Base64.getDecoder().decode(
            PUBLIC_KEY.lineSequence().filterNot { it.startsWith("-----") }.joinToString(""),
        )
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun Float.trimmed(): String =
        if (this % 1f == 0f) toInt().toString() + ".0" else toString().trimEnd('0').trimEnd('.')

    private const val BASE_URL = "https://editor-api-sg.capcutapi.com"
    private const val AID = "359289"
    private const val APP_VERSION = "8.7.0"
    private const val CHANNEL = "capcutpc_google"
    private const val DEVICE_ID = "76471456455646328721"
    private const val REGION = "VN"
    private const val LANGUAGE = "vi-VN"
    private const val PLATFORM = "3"
    private const val REQUEST_KEY = "sami_text_to_speech"
    private const val TASK_VERSION = "v3"
    private const val USER_AGENT =
        "Cronet/TTNetVersion:1d7cc3b1 2025-07-16 QuicVersion:52c2b40d 2025-04-03"

    private val DEVICE_QUERY = listOf(
        "app_name" to "CapCut",
        "device_type" to "MacBookPro17,4",
        "os_version" to "15.7.4",
        "channel" to CHANNEL,
        "version_name" to APP_VERSION,
        "device_brand" to "MacBookPro17,4",
        "device_id" to DEVICE_ID,
        "iid" to DEVICE_ID,
        "version_code" to APP_VERSION,
        "device_platform" to "mac",
        "aid" to AID,
    )

    private const val PUBLIC_KEY = """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAmTd34Lw4b7IuldSXh/zY
CMla+ITdGG5TeWz6ad+OySd4r+IrY45AoqrYUxhQ2dl+7z+i7r/5vEa8rr39BYfB
8AGMQLmZA8HmgpWBsqrn/V6daUALkKnkLb70Fn32CJigIuGXAYqxUdGuI340aC+0
v5Es3puJsHyzf01/AelE4Cdc6bZhQrASJLBh8R3BQToYClmDVSDUQk28o8sl/guA
Z4n303Vj+6Siv1HayPCdV6kpVVnMBAG4+umUbwGmn132N3fgpzLarFF3XyWmS1zh
D/J07iM/rP8GDO9IskHNHd2phrO0G6KzrcFAnTBHjVv+hCBEfzN/no3FNA9AuC36
mwIDAQAB
-----END PUBLIC KEY-----"""
}
