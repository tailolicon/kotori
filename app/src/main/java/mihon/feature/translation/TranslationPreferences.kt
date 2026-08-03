package mihon.feature.translation

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/**
 * Which backend produces the translated strings.
 *
 * [GEMINI] additionally does the OCR itself (single vision call per page), which is both faster and
 * markedly more accurate than OCR-then-translate because the model sees the artwork. The other two
 * providers translate text that on-device ML Kit OCR extracted first.
 */
enum class TranslationProviderType {
    GEMINI,
    GROQ,
    GOOGLE,
}

/**
 * How many chapters ahead of the one being read are translated in the background so that the reader
 * never has to wait at a chapter boundary.
 */
const val TRANSLATION_PREFETCH_CHAPTERS = 2

class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {

    val provider: Preference<TranslationProviderType> =
        preferenceStore.getEnum("pref_translation_provider", TranslationProviderType.GEMINI)

    val geminiApiKey: Preference<String> = preferenceStore.getString("pref_translation_gemini_key", "")

    val groqApiKey: Preference<String> = preferenceStore.getString("pref_translation_groq_key", "")

    /**
     * Flash-Lite is the default because a manga chapter is a high-volume, low-complexity workload:
     * twenty or so pages, each a single vision call. Latency and cost per page matter far more here
     * than headroom on hard reasoning. Swap to a full Flash model if dense or stylised lettering
     * starts coming back misread.
     */
    val geminiModel: Preference<String> = preferenceStore.getString(
        "pref_translation_gemini_model",
        "gemini-3.5-flash-lite",
    )

    val groqModel: Preference<String> = preferenceStore.getString(
        "pref_translation_groq_model",
        "llama-3.3-70b-versatile",
    )

    val sourceLanguage: Preference<String> = preferenceStore.getString("pref_translation_source_lang", "ja")

    val targetLanguage: Preference<String> = preferenceStore.getString("pref_translation_target_lang", "vi")

    /** Free-form extra instruction appended to the translation prompt. */
    val styleHint: Preference<String> = preferenceStore.getString("pref_translation_style", "")

    /** Typeface used for rendered bubble text; resolved against assets/translation/. */
    val font: Preference<String> = preferenceStore.getString("pref_translation_font", "HL-Comic")

    /** Upper bound for the on-disk translated-page cache, in mebibytes. */
    val cacheSizeMb: Preference<Int> = preferenceStore.getInt("pref_translation_cache_mb", 1024)

    /**
     * How long a series' translated pages survive after the reader was last closed on it, in hours.
     *
     * Translated pages are derived data that can always be rebuilt, and a reader who moves on to a
     * different series has no use for them. Expiring by time rather than only by total size means the
     * cache empties itself after a binge instead of sitting at its ceiling until something evicts it.
     * Zero disables time-based expiry and leaves only the size cap.
     */
    val cacheRetentionHours: Preference<Int> = preferenceStore.getInt("pref_translation_cache_hours", 24)

    /**
     * Whether translation stays on for a manga across reader sessions. Keyed per manga so turning it
     * on for one series does not silently spend API quota on every other series.
     */
    fun enabledForManga(mangaId: Long): Preference<Boolean> =
        preferenceStore.getBoolean("pref_translation_enabled_$mangaId", false)

    /** Same as [enabledForManga] but for light novels, which use a text-only pipeline. */
    fun novelEnabledForManga(mangaId: Long): Preference<Boolean> =
        preferenceStore.getBoolean("pref_translation_novel_enabled_$mangaId", false)

    /**
     * Identifies the settings that affect rendered output. Cached pages whose stamp no longer matches
     * are treated as misses, so changing provider or language re-translates instead of showing stale
     * artwork.
     */
    fun outputStamp(): String = listOf(
        RENDERER_VERSION,
        provider.get().name,
        sourceLanguage.get(),
        targetLanguage.get(),
        when (provider.get()) {
            TranslationProviderType.GEMINI -> geminiModel.get()
            TranslationProviderType.GROQ -> groqModel.get()
            TranslationProviderType.GOOGLE -> "gt"
        },
        font.get(),
        styleHint.get(),
    ).joinToString("|")

    companion object {
        /**
         * Bumped whenever the masking or rendering changes shape.
         *
         * Cached pages are keyed on this. Without it a rendering fix has no visible effect on any
         * page already translated — the cache keeps serving the old artwork, which makes the fix look
         * like it did nothing and is exactly how a masking bug can survive several rounds of testing.
         */
        const val RENDERER_VERSION = "r19"
    }

    fun hasCredentialsFor(type: TranslationProviderType): Boolean = when (type) {
        TranslationProviderType.GEMINI -> geminiApiKey.get().isNotBlank()
        TranslationProviderType.GROQ -> groqApiKey.get().isNotBlank()
        TranslationProviderType.GOOGLE -> true
    }
}
