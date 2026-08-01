package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
enum class KotoriRailDestination(internal val index: Int) {
    Library(0),
    Updates(1),
    History(2),
    Browse(3),
    More(4),
}

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
            HomeScreen.RAIL_TABS.forEachIndexed { index, tab ->
                val selected = index == destination.index
                Item(
                    title = tab.options.title,
                    selected = selected,
                    onClick = {
                        open(
                            when (index) {
                                0 -> HomeScreen.Tab.Library()
                                1 -> HomeScreen.Tab.Updates
                                2 -> HomeScreen.Tab.History
                                3 -> HomeScreen.Tab.Browse()
                                else -> HomeScreen.Tab.More(toDownloads = false)
                            },
                        )
                    },
                    icon = {
                        CompositionLocalProvider(
                            LocalContentColor provides if (selected) Color.White else KotoriColors.textMuted,
                        ) {
                            Icon(
                                painter = tab.options.icon!!,
                                contentDescription = tab.options.title,
                            )
                        }
                    },
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}
