package mihon.feature.translation.provider

/**
 * Prompt text for every provider.
 *
 * The Vietnamese variants are deliberately longer than a generic instruction would be. Vietnamese
 * needs an explicit pronoun decision for almost every line — a translation that defaults to
 * `tôi/bạn` throughout reads unmistakably like machine output — so the prompt spells out the
 * register choices, honorific handling and interjection conventions instead of hoping the model
 * infers them.
 */
internal object TranslationPrompts {

    fun visionBubbles(
        context: TranslationContext,
        imageWidth: Int,
        imageHeight: Int,
        geometry: String,
        boxCount: Int,
    ): String = if (context.targetLanguage == "vi") {
        buildString {
            appendLine("Bạn là dịch giả manga/manhwa/manhua chuyên nghiệp, dịch từ ${context.sourceName} sang tiếng Việt.")
            appendLine()
            appendLine("Ảnh trang truyện: ${imageWidth}x${imageHeight} pixel.")
            appendLine("Mỗi ô thoại được ĐÁNH SỐ bằng nhãn đỏ vẽ ngay góc trên-trái của ô trong ảnh.")
            appendLine("Toạ độ từng ô (tham khảo thêm):")
            appendLine(geometry)
            appendLine()
            appendLine("NHIỆM VỤ: với từng số, đọc text nằm TRONG ô mang số đó, rồi dịch sang tiếng Việt.")
            appendLine("- Ô không có thoại (tranh, credit nhà xuất bản, chữ trang trí) → \"text\": \"\", \"translation\": \"\".")
            appendLine("- TUYỆT ĐỐI không điền lời của ô khác vào một ô. Thà bỏ trống còn hơn điền sai.")
            appendLine()
            append(VIETNAMESE_DIALOGUE_RULES)
            appendStyle(context)
            appendLine()
            append(vietnameseOutputContract(boxCount))
        }
    } else {
        buildString {
            appendLine("You are a professional comics translator working from ${context.sourceName} into ${context.targetName}.")
            appendLine()
            appendLine("Page image: ${imageWidth}x${imageHeight} pixels.")
            appendLine("Each bubble is NUMBERED by a red badge drawn at its top-left corner in the image.")
            appendLine("Box coordinates, for reference:")
            appendLine(geometry)
            appendLine()
            appendLine("TASK: for each number, read the text INSIDE that numbered bubble, then translate it.")
            appendLine("- A region with no dialogue (artwork, publisher credits, decorations) → \"text\": \"\", \"translation\": \"\".")
            appendLine("- NEVER fill a bubble with another bubble's line. An empty answer beats a wrong one.")
            appendLine()
            append(GENERIC_DIALOGUE_RULES)
            appendStyle(context)
            appendLine()
            append(genericOutputContract(boxCount))
        }
    }

    fun bubbleLines(context: TranslationContext, texts: List<String>): String {
        val numbered = texts.withIndex().joinToString("\n") { (index, text) -> "${index + 1}. $text" }
        return if (context.targetLanguage == "vi") {
            buildString {
                appendLine("Bạn là dịch giả manga chuyên nghiệp, dịch từ ${context.sourceName} sang tiếng Việt.")
                appendLine()
                append(VIETNAMESE_DIALOGUE_RULES)
                appendStyle(context)
                appendLine()
                appendLine("Mỗi dòng là một ô thoại (text đã OCR, có thể lẫn ký tự nhiễu), có số thứ tự:")
                appendLine(numbered)
                appendLine()
                append(vietnameseOutputContract(texts.size))
            }
        } else {
            buildString {
                appendLine("You are a professional comics translator from ${context.sourceName} to ${context.targetName}.")
                appendLine()
                append(GENERIC_DIALOGUE_RULES)
                appendStyle(context)
                appendLine()
                appendLine("Each numbered line is one speech bubble (raw OCR, may contain noise):")
                appendLine(numbered)
                appendLine()
                append(genericOutputContract(texts.size))
            }
        }
    }

    /**
     * Output shape for every bubble call.
     *
     * Two details here are load-bearing, and both were added to fix defects seen on real pages:
     *
     *  * **`id` per entry.** A bare array is positional, so a model that merges two bubbles or drops an
     *    empty one silently shifts every later translation into the wrong bubble — the reader sees a
     *    line of dialogue attached to the wrong speaker, several bubbles running. An explicit id lets
     *    the caller place each translation where it belongs and leave the rest blank.
     *  * **A separate `text` field.** Asked to "read the bubble, then translate it" while given only
     *    one string to answer in, models write both halves into it, and the bubble ends up rendered as
     *    `Run!!" -> "Chạy đi!!`. Giving the source text somewhere legitimate to go stops it.
     */
    private fun vietnameseOutputContract(count: Int): String = buildString {
        appendLine("Ô trống, chỉ có hiệu ứng âm thanh không cần dịch, hoặc không đọc được → \"translation\": \"\".")
        appendLine("CHỈ trả về JSON array đúng dạng này, không thêm chữ nào khác:")
        appendLine("""[{"id": 1, "text": "text gốc đọc được", "translation": "bản dịch tiếng Việt"}, ...]""")
        appendLine("- Phải có ĐỦ $count phần tử, id chạy từ 1 đến $count, không bỏ sót, không gộp ô.")
        appendLine("- \"text\" chứa text gốc. \"translation\" CHỈ chứa tiếng Việt.")
        append("- TUYỆT ĐỐI không viết text gốc, dấu \"->\" hay dấu ngoặc kép vào \"translation\".")
    }

    private fun genericOutputContract(count: Int): String = buildString {
        appendLine("Empty, sound-effect-only or unreadable bubbles → \"translation\": \"\".")
        appendLine("Return ONLY a JSON array shaped exactly like this, with no other prose:")
        appendLine("""[{"id": 1, "text": "source text you read", "translation": "your translation"}, ...]""")
        appendLine("- Exactly $count entries, ids 1 through $count, none skipped or merged.")
        appendLine("- \"text\" holds the original. \"translation\" holds ONLY the translation.")
        append("- NEVER put the source text, an \"->\" arrow or quote marks inside \"translation\".")
    }

    fun prose(
        context: TranslationContext,
        prose: ProseContext,
        paragraphs: List<String>,
    ): String {
        val numbered = paragraphs.withIndex().joinToString("\n\n") { (index, text) -> "[$index] $text" }
        val glossary = prose.glossary.entries
            .take(MAX_GLOSSARY_LINES)
            .joinToString("\n") { "- ${it.key} → ${it.value}" }

        return if (context.targetLanguage == "vi") {
            buildString {
                appendLine("Bạn là dịch giả light novel chuyên nghiệp, dịch từ ${context.sourceName} sang tiếng Việt.")
                appendLine("Tác phẩm: ${prose.seriesTitle}")
                appendLine()
                append(VIETNAMESE_PROSE_RULES)
                if (glossary.isNotBlank()) {
                    appendLine()
                    appendLine("THUẬT NGỮ ĐÃ CHỐT (phải dùng đúng, không đổi):")
                    appendLine(glossary)
                }
                if (prose.previousChapterTail.isNotBlank()) {
                    appendLine()
                    appendLine("Đoạn cuối chương trước (chỉ để nắm ngữ cảnh và giọng văn, KHÔNG dịch lại):")
                    appendLine("\"\"\"")
                    appendLine(prose.previousChapterTail.take(MAX_TAIL_CHARS))
                    appendLine("\"\"\"")
                }
                appendStyle(context)
                appendLine()
                appendLine("Các đoạn cần dịch, mỗi đoạn có số thứ tự [n]:")
                appendLine(numbered)
                appendLine()
                appendLine("Trả về JSON object đúng dạng:")
                appendLine("""{"paragraphs": ["bản dịch đoạn 0", "bản dịch đoạn 1", ...], """)
                appendLine(""" "glossary": [{"source": "term gốc", "target": "cách dịch"}]}""")
                appendLine("- Mảng paragraphs phải có ĐÚNG ${paragraphs.size} phần tử, đúng thứ tự.")
                appendLine("- Giữ nguyên đoạn trống thành \"\" thay vì bỏ đi.")
                append("- glossary chỉ thêm tên riêng / thuật ngữ MỚI xuất hiện trong các đoạn trên.")
            }
        } else {
            buildString {
                appendLine("You are a professional light-novel translator from ${context.sourceName} to ${context.targetName}.")
                appendLine("Work: ${prose.seriesTitle}")
                appendLine()
                append(GENERIC_PROSE_RULES)
                if (glossary.isNotBlank()) {
                    appendLine()
                    appendLine("ESTABLISHED TERMS (use exactly, do not change):")
                    appendLine(glossary)
                }
                if (prose.previousChapterTail.isNotBlank()) {
                    appendLine()
                    appendLine("Tail of the previous chapter, for context and voice only — do NOT retranslate:")
                    appendLine("\"\"\"")
                    appendLine(prose.previousChapterTail.take(MAX_TAIL_CHARS))
                    appendLine("\"\"\"")
                }
                appendStyle(context)
                appendLine()
                appendLine("Paragraphs to translate, each prefixed with its index [n]:")
                appendLine(numbered)
                appendLine()
                appendLine("Return a JSON object shaped exactly like:")
                appendLine("""{"paragraphs": ["translation of 0", "translation of 1", ...], """)
                appendLine(""" "glossary": [{"source": "original term", "target": "chosen rendering"}]}""")
                appendLine("- paragraphs must contain EXACTLY ${paragraphs.size} items, in order.")
                appendLine("- Preserve empty paragraphs as \"\" rather than dropping them.")
                append("- glossary should list only NEW proper nouns or terms introduced above.")
            }
        }
    }

    private fun StringBuilder.appendStyle(context: TranslationContext) {
        if (context.styleHint.isNotBlank()) {
            appendLine()
            appendLine("Yêu cầu thêm về văn phong: ${context.styleHint}")
        }
    }

    private const val MAX_GLOSSARY_LINES = 60
    private const val MAX_TAIL_CHARS = 1200

    private val VIETNAMESE_DIALOGUE_RULES = """
        QUY TẮC:
        1. Đây là lời NÓI. Dịch sao cho đọc lên nghe như người Việt nói thật, không phải văn viết.
        2. Không dịch từng chữ. Nắm ý rồi diễn đạt lại.
        3. Đại từ nhân xưng phải chọn theo quan hệ và thái độ nhân vật: tao/mày, tôi/cậu, anh/em,
           ông/bà, con/mẹ, ta/ngươi... Chọn sai đại từ là lỗi nặng nhất.
        4. Tên riêng giữ nguyên. Hậu tố có thể Việt hóa nhẹ: Tanaka-san → anh Tanaka,
           sunbae → tiền bối, shishou → sư phụ.
        5. Câu ngắn giữ ngắn. Ô thoại nhỏ thì càng phải gọn — bản dịch dài sẽ không vừa ô.
        6. Thán từ dịch tự nhiên: やばい → toang rồi, すごい → đỉnh thật, なに → cái gì, くそ → chết tiệt.
        7. Tránh văn phong sách giáo khoa, tránh lạm dụng từ Hán Việt, tránh câu dài lê thê.
        8. CHÍNH TẢ: viết đủ dấu, đúng dấu. Trước khi trả về, đọc lại từng từ một lần nữa và kiểm tra
           dấu thanh cùng dấu mũ. Từ mất dấu hoặc sai dấu là lỗi nghiêm trọng — "khả năng" không được
           thành "kh năng", "như vậy" không được thành "như thậy", "được" không được thành "đuợc".
        9. Chỉ dùng chữ cái tiếng Việt tiền tổ hợp. Không chèn ký tự lạ, không bỏ trống giữa từ.
    """.trimIndent() + "\n"

    private val GENERIC_DIALOGUE_RULES = """
        RULES:
        1. This is spoken dialogue — it must read like speech, not prose.
        2. Translate meaning, not words.
        3. Preserve each character's register, emotion and verbal tics.
        4. Keep proper nouns in their original form.
        5. Short lines stay short; small bubbles cannot hold padded text.
        6. Render interjections idiomatically rather than literally.
    """.trimIndent() + "\n"

    private val VIETNAMESE_PROSE_RULES = """
        QUY TẮC DỊCH VĂN XUÔI:
        1. Dịch như một dịch giả xuất bản, không phải máy: câu văn phải mượt, đúng nhịp tiếng Việt.
        2. Giữ đúng ngôi kể và thời của bản gốc. Không tự đổi ngôi thứ nhất thành thứ ba.
        3. Đại từ nhân xưng nhất quán xuyên suốt cho từng cặp quan hệ. Đã chọn "tôi/cậu" thì
           không được đoạn sau nhảy sang "tớ/bạn".
        4. Độc thoại nội tâm giữ giọng riêng, khác với lời thoại nói ra miệng.
        5. Lời thoại trong ngoặc kép dịch theo văn nói; phần dẫn truyện dịch theo văn viết.
        6. Tên riêng, địa danh, tên chiêu thức, tên tổ chức: giữ nhất quán tuyệt đối với
           danh sách thuật ngữ đã chốt.
        7. Không thêm giải thích, không thêm chú thích của người dịch, không tóm lược.
        8. Onomatopoeia và hiệu ứng âm thanh chuyển thành cách diễn đạt tự nhiên trong tiếng Việt.
        9. Giữ đúng số đoạn. Một đoạn gốc = một đoạn dịch.
    """.trimIndent() + "\n"

    private val GENERIC_PROSE_RULES = """
        PROSE RULES:
        1. Translate like a published literary translator, not a machine.
        2. Preserve narrative person and tense exactly.
        3. Keep register consistent across the whole chapter.
        4. Interior monologue keeps a distinct voice from spoken dialogue.
        5. Proper nouns, place names and technique names must match the established glossary.
        6. Add no explanations, translator's notes or summaries.
        7. Keep the paragraph count identical to the source.
    """.trimIndent() + "\n"
}
