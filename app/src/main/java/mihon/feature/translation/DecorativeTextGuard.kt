package mihon.feature.translation

/**
 * Distinguishes large dialogue captions from chapter titles and oversized sound effects.
 *
 * Long-strip comics often omit a visible balloon outline. Those captions enter the pipeline as
 * synthetic text blocks, so size alone is not enough to call them decorative artwork.
 */
internal object DecorativeTextGuard {
    fun shouldDrop(
        isTextBlock: Boolean,
        text: String,
        lineHeights: List<Int>,
        boxWidth: Int,
        boxHeight: Int,
        pageWidth: Int,
    ): Boolean {
        if (!isTextBlock || pageWidth <= 0) return false
        val heights = lineHeights.filter { it > 0 }.sorted()
        if (heights.isEmpty()) return false

        val medianHeight = heights[heights.size / 2]
        val largeType = medianHeight >= maxOf(
            MIN_DISPLAY_TEXT_HEIGHT,
            (pageWidth * DISPLAY_TEXT_HEIGHT_RATIO).toInt(),
        )
        val area = boxWidth.toLong() * boxHeight
        val substantial = area >= pageWidth.toLong() * pageWidth * DISPLAY_TEXT_AREA_RATIO
        val wordCount = WORD.findAll(text.uppercase()).count()
        val compactTitleType = medianHeight >= maxOf(
            MIN_COMPACT_TITLE_HEIGHT,
            (pageWidth * COMPACT_TITLE_HEIGHT_RATIO).toInt(),
        )
        val prominentCompact = compactTitleType &&
            wordCount in 2..MAX_COMPACT_TITLE_WORDS &&
            boxWidth >= pageWidth * COMPACT_TITLE_WIDTH_RATIO
        if ((!largeType || !substantial) && !prominentCompact) return false

        return !looksLikeDialogue(text)
    }

    private fun looksLikeDialogue(raw: String): Boolean {
        // Scripts that do not put spaces between words cannot be judged by counting words, and the
        // Latin normalisation below deletes them outright: every Korean and Japanese balloon arrived
        // here as the empty string, counted zero words, and was discarded as decoration. That is one
        // guard silently refusing to translate two whole languages — a page of sharp, obvious Korean
        // dialogue came back untouched, and so did every Japanese manga.
        //
        // Length is the honest test for those scripts. A decorative title or a drawn sound effect is
        // two or three characters; a line of dialogue is longer. Erring towards translating is right
        // here anyway: lettering a title is a small blemish, and leaving dialogue in a language the
        // reader cannot read is the whole failure the feature exists to prevent.
        if (JapaneseSfxGuard.shouldDrop(raw)) return false
        val ideographs = raw.count(::isIdeographic)
        if (ideographs >= MIN_IDEOGRAPHIC_DIALOGUE) return true
        // Two Hangul syllables is already a spoken word. The ideograph floor of four is for
        // titles and drawn SFX; applying it to Korean dropped short, sharp dialogue.
        if (raw.count(::isHangul) >= MIN_HANGUL_DIALOGUE) return true

        val normalized = raw
            .uppercase()
            .replace('’', '\'')
            .replace(Regex("[^A-Z0-9']+"), " ")
            .trim()
        val words = WORD.findAll(normalized).map { it.value }.toList()
        if (words.size < 2) return false

        val title = words.joinToString(" ")
        if (TITLE_PREFIX.matches(title) || title == "THE END") return false

        val hasSentencePunctuation = raw.any { it == '.' || it == '?' || it == '!' || it == '…' || it == '¿' || it == '¡' }
        val hasConversationCue = words.any(CONVERSATION_CUES::contains)
        val hasSpanishCue = words.any(SPANISH_CUES::contains) || raw.any(::isSpanishLetter)
        return hasSentencePunctuation || hasConversationCue || hasSpanishCue
    }

    private fun isHangul(ch: Char): Boolean = ch.code.let { cp ->
        cp in 0x1100..0x11FF || cp in 0xAC00..0xD7AF
    }

    private fun isSpanishLetter(ch: Char): Boolean = ch in SPANISH_LETTERS

    /** Hangul, kana and Han — the scripts that carry a sentence without spaces. */
    private fun isIdeographic(ch: Char): Boolean = ch.code.let { cp ->
        cp in 0x1100..0x11FF || // Hangul jamo
            cp in 0x3040..0x30FF || // hiragana and katakana
            cp in 0x3400..0x9FFF || // Han
            cp in 0xAC00..0xD7AF || // Hangul syllables
            cp in 0xF900..0xFAFF // Han compatibility
    }

    private val WORD = Regex("[A-Z0-9]+(?:'[A-Z]+)?")
    private val TITLE_PREFIX = Regex("^(?:CHAPTER|EPISODE|VOLUME|SEASON|PROLOGUE|EPILOGUE)\\b.*")
    private val CONVERSATION_CUES = setOf(
        "I", "I'M", "I'VE", "I'LL", "I'D",
        "ME", "MY", "MINE",
        "YOU", "YOU'RE", "YOU'VE", "YOU'LL", "YOU'D", "YOUR", "YOURS",
        "WE", "WE'RE", "WE'VE", "WE'LL", "WE'D", "US", "OUR", "OURS",
        "WHAT", "WHY", "HOW", "WHO", "WHERE", "WHEN",
        "DO", "DON'T", "DID", "DIDN'T", "SHOULD", "SHOULDN'T",
        "ARE", "AREN'T", "IS", "ISN'T", "WAS", "WASN'T", "WERE", "WEREN'T",
        "CAN", "CAN'T", "COULD", "COULDN'T", "WILL", "WON'T", "WOULD", "WOULDN'T",
        "THEN", "PLEASE", "SORRY", "THANKS", "THANK",
    )

    /**
     * High-frequency Spanish function words. The English cue list above never matches a balloon
     * that says "No puedo creer esto", so those balloons were dropped as titles even when they
     * filled the panel and were perfectly sharp.
     */
    private val SPANISH_CUES = setOf(
        "QUE", "COMO", "POR", "PARA", "ESTO", "ESTA", "ESTE", "ESO", "ESA",
        "PERO", "PORQUE", "CUANDO", "DONDE", "QUIEN", "HOLA", "GRACIAS", "PERDON",
        "YO", "TU", "NOS", "MI", "SU", "CON", "SIN", "MAS", "YA", "AHORA", "AQUI",
        "MUY", "TAN", "BIEN", "MAL", "QUIERO", "QUIERES", "PUEDO", "PUEDES",
        "TIENE", "TIENES", "HACE", "VAS", "VOY", "SOY", "ERES", "ESTOY", "ESTAS",
        "NADA", "NUNCA", "SIEMPRE", "TAMBIEN", "DESPUES", "ANTES", "ENTONCES",
        "VAMOS", "VEN", "MIRA", "AMOR", "VIDA", "TIEMPO", "SOLO", "TODO", "TODOS",
        "HAY", "SER", "ESTAR", "NECESITO", "SIENTO", "PERDONA", "DIOS",
        "UNA", "UNO", "LOS", "LAS", "DEL", "AL",
    )
    private val SPANISH_LETTERS = setOf(
        'á', 'é', 'í', 'ó', 'ú', 'ü', 'ñ', 'Á', 'É', 'Í', 'Ó', 'Ú', 'Ü', 'Ñ',
    )

    /** Characters of a spaceless script above which the region is a sentence, not an ornament. */
    private const val MIN_IDEOGRAPHIC_DIALOGUE = 4
    private const val MIN_HANGUL_DIALOGUE = 2

    private const val MIN_DISPLAY_TEXT_HEIGHT = 42
    private const val DISPLAY_TEXT_HEIGHT_RATIO = 0.05f
    private const val DISPLAY_TEXT_AREA_RATIO = 0.08f
    private const val MAX_COMPACT_TITLE_WORDS = 4
    private const val MIN_COMPACT_TITLE_HEIGHT = 28
    private const val COMPACT_TITLE_HEIGHT_RATIO = 0.025f
    private const val COMPACT_TITLE_WIDTH_RATIO = 0.40f
}
