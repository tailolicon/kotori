package eu.kanade.presentation.browse

import androidx.compose.runtime.Composable
import eu.kanade.presentation.browse.components.InLibraryBadge as KotoriInLibraryBadge

/**
 * `✓ THƯ VIỆN` on a browse cover that is already in the library.
 *
 * Delegates to the Kotori badge the manga grids already use — the anime grids were still drawing
 * the stock icon badge here, so the same screen looked different depending on the content type.
 */
@Composable
fun InLibraryBadge(enabled: Boolean) {
    KotoriInLibraryBadge(enabled = enabled)
}
