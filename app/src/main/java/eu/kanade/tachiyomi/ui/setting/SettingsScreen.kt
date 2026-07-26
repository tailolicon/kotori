package eu.kanade.tachiyomi.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.screen.SettingsAppearanceScreen
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.more.settings.screen.SettingsMainScreen
import eu.kanade.presentation.more.settings.screen.SettingsTrackingScreen
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.theme.kotori.AuroraBackground
import eu.kanade.presentation.theme.kotori.KotoriTabletTokens
import eu.kanade.presentation.theme.kotori.isKotoriTablet

class SettingsScreen(
    private val destination: Int? = null,
) : Screen() {

    constructor(destination: Destination) : this(destination.id)

    @Composable
    override fun Content() {
        val parentNavigator = LocalNavigator.currentOrThrow
        if (!isKotoriTablet()) {
            Navigator(
                screen = when (destination) {
                    Destination.About.id -> AboutScreen
                    Destination.DataAndStorage.id -> SettingsDataScreen
                    Destination.Tracking.id -> SettingsTrackingScreen
                    else -> SettingsMainScreen
                },
                onBackPressed = null,
            ) {
                val pop: () -> Unit = {
                    if (it.canPop) {
                        it.pop()
                    } else {
                        parentNavigator.pop()
                    }
                }
                CompositionLocalProvider(LocalBackPress provides pop) {
                    DefaultNavigatorScreenTransition(navigator = it)
                }
            }
        } else {
            Navigator(
                screen = when (destination) {
                    Destination.About.id -> AboutScreen
                    Destination.DataAndStorage.id -> SettingsDataScreen
                    Destination.Tracking.id -> SettingsTrackingScreen
                    else -> SettingsAppearanceScreen
                },
                onBackPressed = null,
            ) {
                // T8: a 330dp settings column against a detail pane, both over one aurora.
                val insets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)
                AuroraBackground {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(insets)
                            .consumeWindowInsets(insets),
                    ) {
                        Box(modifier = Modifier.width(KotoriTabletTokens.settingsListPane)) {
                            CompositionLocalProvider(LocalBackPress provides parentNavigator::pop) {
                                SettingsMainScreen.Content(twoPane = true)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(Color(0x12FFFFFF)),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            DefaultNavigatorScreenTransition(navigator = it)
                        }
                    }
                }
            }
        }
    }

    sealed class Destination(val id: Int) {
        data object About : Destination(0)
        data object DataAndStorage : Destination(1)
        data object Tracking : Destination(2)
    }
}
