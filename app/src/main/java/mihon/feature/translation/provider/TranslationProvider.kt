package mihon.feature.translation.provider

import android.graphics.Bitmap
import mihon.feature.translation.model.BubbleBox

/** Language pair plus tone instructions shared by every provider call. */
data class TranslationContext(
    val sourceLanguage: String,
    val targetLanguage: String,
    val styleHint: String = "",
) {
    val sourceName: String get() = LANGUAGE_NAMES[sourceLanguage] ?: sourceLanguage
    val targetName: String get() = LANGUAGE_NAMES[targetLanguage] ?: targetLanguage

    /**
     * No source language was declared — the page decides.
     *
     * This is the normal state. A reader moves between a Japanese series and a Korean one without
     * thinking about it, and a single app-wide "source language" setting cannot follow them: it
     * kept the last series' answer and read the new one through it. Vision providers detect the
     * script themselves, and the on-device path probes the page, so nothing needs to be told.
     */
    val isSourceAuto: Boolean get() = sourceLanguage.isBlank() || sourceLanguage == AUTO

    /**
     * "từ tiếng Nhật " / "" — the trailing space is part of it so the caller can drop the clause
     * entirely when there is nothing to declare, rather than telling the model it is translating
     * "from auto".
     */
    fun sourceClauseVi(): String = if (isSourceAuto) "" else "từ $sourceName "

    /** English twin of [sourceClauseVi]: `"from Japanese "` or `""`. */
    fun sourceClauseEn(): String = if (isSourceAuto) "" else "from $sourceName "

    companion object {
        const val AUTO = "auto"

        val LANGUAGE_NAMES = mapOf(
            "en" to "English",
            "ja" to "Japanese",
            "zh" to "Chinese",
            "ko" to "Korean",
            "vi" to "Vietnamese",
            "th" to "Thai",
            "id" to "Indonesian",
            "fr" to "French",
            "de" to "German",
            "es" to "Spanish",
            "ru" to "Russian",
        )
    }
}

/**
 * Continuity carried between light-novel chapters: without it a long series drifts, renaming
 * characters and switching pronoun register halfway through.
 */
data class ProseContext(
    val seriesTitle: String,
    /** Source term to agreed translation, accumulated as chapters are translated. */
    val glossary: Map<String, String> = emptyMap(),
    /** Tail of the previous chapter's translation, for tone and unresolved references. */
    val previousChapterTail: String = "",
)

/** A named entity the model chose a rendering for, so later chapters stay consistent. */
data class GlossaryEntry(val source: String, val target: String)

data class ProseTranslation(
    val text: String,
    val glossary: List<GlossaryEntry> = emptyList(),
)

/**
 * One bubble's result: what the model reports it read, and what it translated that into.
 *
 * [source] is carried back rather than discarded because it is the only independent evidence that the
 * model paired its answer with the right bubble. Nothing downstream displays it.
 */
data class BubbleTranslation(
    val source: String,
    val translation: String,
)

/**
 * The provider refused the call because a rate or usage quota is exhausted.
 *
 * Typed rather than a generic failure because the caller's response is different in kind: retrying a
 * quota error does not merely fail again, it *spends the next day's budget on failures* — every page
 * view and every prefetch pass costs one doomed request. The manager uses this to stop calling the
 * provider entirely until the quota window has passed.
 */
/**
 * The provider refused the credentials outright — revoked key, blocked project, wrong key.
 *
 * Distinct from [ProviderRateLimited] because waiting does not help: nothing changes until the user
 * fixes the key or switches provider. The caller stops calling until the settings change.
 */
class ProviderRejected(message: String) : Exception(message)

/**
 * The model is busy for everyone, not for this caller.
 *
 * Distinct from [ProviderRateLimited], which is about an allowance this key has spent: another key
 * meets the same queue, so spending the rest of them on an overloaded model achieves nothing. The
 * useful move is to step down to a different model, which is what the caller does.
 */
class ProviderUnavailable(message: String) : Exception(message)

class ProviderRateLimited(
    message: String,
    /** Server-suggested pause, when the response carried one. */
    val retryAfterSeconds: Long? = null,
    /** True when the quota resets daily; the pause is then hours, not seconds. */
    val dailyQuota: Boolean = false,
) : Exception(message)

interface TranslationProvider {

    /** Human-readable name for error messages. */
    val displayName: String

    /**
     * True when this provider can read the artwork itself. Vision providers are given the page and the
     * bubble geometry and return translations directly, skipping local OCR — noticeably more accurate
     * because the model sees who is speaking.
     */
    val supportsVisionOcr: Boolean get() = false

    /** Translates already-extracted bubble strings. Returns one entry per input, blanks allowed. */
    suspend fun translateLines(texts: List<String>, context: TranslationContext): List<BubbleTranslation>

    /** Vision path: read and translate in one call. Only called when [supportsVisionOcr]. */
    suspend fun ocrAndTranslate(
        bitmap: Bitmap,
        boxes: List<BubbleBox>,
        context: TranslationContext,
    ): List<BubbleTranslation> = throw UnsupportedOperationException("$displayName cannot read images")

    /**
     * Translates a light-novel chapter as continuous prose.
     *
     * Implementations must preserve paragraph breaks, because the reader's scroll position and
     * progress percentage are derived from paragraph count.
     */
    suspend fun translateProse(
        paragraphs: List<String>,
        context: TranslationContext,
        prose: ProseContext,
    ): ProseTranslation
}
