from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"patch anchor not found: {label}")
    return text.replace(old, new, 1)


root = Path(__file__).resolve().parents[1]

menu_path = root / "app/src/main/java/eu/kanade/presentation/components/DownloadDropdownMenu.kt"
menu = menu_path.read_text(encoding="utf-8")
menu = replace_once(
    menu,
    "import eu.kanade.presentation.manga.DownloadAction\n",
    "import eu.kanade.presentation.manga.DownloadAction\nimport mihon.feature.factory.MangaFactoryBridge\n",
    "download menu import",
)
menu = replace_once(
    menu,
    """    options.map { (downloadAction, string) ->
        DropdownMenuItem(
            text = { Text(text = string) },
            onClick = {
                onDownloadClicked(downloadAction)
                onDismissRequest()
            },
        )
    }
}""",
    """    options.map { (downloadAction, string) ->
        DropdownMenuItem(
            text = { Text(text = string) },
            onClick = {
                onDownloadClicked(downloadAction)
                onDismissRequest()
            },
        )
    }

    if (MangaFactoryBridge.available) {
        DropdownMenuItem(
            text = { Text("Gửi truyện đã tải → Manga TL Factory") },
            onClick = {
                onDismissRequest()
                MangaFactoryBridge.requestExport()
            },
        )
    }
}""",
    "factory download menu item",
)
menu_path.write_text(menu, encoding="utf-8")

screen_path = root / "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt"
screen = screen_path.read_text(encoding="utf-8")
screen = replace_once(
    screen,
    "import androidx.compose.runtime.Composable\n",
    "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.DisposableEffect\n",
    "DisposableEffect import",
)
screen = replace_once(
    screen,
    "import eu.kanade.presentation.components.NavigatorAdaptiveSheet\n",
    "import eu.kanade.presentation.components.FactoryExportDialog\nimport eu.kanade.presentation.components.NavigatorAdaptiveSheet\n",
    "FactoryExportDialog import",
)
screen = replace_once(
    screen,
    "import eu.kanade.tachiyomi.source.Source\n",
    "import eu.kanade.tachiyomi.data.download.DownloadProvider\nimport eu.kanade.tachiyomi.data.download.model.Download\nimport eu.kanade.tachiyomi.source.Source\n",
    "download imports",
)
screen = replace_once(
    screen,
    "import mihon.feature.migration.config.MigrationConfigScreen\n",
    "import mihon.feature.factory.MangaFactoryBridge\nimport mihon.feature.factory.MangaFactoryExporter\nimport mihon.feature.migration.config.MigrationConfigScreen\n",
    "factory imports",
)
screen = replace_once(
    screen,
    """        val haptic = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val lifecycleOwner = LocalLifecycleOwner.current
""",
    """        val haptic = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        var showFactoryExportDialog by remember { mutableStateOf(false) }
        val factoryExporter = remember(context) { MangaFactoryExporter(DownloadProvider(context)) }
        val lifecycleOwner = LocalLifecycleOwner.current
""",
    "factory state",
)
screen = replace_once(
    screen,
    """        val successState = state as MangaScreenModel.State.Success
        val isHttpSource = remember { successState.source is HttpSource }

        LaunchedEffect(successState.manga, screenModel.source) {
""",
    """        val successState = state as MangaScreenModel.State.Success
        val isHttpSource = remember { successState.source is HttpSource }
        val factoryChapters = remember(successState.chapters) {
            successState.chapters
                .filter { it.downloadState == Download.State.DOWNLOADED }
                .map { it.chapter }
        }

        DisposableEffect(mangaId) {
            MangaFactoryBridge.bind(mangaId) { showFactoryExportDialog = true }
            onDispose { MangaFactoryBridge.unbind(mangaId) }
        }

        LaunchedEffect(successState.manga, screenModel.source) {
""",
    "factory binding",
)
screen = replace_once(
    screen,
    """            onAllChapterSelected = screenModel::toggleAllSelection,
            onInvertSelection = screenModel::invertSelection,
        )

        var showScanlatorsDialog by remember { mutableStateOf(false) }
""",
    """            onAllChapterSelected = screenModel::toggleAllSelection,
            onInvertSelection = screenModel::invertSelection,
        )

        if (showFactoryExportDialog) {
            FactoryExportDialog(
                chapterCount = factoryChapters.size,
                onDismissRequest = { showFactoryExportDialog = false },
                onConfirm = { token ->
                    showFactoryExportDialog = false
                    context.toast("Đang gửi ${factoryChapters.size} chương lên Manga TL Factory…")
                    scope.launch {
                        try {
                            val result = withIOContext {
                                factoryExporter.export(
                                    manga = successState.manga,
                                    chapters = factoryChapters,
                                    source = successState.source,
                                    token = token,
                                    targetLanguage = "vi",
                                )
                            }
                            screenModel.snackbarHostState.showSnackbar(
                                message = "Đã gửi ${result.chapterCount} chương / ${result.pageCount} trang · ${result.commitSha.take(8)}",
                            )
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) { "Manga TL Factory export failed" }
                            screenModel.snackbarHostState.showSnackbar(
                                message = "Gửi Manga TL Factory thất bại: ${e.message ?: e::class.simpleName}",
                            )
                        }
                    }
                },
            )
        }

        var showScanlatorsDialog by remember { mutableStateOf(false) }
""",
    "factory dialog",
)
screen_path.write_text(screen, encoding="utf-8")

print("Manga TL Factory UI patch applied")
