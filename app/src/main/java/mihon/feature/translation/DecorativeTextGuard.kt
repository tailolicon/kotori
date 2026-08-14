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
        val normalized = raw
            .uppercase()
            .replace('’', '\'')
            .replace(Regex("[^A-Z0-9']+"), " ")
            .trim()
        val words = WORD.findAll(normalized).map { it.value }.toList()
        if (words.size < 2) return false

        val title = words.joinToString(" ")
        if (TITLE_PREFIX.matches(title) || title == "THE END") return false

        val hasSentencePunctuation = raw.any { it == '.' || it == '?' || it == '!' || it == '…' }
        val hasConversationCue = words.any(CONVERSATION_CUES::contains)
        return hasSentencePunctuation || hasConversationCue
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

    private const val MIN_DISPLAY_TEXT_HEIGHT = 42
    private const val DISPLAY_TEXT_HEIGHT_RATIO = 0.05f
    private const val DISPLAY_TEXT_AREA_RATIO = 0.08f
    private const val MAX_COMPACT_TITLE_WORDS = 4
    private const val MIN_COMPACT_TITLE_HEIGHT = 28
    private const val COMPACT_TITLE_HEIGHT_RATIO = 0.025f
    private const val COMPACT_TITLE_WIDTH_RATIO = 0.40f
}
