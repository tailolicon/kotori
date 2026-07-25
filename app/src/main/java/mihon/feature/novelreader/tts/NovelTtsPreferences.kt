package mihon.feature.novelreader.tts

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/**
 * Listening settings for the novel reader.
 *
 * Separate from `NovelReaderPreferences` because these outlive a reading session in a way typography
 * does not: the chosen voice decides whether a hundred-megabyte model download was worthwhile, so it
 * has to be remembered exactly, and the engine choice decides which of the two backends the reader
 * even initialises.
 */
class NovelTtsPreferences(
    preferenceStore: PreferenceStore,
) {
    val engine: Preference<NovelTtsEngineId> =
        preferenceStore.getEnum("novel_tts_engine", NovelTtsEngineId.SYSTEM)

    /** Engine-specific voice id; blank means "let the engine choose". */
    val voiceId: Preference<String> = preferenceStore.getString("novel_tts_voice", "")

    val rate: Preference<Float> = preferenceStore.getFloat("novel_tts_rate", 1f)

    /** Whether the reader scrolls itself to keep the sentence being spoken on screen. */
    val followPlayback: Preference<Boolean> =
        preferenceStore.getBoolean("novel_tts_follow_playback", true)

    /** Whether the word currently being spoken is emphasised inside the highlighted sentence. */
    val highlightWords: Preference<Boolean> =
        preferenceStore.getBoolean("novel_tts_highlight_words", true)
}
