package eu.kanade.presentation.more.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.theme.kotori.KotoriPanelLabel
import eu.kanade.presentation.theme.kotori.KotoriTabletShapes
import eu.kanade.presentation.theme.kotori.isKotoriTablet
import kotlinx.coroutines.delay
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.util.plus
import kotlin.time.Duration.Companion.seconds

/**
 * Preference Screen composable which contains a list of [Preference] items
 * @param items [Preference] items which should be displayed on the preference screen. An item can be a single [PreferenceItem] or a group ([Preference.PreferenceGroup])
 * @param modifier [Modifier] to be applied to the preferenceScreen layout
 */
@Composable
fun PreferenceScreen(
    items: List<Preference>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state = rememberLazyListState()
    val highlightKey = SearchableSettings.highlightKey
    if (highlightKey != null) {
        LaunchedEffect(Unit) {
            val i = items.findHighlightedIndex(highlightKey)
            if (i >= 0) {
                delay(0.5.seconds)
                state.animateScrollToItem(i)
            }
            SearchableSettings.highlightKey = null
        }
    }

    if (isKotoriTablet()) {
        KotoriTabletPreferenceGrid(
            items = items,
            highlightKey = highlightKey,
            modifier = modifier,
            contentPadding = contentPadding,
        )
        return
    }

    ScrollbarLazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
    ) {
        items.fastForEachIndexed { i, preference ->
            when (preference) {
                // Create Preference Group
                is Preference.PreferenceGroup -> {
                    if (!preference.enabled) return@fastForEachIndexed

                    item {
                        Column {
                            PreferenceGroupHeader(title = preference.title)
                        }
                    }
                    items(preference.preferenceItems) { item ->
                        PreferenceItem(
                            item = item,
                            highlightKey = highlightKey,
                        )
                    }
                    item {
                        if (i < items.lastIndex) {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                // Create Preference Item
                is Preference.PreferenceItem<*, *> -> item {
                    PreferenceItem(
                        item = preference,
                        highlightKey = highlightKey,
                    )
                }
            }
        }
    }
}

/**
 * T8 detail pane: every preference group becomes a glass card (radius 22 22 22 7)
 * laid out in a two-column grid, with its title as the tracked-out Unbounded label.
 */
@Composable
private fun KotoriTabletPreferenceGrid(
    items: List<Preference>,
    highlightKey: String?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val gridState = rememberLazyStaggeredGridState()
    if (highlightKey != null) {
        LaunchedEffect(highlightKey) {
            val i = items.indexOfFirst { preference ->
                when (preference) {
                    is Preference.PreferenceGroup ->
                        preference.title == highlightKey ||
                            preference.preferenceItems.any { it.title == highlightKey }
                    is Preference.PreferenceItem<*, *> -> preference.title == highlightKey
                }
            }
            if (i >= 0) {
                delay(0.5.seconds)
                gridState.animateScrollToItem(i)
            }
            SearchableSettings.highlightKey = null
        }
    }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        state = gridState,
        contentPadding = contentPadding + PaddingValues(horizontal = 30.dp, vertical = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalItemSpacing = 14.dp,
    ) {
        items.fastForEachIndexed { index, preference ->
            when (preference) {
                is Preference.PreferenceGroup -> {
                    if (!preference.enabled) return@fastForEachIndexed
                    item(key = "group-$index-${preference.title}") {
                        KotoriPreferenceCard(label = preference.title) {
                            preference.preferenceItems.forEach { item ->
                                PreferenceItem(item = item, highlightKey = highlightKey)
                            }
                        }
                    }
                }
                is Preference.PreferenceItem<*, *> -> item(key = "item-$index-${preference.title}") {
                    KotoriPreferenceCard(label = null) {
                        PreferenceItem(item = preference, highlightKey = highlightKey)
                    }
                }
            }
        }
    }
}

@Composable
private fun KotoriPreferenceCard(
    label: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KotoriTabletShapes.settingsCard)
            .background(Color(0x0DFFFFFF))
            .border(1.dp, Color(0x1AFFFFFF), KotoriTabletShapes.settingsCard)
            .padding(vertical = 16.dp),
    ) {
        if (!label.isNullOrBlank()) {
            KotoriPanelLabel(
                text = label.uppercase(),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
            )
        }
        content()
    }
}

private fun List<Preference>.findHighlightedIndex(highlightKey: String): Int {
    return flatMap {
        if (it is Preference.PreferenceGroup) {
            buildList<String?> {
                add(null) // Header
                addAll(it.preferenceItems.map { groupItem -> groupItem.title })
                add(null) // Spacer
            }
        } else {
            listOf(it.title)
        }
    }.indexOfFirst { it == highlightKey }
}
