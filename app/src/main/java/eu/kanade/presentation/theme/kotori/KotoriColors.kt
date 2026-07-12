package eu.kanade.presentation.theme.kotori

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import eu.kanade.domain.ui.model.MediaType

/**
 * Kotori "Aurora Glass" raw design tokens.
 * Values mirror design_handoff_kotori_redesign/README.md — do not tweak casually.
 */
object KotoriColors {
    // Backgrounds
    val bgBase = Color(0xFF14101F)
    val bgPlayer = Color(0xFF100D18)
    val bgReaderManga = Color(0xFF0B0910)
    val bgSheet = Color(0xF7181326)
    val bgNavbar = Color(0xBF181326)

    // Text
    val textPrimary = Color(0xFFF3EFFB)
    val textSecondary = Color(0xFFA79FC0)
    val textMuted = Color(0xFF8D84AC)
    val textFaint = Color(0xFF6E6590)

    // Glass
    val glassBg = Color(0x0DFFFFFF) // 5%
    val glassBgElevated = Color(0x12FFFFFF) // 7%
    val glassBgPressed = Color(0x1AFFFFFF) // 10%
    val glassBorder = Color(0x17FFFFFF) // 9%
    val glassBorderElevated = Color(0x24FFFFFF) // 14%

    // Semantic
    val danger = Color(0xFFFB7185)
    val warning = Color(0xFFFCD34D)
    val warningSoft = Color(0xFFFDE68A)
    val success = Color(0xFF5EEAD4)
    val highlightPink = Color(0xFFF0ABFC)
    val star = Color(0xFFFDE68A)

    // Novel reader paper themes
    val paperSepia = Color(0xFFF3EAD8)
    val paperSepiaInk = Color(0xFF33302A)
    val paperSepiaAccent = Color(0xFF0D9488)
}

/**
 * Per-mode accent set. Switching content mode re-themes every accent in the app.
 */
data class KotoriAccent(
    val start: Color,
    val end: Color,
    val light: Color,
    val ctaStart: Color,
    val ctaEnd: Color,
    val onAccent: Color,
) {
    val gradient: Brush
        get() = Brush.linearGradient(listOf(start, end))
    val ctaGradient: Brush
        get() = Brush.linearGradient(listOf(ctaStart, ctaEnd))
}

val AnimeAccent = KotoriAccent(
    start = Color(0xFF8B5CF6),
    end = Color(0xFFC084FC),
    light = Color(0xFFC4B5FD),
    ctaStart = Color(0xFF8B5CF6),
    ctaEnd = Color(0xFFF472B6),
    onAccent = Color.White,
)

val MangaAccent = KotoriAccent(
    start = Color(0xFFEC4899),
    end = Color(0xFFF472B6),
    light = Color(0xFFF9A8D4),
    ctaStart = Color(0xFFEC4899),
    ctaEnd = Color(0xFFF472B6),
    onAccent = Color.White,
)

val NovelAccent = KotoriAccent(
    start = Color(0xFF14B8A6),
    end = Color(0xFF5EEAD4),
    light = Color(0xFF5EEAD4),
    ctaStart = Color(0xFF14B8A6),
    ctaEnd = Color(0xFF5EEAD4),
    onAccent = Color(0xFF0B1512),
)

val MediaType.accent: KotoriAccent
    get() = when (this) {
        MediaType.MANGA -> MangaAccent
        MediaType.ANIME -> AnimeAccent
        MediaType.NOVEL -> NovelAccent
    }

val LocalKotoriAccent = staticCompositionLocalOf { MangaAccent }

object KotoriTheme {
    val accent: KotoriAccent
        @Composable
        @ReadOnlyComposable
        get() = LocalKotoriAccent.current
}
