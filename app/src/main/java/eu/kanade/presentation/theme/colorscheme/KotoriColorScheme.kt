package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Kotori "Aurora Glass" M3 color scheme.
 *
 * The design is a single dark night-violet look (design_handoff_kotori_redesign):
 * bg #14101F, text #F3EFFB, glass surfaces, mode-colored accents. The light
 * scheme intentionally mirrors the dark one — Aurora Glass is always dark;
 * novel reading gets its own paper themes inside the reader.
 *
 * Mode accents (manga pink / anime violet / novel teal) are applied on top of
 * this scheme in TachiyomiTheme based on the active media mode.
 */
internal object KotoriColorScheme : BaseColorScheme() {

    private val scheme = darkColorScheme(
        primary = Color(0xFFEC4899),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF4A1B34),
        onPrimaryContainer = Color(0xFFF9A8D4),
        inversePrimary = Color(0xFFF472B6),
        secondary = Color(0xFFEC4899), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFF3A2547), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFFF9A8D4), // Navigation bar selector icon
        tertiary = Color(0xFF5EEAD4), // Downloaded badge
        onTertiary = Color(0xFF0B1512), // Downloaded badge text
        tertiaryContainer = Color(0xFF14B8A6),
        onTertiaryContainer = Color(0xFF0B1512),
        background = Color(0xFF14101F),
        onBackground = Color(0xFFF3EFFB),
        surface = Color(0xFF14101F),
        onSurface = Color(0xFFF3EFFB),
        surfaceVariant = Color(0xFF201C2E), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFFA79FC0),
        surfaceTint = Color(0xFFEC4899),
        inverseSurface = Color(0xFF2E2843),
        inverseOnSurface = Color(0xFFF3EFFB),
        outline = Color(0xFF6E6590),
        outlineVariant = Color(0xFF35304A),
        error = Color(0xFFFB7185),
        onError = Color(0xFF1B0A10),
        errorContainer = Color(0xFF551D2A),
        onErrorContainer = Color(0xFFFECDD3),
        surfaceContainerLowest = Color(0xFF161222),
        surfaceContainerLow = Color(0xFF181326),
        surfaceContainer = Color(0xFF1B1629), // Navigation bar background
        surfaceContainerHigh = Color(0xFF221D36),
        surfaceContainerHighest = Color(0xFF27213C),
    )

    override val darkScheme = scheme
    override val lightScheme = scheme
}
