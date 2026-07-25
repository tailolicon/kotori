package mihon.feature.novelreader.tts

/**
 * One spoken unit of a chapter.
 *
 * A sentence — not a paragraph and not a fixed-length chunk — is the unit the whole listening
 * feature is built on, because it is the smallest span a listener can meaningfully point at. It is
 * what gets highlighted, what a tap seeks to, and what is handed to the engine as a single
 * utterance, so the highlight and the audio can never drift apart: whatever is playing *is* the
 * sentence that is lit up.
 *
 * @param index position in the chapter's flat sentence list; the seek/progress coordinate.
 * @param blockIndex the prose block this came from, so the reader knows which paragraph to light up.
 * @param range where the sentence sits inside that block's text, for highlighting a span of it.
 */
data class SpeechSentence(
    val index: Int,
    val blockIndex: Int,
    val text: String,
    val range: IntRange,
) {
    /**
     * Word spans within [text], used to bold the word currently being spoken.
     *
     * Computed once per sentence rather than per frame: the highlight redraws on every progress
     * tick, and re-tokenising Vietnamese prose at that rate would show up as jank.
     */
    val words: List<IntRange> by lazy(LazyThreadSafetyMode.NONE) { text.wordRanges() }

    /**
     * Relative weight of each word in speaking time, normalised to sum to 1.
     *
     * Engines that synthesize a whole sentence at once report progress in samples, not words, so
     * word timing has to be inferred. Vietnamese is syllable-timed and its words are short and
     * fairly even, which makes character count a decent proxy; a small constant is added per word so
     * that one-letter words still get a visible beat rather than flashing past.
     */
    val wordWeights: FloatArray by lazy(LazyThreadSafetyMode.NONE) {
        val raw = FloatArray(words.size) { words[it].count() + WORD_WEIGHT_FLOOR }
        val total = raw.sum().takeIf { it > 0f } ?: return@lazy FloatArray(words.size) { 0f }
        FloatArray(raw.size) { raw[it] / total }
    }

    /** Index of the word being spoken at [fraction] (0..1) of the way through the sentence. */
    fun wordAt(fraction: Float): Int {
        if (words.isEmpty()) return -1
        var accumulated = 0f
        wordWeights.forEachIndexed { index, weight ->
            accumulated += weight
            if (fraction <= accumulated) return index
        }
        return words.lastIndex
    }

    private companion object {
        const val WORD_WEIGHT_FLOOR = 1.6f
    }
}

/** Every sentence of a chapter, in reading order, with lookups the reader and player both need. */
class SpeechScript(val sentences: List<SpeechSentence>) {

    val isEmpty: Boolean get() = sentences.isEmpty()
    val size: Int get() = sentences.size

    private val byBlock: Map<Int, List<SpeechSentence>> = sentences.groupBy { it.blockIndex }

    fun sentencesIn(blockIndex: Int): List<SpeechSentence> = byBlock[blockIndex].orEmpty()

    operator fun get(index: Int): SpeechSentence? = sentences.getOrNull(index)

    /** The sentence a tap at [offset] inside [blockIndex] means, for seek-by-tap. */
    fun sentenceAt(blockIndex: Int, offset: Int): SpeechSentence? {
        val candidates = sentencesIn(blockIndex).ifEmpty { return null }
        return candidates.firstOrNull { offset in it.range } ?: candidates.last()
    }

    companion object {
        val Empty = SpeechScript(emptyList())
    }
}

/**
 * Builds the chapter's speech timeline from its prose blocks.
 *
 * [blockTexts] must be indexed exactly as the reader's own block list is — including the entries for
 * illustrations, which contribute no sentences but must not shift the indices of the prose around
 * them, or the highlight would land on the wrong paragraph.
 */
fun buildSpeechScript(blockTexts: List<String?>): SpeechScript {
    var index = 0
    val sentences = buildList {
        blockTexts.forEachIndexed { blockIndex, text ->
            if (text.isNullOrBlank()) return@forEachIndexed
            text.sentenceRanges().forEach { range ->
                val body = text.substring(range.first, range.last + 1).trim()
                if (body.isEmpty()) return@forEach
                add(SpeechSentence(index++, blockIndex, body, range))
            }
        }
    }
    return SpeechScript(sentences)
}

/**
 * Splits a paragraph into sentence ranges.
 *
 * Sentence-final punctuation alone is not enough: Vietnamese prose is full of decimals ("3.5"),
 * ellipses and abbreviations, and cutting inside one of those would both mangle the highlight and
 * make the engine read a fragment with the wrong intonation. A terminator therefore only ends a
 * sentence when what follows looks like a fresh start — whitespace then an opening quote, a dialogue
 * dash, a digit or an upper-case letter — or the paragraph itself ends.
 *
 * Two length guards keep the result speakable. Runs shorter than [MIN_SENTENCE] are folded into the
 * next sentence, so a lone "Ừ." is not its own utterance with its own pause. Runs longer than
 * [MAX_SENTENCE] are broken at the last comma or clause break before the limit, so one unpunctuated
 * paragraph cannot become a single minutes-long utterance that no tap can seek inside.
 */
private fun String.sentenceRanges(): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var start = 0
    var cursor = 0
    while (cursor < length) {
        val char = this[cursor]
        val isTerminator = char in SENTENCE_TERMINATORS
        val atEnd = cursor == lastIndex
        if (isTerminator) {
            // Consume a run of terminators and any closing quote or bracket that belongs with them,
            // so "!?" and `nói."` stay whole instead of splitting between the marks.
            var end = cursor
            while (end + 1 < length && this[end + 1] in SENTENCE_TERMINATORS) end++
            while (end + 1 < length && this[end + 1] in CLOSING_MARKS) end++
            if (end == lastIndex || startsSentence(end + 1)) {
                ranges.addSentence(start, end, this)
                cursor = end + 1
                while (cursor < length && this[cursor].isWhitespace()) cursor++
                start = cursor
                continue
            }
            cursor = end + 1
            continue
        }
        if (atEnd) {
            ranges.addSentence(start, cursor, this)
            start = cursor + 1
        }
        cursor++
    }
    if (start < length) ranges.addSentence(start, lastIndex, this)
    return ranges.mergeShortRuns(this)
}

/** True when the text from [at] onward reads as the beginning of a new sentence. */
private fun String.startsSentence(at: Int): Boolean {
    var index = at
    var sawSpace = false
    while (index < length && this[index].isWhitespace()) {
        sawSpace = true
        index++
    }
    if (index >= length) return true
    if (!sawSpace) return false
    val next = this[index]
    return next.isUpperCase() || next.isDigit() || next in OPENING_MARKS
}

/** Appends [start]..[end], splitting it first if it is too long to be one utterance. */
private fun MutableList<IntRange>.addSentence(start: Int, end: Int, source: String) {
    if (start > end) return
    var from = start
    while (end - from + 1 > MAX_SENTENCE) {
        val limit = from + MAX_SENTENCE
        val breakAt = (limit downTo from + MIN_SENTENCE)
            .firstOrNull { source[it] in CLAUSE_BREAKS }
            ?: (limit downTo from + MIN_SENTENCE).firstOrNull { source[it].isWhitespace() }
            ?: limit
        add(from..breakAt)
        from = breakAt + 1
        while (from <= end && source[from].isWhitespace()) from++
    }
    if (from <= end) add(from..end)
}

/** Folds runs too short to be worth their own utterance into the one that follows. */
private fun List<IntRange>.mergeShortRuns(source: String): List<IntRange> {
    if (size < 2) return this
    val merged = mutableListOf<IntRange>()
    var pending: IntRange? = null
    forEach { range ->
        val combined = pending?.let { it.first..range.last } ?: range
        val length = source.substring(combined.first, combined.last + 1).trim().length
        if (length < MIN_SENTENCE) {
            pending = combined
        } else {
            merged += combined
            pending = null
        }
    }
    pending?.let { leftover ->
        // A trailing fragment has nothing to merge forward into, so it joins the previous sentence
        // rather than being spoken alone or, worse, dropped.
        if (merged.isEmpty()) merged += leftover else merged[merged.lastIndex] =
            merged.last().first..leftover.last
    }
    return merged
}

/** Word spans, used for the per-word highlight. Punctuation is not a word and never lights up. */
private fun String.wordRanges(): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var start = -1
    forEachIndexed { index, char ->
        val isWord = char.isLetterOrDigit() || char == '_' || char == '\'' || char == '-'
        when {
            isWord && start < 0 -> start = index
            !isWord && start >= 0 -> {
                ranges += start until index
                start = -1
            }
        }
    }
    if (start >= 0) ranges += start..lastIndex
    return ranges
}

private const val MIN_SENTENCE = 12
private const val MAX_SENTENCE = 320
// These arrays are mostly non-ASCII, which makes them the first thing lost if anything mangles this
// file's encoding — and losing them is not loud: an emptied entry is a compile error here, but a
// half-emptied set would still compile and quietly read a chapter that only ever ends its sentences
// in a full-width period as one enormous utterance. Keep the file UTF-8, and keep the comments that
// name each mark so a mangled one can be identified rather than guessed at.
private val SENTENCE_TERMINATORS = charArrayOf(
    '.', '!', '?',
    '…', // ellipsis
    '。', '！', '？', // full-width . ! ? — common in translated light novels
)
private val CLOSING_MARKS = charArrayOf(
    '"', '\'', ')', ']',
    '”', '’', // curly double / single close
    '»', '」', // guillemet, CJK corner bracket
    '）', '】', // full-width ), black lenticular close
)
private val OPENING_MARKS = charArrayOf(
    '"', '-', '(', '[',
    '“', '‘', // curly double / single open
    '«', '「', '【', // guillemet, CJK corner, black lenticular open
    '—', '–', // em / en dash — these open a line of dialogue in Vietnamese prose
    '（', // full-width (
)
private val CLAUSE_BREAKS = charArrayOf(
    ',', ';', ':', '-',
    '，', '；', // full-width , ;
    '—', // em dash
)
