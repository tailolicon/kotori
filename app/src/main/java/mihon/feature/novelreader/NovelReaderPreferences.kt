package mihon.feature.novelreader

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/**
 * Novel reader preferences (design screen 10): font size 12–28sp, font family,
 * paper theme, line spacing.
 */
class NovelReaderPreferences(
    preferenceStore: PreferenceStore,
) {
    val fontSize: Preference<Int> = preferenceStore.getInt("novel_font_size", 18)

    val fontFamily: Preference<NovelFont> = preferenceStore.getEnum("novel_font_family", NovelFont.LITERATA)

    val theme: Preference<NovelTheme> = preferenceStore.getEnum("novel_theme", NovelTheme.SEPIA)

    val lineSpacing: Preference<NovelLineSpacing> =
        preferenceStore.getEnum("novel_line_spacing", NovelLineSpacing.NORMAL)

    enum class NovelFont(val label: String) {
        LITERATA("Literata"),
        NOTO_SERIF("Noto Serif"),
        BE_VIETNAM("Be Vietnam"),
    }

    enum class NovelTheme(val label: String) {
        WHITE("Trắng"),
        SEPIA("Giấy cũ"),
        DARK("Tối"),
        BLACK("Đen"),
    }

    enum class NovelLineSpacing(val multiplier: Float, val label: String) {
        COMPACT(1.55f, "Gọn"),
        NORMAL(1.85f, "Vừa"),
        RELAXED(2.15f, "Thoáng"),
    }
}
