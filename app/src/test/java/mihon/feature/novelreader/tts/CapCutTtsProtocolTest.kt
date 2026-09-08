package mihon.feature.novelreader.tts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapCutTtsProtocolTest {

    private val voice = CapCutTtsProtocol.VOICES.first { it.id == "BV074_streaming" }

    @Test
    fun `catalogue contains only the captured Vietnamese voices`() {
        assertEquals(24, CapCutTtsProtocol.VOICES.size)
        assertEquals(CapCutTtsProtocol.VOICES.size, CapCutTtsProtocol.VOICES.map { it.id }.distinct().size)
        assertTrue(CapCutTtsProtocol.VOICES.any { it.label == "Nhỏ Ngọt Ngào" })
        assertTrue(CapCutTtsProtocol.VOICES.any { it.label == "Thanh Niên Tự Tin" })
        assertFalse(CapCutTtsProtocol.VOICES.any { it.id.startsWith("en_") })
    }

    @Test
    fun `ssml escapes prose and carries the selected speaker resource`() {
        val ssml = CapCutTtsProtocol.ssml("Anh nói: <đi> & chờ 'tôi'.", voice, 1.25f)
        assertTrue("&lt;đi&gt; &amp; chờ &apos;tôi&apos;" in ssml)
        assertTrue("name=\"BV074_streaming\"" in ssml)
        assertTrue("resource_id=\"7102355709945188865\"" in ssml)
        assertTrue("rate=\"1.25\"" in ssml)
    }

    @Test
    fun `new task is compact signed json with CapCut editor identity`() {
        val request = CapCutTtsProtocol.newTask(
            text = "Xin chào.",
            voice = voice,
            rate = 1f,
            nowSeconds = 1_750_000_000,
            bindId = "bind",
            contextId = "context",
            traceId = "1234567890abcdef1234567890abcdef",
        )
        val body = Json.parseToJsonElement(request.body) as JsonObject
        assertEquals("bind", body["bind_id"]?.jsonPrimitive?.content)
        assertTrue("editor-api-sg.capcutapi.com/lv/v1/common_task/new" in request.url)
        assertEquals("359289", request.headers["appid"])
        assertEquals("1750000000", request.headers["device-time"])
        assertEquals(32, request.headers["x-ss-stub"]?.length)
        assertEquals(32, request.headers["sign"]?.length)
        assertNotNull(request.headers["x-tt-trace-id"])
    }

    @Test
    fun `ticket status and nested speech url parse from real response shape`() {
        val create = """{"data":{"tasks":[{"id":"42","token":"secret"}]}}"""
        val payload = """{\"audio_subtitles\":[{\"speech_url\":\"https://cdn.example/voice.mp3\"}]}"""
        val query = """{"data":{"tasks":[{"status":"succeed","payload":"$payload"}]}}"""
        assertEquals(CapCutTtsProtocol.Ticket("42", "secret"), CapCutTtsProtocol.ticket(create))
        assertEquals("succeed", CapCutTtsProtocol.status(query))
        assertEquals("https://cdn.example/voice.mp3", CapCutTtsProtocol.speechUrl(query))
    }
}
