package mihon.feature.translation.provider

import mihon.feature.translation.TranslationPreferences
import mihon.feature.translation.TranslationProviderType

/** Builds the provider the user currently has selected. */
object TranslationProviders {

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
        }
}
