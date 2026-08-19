package mihon.feature.translation.manga

/**
 * Faithful port of `manga_ocr.ocr.post_process` plus the jaconv.h2z(ascii, digit) it ends with.
 */
internal object MangaOcrPostProcess {

    fun apply(text: String): String {
        var value = text.filterNot { it.isWhitespace() }.replace("…", "...")
        value = DOTS.replace(value) { match -> ".".repeat(match.value.length) }
        return h2z(value)
    }

    /**
     * `jaconv.h2z(text, kana=True, ascii=True, digit=True)`: dakuten pairs first, then H2Z_ALL.
     */
    fun h2z(text: String): String {
        var value = text
        DAKUTEN.forEach { (half, full) -> value = value.replace(half, full) }
        if (value.none { map.containsKey(it.code) }) return value
        val out = StringBuilder(value.length)
        for (ch in value) {
            val mapped = map[ch.code]
            if (mapped != null) out.append(mapped.toChar()) else out.append(ch)
        }
        return out.toString()
    }

    fun decodeTokens(ids: List<Int>, vocab: List<String>): String {
        val out = StringBuilder()
        for (id in ids) {
            if (id in SPECIAL_TOKEN_IDS) continue
            val token = vocab.getOrNull(id) ?: continue
            if (token.isEmpty() || token in SPECIAL_TOKEN_STRINGS) continue
            if (token.startsWith("##")) out.append(token, 2, token.length) else out.append(token)
        }
        return out.toString()
    }

    fun looksJapanese(text: String): Boolean {
        var kana = 0
        var han = 0
        var hangul = 0
        for (ch in text) {
            val cp = ch.code
            when {
                cp in 0x3040..0x30FF -> kana++
                cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF -> han++
                cp in 0x1100..0x11FF || cp in 0xAC00..0xD7AF -> hangul++
            }
        }
        if (hangul > 0) return false
        if (kana >= 2) return true
        if (kana >= 1 && han >= 1) return true
        return han >= 2 && kana + han >= 3
    }

    private val map: Map<Int, Int> by lazy {
        val pairs = MangaH2zTable.pairs
        val out = HashMap<Int, Int>(pairs.size / 2)
        var i = 0
        while (i + 1 < pairs.size) {
            out[pairs[i]] = pairs[i + 1]
            i += 2
        }
        out
    }

    private val DOTS = Regex("[・.]{2,}")

    private val DAKUTEN = listOf(
        "ｶﾞ" to "ガ", "ｷﾞ" to "ギ", "ｸﾞ" to "グ", "ｹﾞ" to "ゲ", "ｺﾞ" to "ゴ",
        "ｻﾞ" to "ザ", "ｼﾞ" to "ジ", "ｽﾞ" to "ズ", "ｾﾞ" to "ゼ", "ｿﾞ" to "ゾ",
        "ﾀﾞ" to "ダ", "ﾁﾞ" to "ヂ", "ﾂﾞ" to "ヅ", "ﾃﾞ" to "デ", "ﾄﾞ" to "ド",
        "ﾊﾞ" to "バ", "ﾋﾞ" to "ビ", "ﾌﾞ" to "ブ", "ﾍﾞ" to "ベ", "ﾎﾞ" to "ボ",
        "ﾊﾟ" to "パ", "ﾋﾟ" to "ピ", "ﾌﾟ" to "プ", "ﾍﾟ" to "ペ", "ﾎﾟ" to "ポ",
        "ｳﾞ" to "ヴ",
    )

    val SPECIAL_TOKEN_IDS = setOf(0, 1, 2, 3, 4)
    private val SPECIAL_TOKEN_STRINGS = setOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]")

    const val DECODER_START = 2
    const val EOS = 3
    const val MAX_LENGTH = 300
}
