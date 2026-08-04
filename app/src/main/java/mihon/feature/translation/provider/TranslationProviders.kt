package mihon.feature.translation.provider

import mihon.feature.translation.TranslationPreferences
import mihon.feature.translation.TranslationProviderType
import mihon.feature.translation.offline.OfflineModelStore
import mihon.feature.translation.offline.OfflineTranslationProvider
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Builds the provider the user currently has selected.
 *
 * Offline instances are cached: reloading a 1.1 GB GGUF on every page would dominate latency.
 * Call [releaseOffline] when deleting the model or leaving the offline provider permanently.
 */
object TranslationProviders {

    private val offlineLock = Any()

    @Volatile
    private var cachedOffline: OfflineTranslationProvider? = null

    fun current(preferences: TranslationPreferences): TranslationProvider =
        of(preferences.provider.get(), preferences)

    fun of(type: TranslationProviderType, preferences: TranslationPreferences): TranslationProvider =
        when (type) {
            TranslationProviderType.GEMINI -> GeminiTranslationProvider(
                apiKey = { preferences.geminiApiKey.get() },
                model = { preferences.geminiModel.get() },
            )
            TranslationProviderType.GROQ -> GroqTranslationProvider(
                apiKey = { preferences.groqApiKey.get() },
                model = { preferences.groqModel.get() },
            )
            TranslationProviderType.GOOGLE -> GoogleTranslationProvider()
            TranslationProviderType.OFFLINE -> offlineProvider(preferences)
        }

    /**
     * Shared offline provider. Path/thread lambdas always read current prefs/store so a reload
     * happens on next ensureLoaded when those change — without constructing a new engine each page.
     */
    fun offlineProvider(preferences: TranslationPreferences): OfflineTranslationProvider {
        synchronized(offlineLock) {
            cachedOffline?.let { return it }
            val store = runCatching { Injekt.get<OfflineModelStore>() }.getOrNull()
            val created = OfflineTranslationProvider(
                modelPath = { store?.modelPathOrNull() },
                threadCount = { preferences.offlineThreadCount.get() },
            )
            cachedOffline = created
            return created
        }
    }

    /** Unload native resources and drop the cached provider (delete model / leave OFFLINE). */
    fun releaseOffline() {
        synchronized(offlineLock) {
            cachedOffline?.release()
            cachedOffline = null
        }
    }
}
