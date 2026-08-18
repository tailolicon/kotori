package mihon.feature.translation

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/**
 * Which backend produces the translated strings.
 *
 * [GEMINI] additionally does the OCR itself (single vision call per page), which is both faster and
 * markedly more accurate than OCR-then-translate because the model sees the artwork. [GROQ] and
 * [GOOGLE] translate text that on-device ML Kit OCR extracted first. [OFFLINE] runs HY-MT via
 * llama.cpp on the device — no API key, user-downloaded GGUF required.
 */
enum class TranslationProviderType {
    GEMINI,
    GROQ,
    GOOGLE,
    OFFLINE,
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

    /**
     * Identity string of the on-device GGUF (feeds [outputStamp]). Defaults to the only model we
     * currently ship a download for; kept as a preference so a future second quant does not reuse
     * rendered pages from the previous one.
     */
    val offlineModelId: Preference<String> = preferenceStore.getString(
        "pref_translation_offline_model_id",
        mihon.feature.translation.offline.OfflineModelSpec.IDENTITY,
    )

    /** llama.cpp thread count for offline inference. */
    val offlineThreadCount: Preference<Int> = preferenceStore.getInt(
        "pref_translation_offline_threads",
        mihon.feature.translation.offline.OfflineModelSpec.DEFAULT_THREADS,
    )

    /**
     * Authoritative "GGUF verified and installed" flag, maintained by OfflineModelStore.
     * Prefer this over a bare `false` so every [hasCredentialsFor] caller agrees.
     */
    val offlineModelReady: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_translation_offline_model_ready",
        false,
    )

    /**
     * User explicitly confirmed: not in EU/UK/South Korea, read the Tencent HY Community License,
     * and accepts that Tencent is not affiliated with Kotori. Value is the acceptance version.
     */
    val offlineLicenseAcceptedVersion: Preference<Int> = preferenceStore.getInt(
        "pref_translation_offline_license_accepted_v",
        0,
    )

    fun offlineLicenseAccepted(): Boolean =
        offlineLicenseAcceptedVersion.get() >=
            mihon.feature.translation.offline.OfflineModelSpec.LICENSE_ACCEPTANCE_VERSION

    fun acceptOfflineLicense() {
        offlineLicenseAcceptedVersion.set(
            mihon.feature.translation.offline.OfflineModelSpec.LICENSE_ACCEPTANCE_VERSION,
        )
    }

    val sourceLanguage: Preference<String> = preferenceStore.getString("pref_translation_source_lang", "ja")

    val targetLanguage: Preference<String> = preferenceStore.getString("pref_translation_target_lang", "vi")

    /** Free-form extra instruction appended to the translation prompt. */
    val styleHint: Preference<String> = preferenceStore.getString("pref_translation_style", "")

    /** Typeface used for rendered bubble text; resolved against assets/translation/. */
    val font: Preference<String> = preferenceStore.getString("pref_translation_font", "HL-Comic")

    /**
     * Simple lettering: erase the recognised text, write the translation in its footprint, and do
     * nothing else. Defaults on — it is what a human letterer does, and every extra ambition of the
     * bubble-aware mode (fills matched to the balloon, text recentred, larger type) is also a way
     * for a page to come out redecorated. The bubble-aware renderer remains available behind this
     * switch for readers who prefer its look.
     */
    val simpleRender: Preference<Boolean> = preferenceStore.getBoolean("pref_translation_simple_render", true)

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
            TranslationProviderType.OFFLINE -> offlineModelId.get()
        },
        font.get(),
        styleHint.get(),
        if (simpleRender.get()) "simple" else "bubble",
    ).joinToString("|")

    companion object {
        /**
         * Bumped whenever anything that shapes the output changes — masking, drawing, *and how the
         * page is read*, since grouping the recognised lines is what decides where a translation goes.
         *
         * Cached pages are keyed on this. Without it a fix has no visible effect on any page already
         * translated: the cache keeps serving the old artwork, which makes the fix look like it did
         * nothing and is exactly how a masking bug survives several rounds of testing. Shipped that
         * way once — 1.0.14 fixed a balloon defect and left the constant alone, so every page the
         * reader had already seen came back with the defect intact.
         */
        const val RENDERER_VERSION = "r99"
    }

    /**
     * Whether the selected backend can run right now.
     *
     * Offline needs no API key: readiness is the verified GGUF flag kept in sync by
     * [mihon.feature.translation.offline.OfflineModelStore] (and re-checked against the file when
     * the store is available). License acceptance is required before download, not as a credential
     * for translation once the model is already on disk.
     */
    fun hasCredentialsFor(type: TranslationProviderType): Boolean = when (type) {
        TranslationProviderType.GEMINI -> geminiApiKey.get().isNotBlank()
        TranslationProviderType.GROQ -> groqApiKey.get().isNotBlank()
        TranslationProviderType.GOOGLE -> true
        TranslationProviderType.OFFLINE -> offlineModelReady.get()
    }
}
