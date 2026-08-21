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

    /**
     * One or more Gemini API keys, newline-separated.
     *
     * Several because the free tier's daily allowance is what ends a reading session, and a reader
     * with more than one Google account has more than one allowance. The provider works through
     * them and parks whichever is spent — see [mihon.feature.translation.provider.GeminiKeyRing].
     * A single key entered before this existed is still a valid value of this preference.
     */
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

    /**
     * Legacy setting, now always [SOURCE_AUTO] for new installs and never shown in the reader.
     *
     * It is kept because it still keys [outputStamp] — an install that had it pinned to a language
     * must not go on serving pages translated under that pin — and because the light-novel path can
     * still be handed an explicit language. Nothing in the manga path reads it as an instruction any
     * more: the page is what decides, which is what stops a Korean series being read as Japanese
     * because the previous series was.
     */
    val sourceLanguage: Preference<String> = preferenceStore.getString("pref_translation_source_lang", SOURCE_AUTO)

    val targetLanguage: Preference<String> = preferenceStore.getString("pref_translation_target_lang", "vi")

    /** Free-form extra instruction appended to the translation prompt. */
    val styleHint: Preference<String> = preferenceStore.getString("pref_translation_style", "")

    /** Typeface used for rendered bubble text; resolved against assets/translation/. */
    val font: Preference<String> = preferenceStore.getString("pref_translation_font", "HL-Comic")

    /**
     * Simple lettering: erase the recognised text, write the translation in its footprint, and do
     * nothing else. Kept so installs that never opened the new style picker keep their previous
     * choice; [renderStyle] is what the pipeline and the cache stamp read.
     */
    val simpleRender: Preference<Boolean> = preferenceStore.getBoolean("pref_translation_simple_render", true)

    /**
     * How dialogue is painted back. Defaults to [TranslationRenderStyle.SIMPLE] so the regression
     * corpus and existing installs stay on the footprint path until the reader picks another.
     *
     * [TranslationRenderStyle.TYPESET] is the letterer's path: recover the balloon, fill it, set
     * the translation in the whole interior. That is what vertical Japanese (and any page whose
     * detector misses the balloon) needs.
     */
    val renderStylePref: Preference<TranslationRenderStyle> =
        preferenceStore.getEnum("pref_translation_render_style", TranslationRenderStyle.SIMPLE)

    fun renderStyle(): TranslationRenderStyle {
        if (renderStylePref.isSet()) return renderStylePref.get()
        return if (simpleRender.get()) TranslationRenderStyle.SIMPLE else TranslationRenderStyle.AUTO
    }

    fun setRenderStyle(style: TranslationRenderStyle) {
        renderStylePref.set(style)
        simpleRender.set(style == TranslationRenderStyle.SIMPLE)
    }

    /**
     * One-shot move of existing installs onto [TranslationRenderStyle.AUTO].
     *
     * A style pinned before AUTO existed cannot be told apart from one the reader chose, and the
     * pinned value was doing real damage: an install left on TYPESET after manga work rendered every
     * webtoon as flooded dark slabs. Everyone starts on the automatic choice once; the picker is
     * still there, under "Nâng cao", for anyone who wants to force a mode back.
     */
    private val renderStyleMigrated: Preference<Boolean> =
        preferenceStore.getBoolean("pref_translation_render_style_auto_v1", false)

    init {
        if (!renderStyleMigrated.get()) {
            setRenderStyle(TranslationRenderStyle.AUTO)
            renderStyleMigrated.set(true)
        }
    }

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

    /**
     * Bumped when the user discards cached pages for [mangaId]. Included in the on-disk key so a
     * file that failed to delete (still open in the viewer) or was rewritten by in-flight work
     * cannot be served again.
     */
    /**
     * Script this series turned out to be written in, learned from its first translated page.
     *
     * Per series, which is the whole point — the app-wide setting it replaces kept the *previous*
     * series' answer and read the new one through it. Remembering it also means the recogniser is
     * chosen once rather than probed on every page: probing per page cost three extra ML Kit model
     * loads a page, and on a long strip it could sample nothing but artwork and commit the whole
     * chapter to the wrong script.
     */
    fun detectedLanguage(mangaId: Long): Preference<String> =
        preferenceStore.getString("pref_translation_detected_lang_$mangaId", "")

    fun cacheGeneration(mangaId: Long): Preference<Int> =
        preferenceStore.getInt("pref_translation_cache_gen_$mangaId", 0)

    /**
     * Bumped by "clear everything". Same job as [cacheGeneration] but across all series, since a
     * global clear cannot enumerate the per-series counters it would otherwise have to bump.
     */
    val globalCacheGeneration: Preference<Int> =
        preferenceStore.getInt("pref_translation_cache_gen_all", 0)

    fun bumpGlobalCacheGeneration(): Int {
        val next = globalCacheGeneration.get() + 1
        globalCacheGeneration.set(next)
        return next
    }

    fun bumpCacheGeneration(mangaId: Long): Int {
        val pref = cacheGeneration(mangaId)
        val next = pref.get() + 1
        pref.set(next)
        return next
    }

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
        renderStyle().name.lowercase(),
    ).joinToString("|")

    companion object {
        /** Source language is decided by the page, not by a setting. */
        const val SOURCE_AUTO = "auto"

        /**
         * Bumped whenever anything that shapes the output changes — masking, drawing, *and how the
         * page is read*, since grouping the recognised lines is what decides where a translation goes.
         *
         * Cached pages are keyed on this. Without it a fix has no visible effect on any page already
         * translated: the cache keeps serving the old artwork, which makes the fix look like it did
         * nothing and is exactly how a masking bug survives several rounds of testing. Shipped that
         * way once — 1.0.14 fixed a balloon defect and left the constant alone, so every page the
         * reader had already seen came back with the defect intact.
         *
         * And again in 1.0.21: the script probe, the companion re-read and the balloon detector all
         * changed which text a page yields and where it goes, and this stayed at r107 from 1.0.20.
         * A reader who updated kept being served 1.0.20's pages, so the same chapter looked different
         * on two devices purely by which one happened to hold a cache. If a change alters a single
         * pixel of output, it belongs in the same commit as a bump here.
         */
        const val RENDERER_VERSION = "r111"
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
