package mihon.feature.animeextension

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.theme.kotori.AnimeAccent
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriHeader
import eu.kanade.presentation.theme.kotori.KotoriHeaderAction
import eu.kanade.presentation.theme.kotori.KotoriScreenScaffold
import eu.kanade.presentation.theme.kotori.KotoriSectionLabel
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import eu.kanade.presentation.theme.kotori.glass
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Anime extensions manager (design 08): installed list + available catalog
 * from the Aniyomi repo, install/update/uninstall via the system installer.
 */
class AnimeExtensionScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { Injekt.get<AnimeExtensionManager>() }
        val scope = rememberCoroutineScope()
        val installed by manager.installedFlow.collectAsState()
        val available by manager.availableFlow.collectAsState()
        var status by remember { mutableStateOf<String?>("Đang tải kho tiện ích…") }

        LaunchedEffect(Unit) {
            manager.refreshInstalled()
            status = "Đang tải kho tiện ích…"
            manager.refreshAvailable()
                .onSuccess { status = if (it == 0) "Kho trống" else null }
                .onFailure { status = "Không tải được kho: ${it.message}" }
        }

        // Available minus already-installed
        val installedPkgs = installed.map { it.pkgName }.toSet()
        val notInstalled = available.filter { it.pkgName !in installedPkgs }

        KotoriScreenScaffold(
            header = {
                KotoriHeader(
                    title = "Tiện ích Anime",
                    subtitle = "Kho: yuzono/anime-repo",
                    onNavigateUp = navigator::pop,
                    actions = {
                        KotoriHeaderAction(
                            icon = Icons.Filled.Refresh,
                            contentDescription = "Làm mới",
                            onClick = {
                                scope.launch {
                                    status = "Đang tải kho tiện ích…"
                                    manager.refreshAvailable()
                                        .onSuccess { status = null }
                                        .onFailure { status = "Không tải được kho: ${it.message}" }
                                }
                            },
                        )
                    },
                )
            },
        ) { _ ->
            LazyColumn {
                if (installed.isNotEmpty()) {
                    item {
                        KotoriSectionLabel(
                            text = "ĐÃ CÀI · ${installed.size}",
                            accent = AnimeAccent,
                            modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                    items(installed, key = { it.pkgName }) { ext ->
                        InstalledExtensionRow(
                            name = ext.name,
                            info = "${ext.versionName} · ${ext.lang.uppercase()}",
                            buttonText = if (ext.hasUpdate) "Cập nhật" else "Gỡ",
                            highlight = ext.hasUpdate,
                            onButton = {
                                if (ext.hasUpdate) {
                                    available.find { it.pkgName == ext.pkgName }?.let { avail ->
                                        scope.launch { manager.installExtension(avail) }
                                    }
                                } else {
                                    manager.uninstallExtension(ext.pkgName)
                                }
                            },
                            onOpen = if (ext.sources.isNotEmpty()) {
                                { navigator.push(AnimeSourceCatalogScreen(ext.sources.first().id)) }
                            } else {
                                null
                            },
                        )
                    }
                }

                item {
                    KotoriSectionLabel(
                        text = "CÓ SẴN · ${notInstalled.size}",
                        accent = AnimeAccent,
                        modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                if (status != null) {
                    item {
                        Text(
                            text = status!!,
                            fontFamily = BeVietnamProFamily,
                            fontSize = 11.sp,
                            color = KotoriColors.textMuted,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        )
                    }
                }
                items(notInstalled, key = { it.pkgName }) { ext ->
                    InstalledExtensionRow(
                        name = ext.name,
                        info = "${ext.versionName} · ${ext.lang.uppercase()}",
                        buttonText = "Cài",
                        highlight = true,
                        onButton = { scope.launch { manager.installExtension(ext) } },
                        onOpen = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledExtensionRow(
    name: String,
    info: String,
    buttonText: String,
    highlight: Boolean,
    onButton: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 18.dp, vertical = 4.5.dp)
            .glass(shape = KotoriShapes.row)
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(AnimeAccent.gradient, KotoriShapes.monogram),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.take(1).uppercase(),
                fontFamily = UnboundedFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = AnimeAccent.onAccent,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = KotoriColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = info,
                fontFamily = BeVietnamProFamily,
                fontSize = 10.5.sp,
                color = KotoriColors.textMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .then(
                    if (highlight) {
                        Modifier
                            .background(AnimeAccent.gradient, KotoriShapes.chip)
                    } else {
                        Modifier.glass(shape = KotoriShapes.chip, elevated = true)
                    },
                )
                .clickable(onClick = onButton)
                .padding(horizontal = 13.dp, vertical = 7.dp),
        ) {
            Text(
                text = buttonText,
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = if (highlight) AnimeAccent.onAccent else KotoriColors.textSecondary,
            )
        }
    }
}
