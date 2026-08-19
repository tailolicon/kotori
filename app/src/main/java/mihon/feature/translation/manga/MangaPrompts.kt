package mihon.feature.translation.manga

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mihon.feature.translation.provider.TranslationContext

/** Exact Gemini prompts from Manga-Translator `translator/gemini_translator.py`. */
internal object MangaPrompts {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val LANG_NAMES = mapOf(
        "ja" to "Japanese",
        "zh" to "Chinese",
        "ko" to "Korean",
        "en" to "English",
        "vi" to "Vietnamese",
        "th" to "Thai",
        "id" to "Indonesian",
        "fr" to "French",
        "de" to "German",
        "es" to "Spanish",
        "ru" to "Russian",
    )

    fun batchPrompt(texts: List<String>, context: TranslationContext): String {
        val sourceName = LANG_NAMES[context.sourceLanguage] ?: "Japanese"
        val targetName = LANG_NAMES[context.targetLanguage] ?: "English"
        val styleText = context.styleHint.takeIf { it.isNotBlank() }?.let { "\nStyle instructions: $it" }.orEmpty()
        val payload = buildString {
            append('[')
            texts.forEachIndexed { index, text ->
                if (index > 0) append(',')
                append(JsonPrimitive(text))
            }
            append(']')
        }
        return """
Bạn là chuyên gia dịch manga/comic từ $sourceName sang $targetName.

QUY TẮC DỊCH:
1. ĐÂY LÀ HỘI THOẠI NÓI - phải nghe tự nhiên như người thật nói chuyện
2. TUYỆT ĐỐI KHÔNG dịch word-by-word, phải diễn đạt lại theo cách người Việt nói
3. Giữ nguyên cảm xúc, tính cách nhân vật qua cách dùng từ

HƯỚNG DẪN CHO TIẾNG VIỆT:
- TÊN NHÂN VẬT: GIỮ NGUYÊN tên gốc, KHÔNG dịch nghĩa
  + Nhật: Tanaka, Yamato, Sakura (-san, -kun, -chan, senpai, sensei)
  + Hàn: Kim, Park, Lee, Hyun (sunbae, oppa, hyung, noona)
  + Trung: Lý, Trương, Vương (sư huynh, sư đệ, đại nhân)
  + Có thể Việt hóa nhẹ: Tanaka-san → anh Tanaka, sunbae → tiền bối
- Đại từ nhân xưng: chọn phù hợp với quan hệ (tao/mày, tôi/cậu, anh/em, ông/bà, con/mẹ...)
- Thán từ: dịch tự nhiên (くそ→Đ*t/Chết tiệt, やばい→Toang rồi, すごい→Đỉnh thật, なに→Cái gì)
- Câu ngắn giữ ngắn, đừng thêm thắt dài dòng
- Dùng từ lóng, khẩu ngữ phù hợp ngữ cảnh (oke, ngon, chill, tởm...)
- Câu cảm thán: ôi, trời ơi, ủa, hả, ê, này...
- TRÁNH: dịch kiểu sách giáo khoa, dùng từ Hán Việt quá nhiều, câu dài lê thê$styleText

Input texts (JSON array - mỗi item là 1 bubble):
$payload

IMPORTANT: Trả về ĐÚNG JSON array với bản dịch theo THỨ TỰ GIỐNG HỆT.
Format: ["bản dịch 1", "bản dịch 2", ...]
        """.trimIndent()
    }

    fun parseBatch(raw: String, expected: Int): List<String>? {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val array = runCatching { json.parseToJsonElement(cleaned) }.getOrNull() as? JsonArray
            ?: return null
        val values = array.map { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull.orEmpty()
                else -> element.toString().trim().removeSurrounding("\"")
            }
        }
        if (values.size != expected) return null
        return values
    }
}
