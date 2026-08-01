package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.theme.kotori.KotoriCircleAction
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriTabletRail
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.theme.kotori.isKotoriTablet
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Which of the five rail destinations a pushed screen belongs under, so screens that
 * live outside the tab navigator (the season calendar, for instance) can still show the
 * rail the mock draws for them with the right destination lit.
 */
enum class KotoriRailDestination {
    Library,
    Updates,
    History,
    Browse,
    More,
}

/**
 * The rail's own labels and icons.
 *
 * A pushed screen sits outside the tab navigator, so it cannot read `Tab.options` — those
 * getters resolve `LocalTabNavigator` and throw here. The icons are the ones the mock names
 * anyway (`collections_bookmark`, `new_releases`, `history`, `explore`, `more_horiz`).
 */
private val RAIL_ITEMS = listOf(
    Triple(KotoriRailDestination.Library, MR.strings.label_library, Icons.Outlined.CollectionsBookmark),
    Triple(KotoriRailDestination.Updates, MR.strings.label_recent_updates, Icons.Outlined.NewReleases),
    Triple(KotoriRailDestination.History, MR.strings.label_recent_manga, Icons.Outlined.History),
    Triple(KotoriRailDestination.Browse, MR.strings.browse, Icons.Outlined.Explore),
    Triple(KotoriRailDestination.More, MR.strings.label_more, Icons.Outlined.MoreHoriz),
)

/**
 * Wraps a screen that was pushed over [HomeScreen] so it keeps the navigation rail on
 * tablet. Tapping a destination unwinds back to the home tabs and opens it; on phone the
 * screen renders untouched.
 */
@Composable
fun KotoriRailScaffold(
    destination: KotoriRailDestination,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!isKotoriTablet()) {
        content()
        return
    }
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val basePreferences = remember { Injekt.get<BasePreferences>() }
    val downloadedOnly by basePreferences.downloadedOnly.collectAsState()

    val open: (HomeScreen.Tab) -> Unit = { tab ->
        scope.launch {
            navigator.popUntilRoot()
            HomeScreen.openTab(tab)
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        KotoriTabletRail(
            footer = {
                KotoriCircleAction(
                    icon = Icons.Filled.CloudOff,
                    contentDescription = stringResource(MR.strings.label_downloaded_only),
                    onClick = { basePreferences.downloadedOnly.set(!downloadedOnly) },
                    tint = if (downloadedOnly) KotoriColors.success else KotoriTheme.accent.light,
                )
                KotoriCircleAction(
                    icon = Icons.Filled.Settings,
                    contentDescription = stringResource(MR.strings.label_settings),
                    onClick = { navigator.push(SettingsScreen()) },
                )
            },
        ) {
            RAIL_ITEMS.forEach { (item, titleRes, icon) ->
                val selected = item == destination
                val title = stringResource(titleRes)
                Item(
                    title = title,
                    selected = selected,
                    onClick = {
                        open(
                            when (item) {
                                KotoriRailDestination.Library -> HomeScreen.Tab.Library()
                                KotoriRailDestination.Updates -> HomeScreen.Tab.Updates
                                KotoriRailDestination.History -> HomeScreen.Tab.History
                                KotoriRailDestination.Browse -> HomeScreen.Tab.Browse()
                                KotoriRailDestination.More -> HomeScreen.Tab.More(toDownloads = false)
                            },
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = if (selected) Color.White else KotoriColors.textMuted,
                        )
                    },
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}
