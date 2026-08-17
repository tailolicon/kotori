package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import kotlinx.coroutines.launch
import mihon.feature.translation.TRANSLATION_PREFETCH_CHAPTERS
import mihon.feature.translation.TranslationProviderType
import mihon.feature.translation.TranslationStatus
import mihon.feature.translation.offline.OfflineLicenseText
import mihon.feature.translation.offline.OfflineModelSpec
import mihon.feature.translation.offline.OfflineModelState
import mihon.feature.translation.provider.TranslationProviders
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale

private val providerLabels = listOf(
    "Gemini" to TranslationProviderType.GEMINI,
    "Groq" to TranslationProviderType.GROQ,
    "Google Dịch" to TranslationProviderType.GOOGLE,
)

/**
 * Gemini models worth offering for this workload, ordered by how much reading they allow per day.
 *
 * The daily request allowance is what actually decides whether a chapter finishes, and on the free
 * tier the spread is enormous: the headline Flash models permit 20 requests a day — one chapter
 * exhausts that — while 3.1 Flash-Lite allows 500. The label carries the number because it is the
 * only figure that predicts whether translation stops halfway.
 *
 * Gemma is deliberately absent despite advertising 14,400 requests a day. Asked for this pipeline's
 * JSON contract it answers with a *restatement of the instructions* — "Role: Manga translator.
 * Input format: ..." — rather than the translation, on both 26B and 31B. An allowance that large is
 * worth nothing when nothing parses.
 *
 * Flash-Lite is fastest and cheapest but drops and misplaces Vietnamese diacritics often enough to
 * be noticeable — "khả năng" comes back as "kh năng". The heavier Flash models are the ones to reach
 * for when accent errors matter more than throughput.
 */
private val geminiModels = listOf(
    "3.1 Flash-Lite · 500/ngày" to "gemini-3.1-flash-lite",
    "3.5 Flash-Lite · rẻ" to "gemini-3.5-flash-lite",
    "3.5 Flash · 20/ngày" to "gemini-3.5-flash",
    "3.6 Flash · 20/ngày" to "gemini-3.6-flash",
    "2.5 Flash · cũ" to "gemini-2.5-flash",
)

private val sourceLanguages = listOf(
    "Nhật" to "ja",
    "Trung" to "zh",
    "Hàn" to "ko",
    "Anh" to "en",
    "Tây Ban Nha" to "es",
)

/**
 * Languages the reader can ask for.
 *
 * Vietnamese gets a much longer, hand-written prompt than the rest — see [TranslationPrompts] — so
 * it stays first. The others share a generic prompt, which is why adding one is a list entry and
 * nothing more: the recogniser for any Latin-script source is the same one, and every guard
 * downstream already keys on the code rather than on Vietnamese specifically.
 */
private val targetLanguages = listOf(
    "Việt" to "vi",
    "Anh" to "en",
    "Tây Ban Nha" to "es",
)

/**
 * Reader-side translation controls.
 *
 * The primary action translates the chapter on screen plus the next
 * [TRANSLATION_PREFETCH_CHAPTERS] chapters, which is what makes continuous reading possible: by the
 * time the reader reaches a chapter boundary the following chapters are already rendered.
 */
@Composable
internal fun ColumnScope.TranslationPage(screenModel: ReaderSettingsScreenModel) {
    val preferences = screenModel.translationPreferences
    val manager = screenModel.translationManager
    val manga by screenModel.mangaFlow.collectAsState()
    val status by manager.status.collectAsState()

    val provider by preferences.provider.collectAsState()
    val sourceLanguage by preferences.sourceLanguage.collectAsState()
    val geminiKey by preferences.geminiApiKey.collectAsState()
    val groqKey by preferences.groqApiKey.collectAsState()
    val offlineState by manager.offlineModelStore.state.collectAsState()
    val licenseAccepted by preferences.offlineLicenseAcceptedVersion.collectAsState()
    val threadCount by preferences.offlineThreadCount.collectAsState()
    val scope = rememberCoroutineScope()

    val mangaId = manga?.id
    val enabled = mangaId?.let { manager.isEnabled(it) } == true
    val offlineReady = offlineState.isReady || preferences.offlineModelReady.get()

    val missingKey = when (provider) {
        TranslationProviderType.GEMINI -> geminiKey.isBlank()
        TranslationProviderType.GROQ -> groqKey.isBlank()
        TranslationProviderType.GOOGLE -> false
        TranslationProviderType.OFFLINE -> !offlineReady
    }

    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (enabled) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { screenModel.onToggleTranslation(false) },
                ) {
                    Text("Tắt dịch cho truyện này")
                }
            } else {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = mangaId != null && !missingKey,
                    onClick = { screenModel.onToggleTranslation(true) },
                ) {
                    Text("Dịch chương này + $TRANSLATION_PREFETCH_CHAPTERS chương sau")
                }
            }
        }

        if (missingKey) {
            Text(
                text = when (provider) {
                    TranslationProviderType.OFFLINE -> stringResource(
                        MR.strings.translation_offline_need_model,
                        formatBytes(OfflineModelSpec.EXPECTED_SIZE_BYTES),
                    )
                    else ->
                        "Cần API key cho ${providerLabels.first { it.second == provider }.first}. " +
                            "Nhập bên dưới, hoặc chọn Google Dịch / Offline."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when (val current = status) {
            is TranslationStatus.Working -> {
                Text(
                    text = "${current.label} — ${current.completed}/${current.total} trang",
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = {
                        if (current.total == 0) 0f else current.completed.toFloat() / current.total
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is TranslationStatus.Failed -> Text(
                text = current.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TranslationStatus.Idle -> {
                if (enabled) {
                    Text(
                        text = "Đã dịch xong phần đang đọc. Chương tiếp theo được dịch sẵn ở chế độ nền.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    LabelledChipRow(label = "Dịch bằng") {
        providerLabels.forEach { (label, type) ->
            FilterChip(
                selected = provider == type,
                onClick = {
                    if (type != TranslationProviderType.OFFLINE) {
                        TranslationProviders.releaseOffline()
                    }
                    preferences.provider.set(type)
                },
                label = { Text(label) },
            )
        }
        FilterChip(
            selected = provider == TranslationProviderType.OFFLINE,
            onClick = { preferences.provider.set(TranslationProviderType.OFFLINE) },
            label = { Text(stringResource(MR.strings.translation_provider_offline)) },
        )
    }

    LabelledChipRow(label = "Ngôn ngữ gốc") {
        sourceLanguages.forEach { (label, code) ->
            FilterChip(
                selected = sourceLanguage == code,
                onClick = { preferences.sourceLanguage.set(code) },
                label = { Text(label) },
            )
        }
    }

    val targetLanguage by preferences.targetLanguage.collectAsState()
    LabelledChipRow(label = "Dịch sang") {
        targetLanguages.forEach { (label, code) ->
            FilterChip(
                selected = targetLanguage == code,
                onClick = { preferences.targetLanguage.set(code) },
                label = { Text(label) },
            )
        }
    }

    val simpleRender by preferences.simpleRender.collectAsState()
    LabelledChipRow(label = "Kiểu vẽ chữ") {
        FilterChip(
            selected = simpleRender,
            onClick = { preferences.simpleRender.set(true) },
            label = { Text("Đơn giản — xoá thoại, ghi đè đúng chỗ (nhanh hơn nhiều)") },
        )
        FilterChip(
            selected = !simpleRender,
            onClick = { preferences.simpleRender.set(false) },
            label = { Text("Theo bóng thoại (cũ, chậm)") },
        )
    }

    when (provider) {
        TranslationProviderType.GEMINI -> {
            TextItem(
                label = "Gemini API key",
                value = geminiKey,
                onChange = preferences.geminiApiKey::set,
            )
            val currentModel by preferences.geminiModel.collectAsState()
            LabelledChipRow(label = "Model") {
                geminiModels.forEach { (label, id) ->
                    FilterChip(
                        selected = currentModel == id,
                        onClick = { preferences.geminiModel.set(id) },
                        label = { Text(label) },
                    )
                }
            }
            TextItem(
                label = "Hoặc nhập ID model khác",
                value = currentModel,
                onChange = preferences.geminiModel::set,
            )
        }
        TranslationProviderType.GROQ -> {
            TextItem(
                label = "Groq API key",
                value = groqKey,
                onChange = preferences.groqApiKey::set,
            )
            TextItem(
                label = "Model",
                value = preferences.groqModel.get(),
                onChange = preferences.groqModel::set,
            )
        }
        TranslationProviderType.GOOGLE -> Text(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            text = "Google Dịch không cần key. Chất lượng hội thoại ổn, nhưng kém hơn Gemini rõ rệt " +
                "với truyện nhiều ngữ cảnh.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TranslationProviderType.OFFLINE -> OfflineProviderSection(
            state = offlineState,
            threadCount = threadCount,
            licenseAccepted = licenseAccepted >= OfflineModelSpec.LICENSE_ACCEPTANCE_VERSION,
            onThreadCount = { preferences.offlineThreadCount.set(it) },
            onAcceptLicense = { preferences.acceptOfflineLicense() },
            onDownload = {
                if (preferences.offlineLicenseAccepted()) {
                    scope.launch { manager.offlineModelStore.download() }
                }
            },
            onCancel = { manager.offlineModelStore.cancelDownload() },
            onDelete = {
                scope.launch { manager.deleteOfflineModel() }
            },
        )
    }

    TextItem(
        label = "Yêu cầu văn phong (tuỳ chọn)",
        value = preferences.styleHint.get(),
        onChange = preferences.styleHint::set,
    )

    val cacheSizePref = preferences.cacheSizeMb
    val cacheSize by cacheSizePref.collectAsState()
    SliderItem(
        label = "Giới hạn bộ đệm trang đã dịch",
        value = cacheSize,
        valueRange = 128..4096 step 128,
        valueString = "$cacheSize MB",
        onChange = cacheSizePref::set,
    )

    val retentionPref = preferences.cacheRetentionHours
    val retention by retentionPref.collectAsState()
    SliderItem(
        label = "Tự xoá bản dịch sau khi thoát truyện",
        value = retention,
        valueRange = 0..168 step 6,
        valueString = if (retention == 0) "Không tự xoá" else "$retention giờ",
        onChange = retentionPref::set,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { mangaId?.let(manager::clearFor) }) {
            Text("Xoá bản dịch truyện này")
        }
        OutlinedButton(onClick = manager::clearAll) {
            Text("Xoá tất cả")
        }
    }
}

@Composable
private fun LabelledChipRow(label: String, content: @Composable FlowRowScope.() -> Unit) {
    Column {
        HeadingItem(text = label)
        FlowRow(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun OfflineProviderSection(
    state: OfflineModelState,
    threadCount: Int,
    licenseAccepted: Boolean,
    onThreadCount: (Int) -> Unit,
    onAcceptLicense: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var showLicenseDialog by remember { mutableStateOf(false) }
    var pendingConfirm by remember { mutableStateOf(licenseAccepted) }

    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                MR.strings.translation_offline_intro,
                formatBytes(OfflineModelSpec.EXPECTED_SIZE_BYTES),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                MR.strings.translation_offline_provider_legal,
                OfflineModelSpec.LEGAL_PROVIDER_ENTITY,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(MR.strings.translation_offline_territory),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!licenseAccepted) {
            CheckboxItem(
                label = stringResource(MR.strings.translation_offline_license_confirm),
                checked = pendingConfirm,
                onClick = { pendingConfirm = !pendingConfirm },
            )
            TextButton(onClick = { showLicenseDialog = true }) {
                Text(stringResource(MR.strings.translation_offline_view_license))
            }
        }

        Text(
            text = offlineStatusText(state),
            style = MaterialTheme.typography.bodySmall,
            color = when (state) {
                is OfflineModelState.Failed -> MaterialTheme.colorScheme.error
                is OfflineModelState.Ready -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )

        if (state is OfflineModelState.Downloading) {
            val progress = if (state.totalBytes > 0L) {
                (state.bytesDownloaded.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else if (state is OfflineModelState.Verifying) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                is OfflineModelState.Downloading, is OfflineModelState.Verifying -> {
                    OutlinedButton(onClick = onCancel) {
                        Text(stringResource(MR.strings.translation_offline_cancel))
                    }
                }
                is OfflineModelState.Ready -> {
                    OutlinedButton(onClick = onDelete) {
                        Text(stringResource(MR.strings.translation_offline_delete))
                    }
                }
                is OfflineModelState.Failed -> {
                    Button(
                        onClick = {
                            if (licenseAccepted || pendingConfirm) {
                                if (!licenseAccepted && pendingConfirm) onAcceptLicense()
                                onDownload()
                            }
                        },
                        enabled = licenseAccepted || pendingConfirm,
                    ) {
                        Text(stringResource(MR.strings.translation_offline_retry))
                    }
                    if (state.canRetry) {
                        OutlinedButton(onClick = onDelete) {
                            Text(stringResource(MR.strings.translation_offline_delete_partial))
                        }
                    }
                }
                OfflineModelState.Missing, is OfflineModelState.Idle -> {
                    val isPartial = state is OfflineModelState.Idle && state.partialBytes > 0L
                    Button(
                        onClick = {
                            if (licenseAccepted || pendingConfirm) {
                                if (!licenseAccepted && pendingConfirm) onAcceptLicense()
                                onDownload()
                            }
                        },
                        enabled = licenseAccepted || pendingConfirm,
                    ) {
                        Text(
                            stringResource(
                                when {
                                    !licenseAccepted -> MR.strings.translation_offline_accept_and_download
                                    isPartial -> MR.strings.translation_offline_resume
                                    else -> MR.strings.translation_offline_download
                                },
                            ),
                        )
                    }
                }
            }
        }

        SliderItem(
            label = stringResource(MR.strings.translation_offline_threads),
            value = threadCount.coerceIn(OfflineModelSpec.MIN_THREADS, OfflineModelSpec.MAX_THREADS),
            valueRange = OfflineModelSpec.MIN_THREADS..OfflineModelSpec.MAX_THREADS,
            valueString = "$threadCount",
            onChange = onThreadCount,
        )
        Text(
            text = stringResource(
                MR.strings.translation_offline_threads_hint,
                OfflineModelSpec.DEFAULT_THREADS,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showLicenseDialog) {
        val licenseBody = remember {
            buildString {
                appendLine(OfflineLicenseText.loadNotice(context))
                appendLine()
                append(OfflineLicenseText.loadLicense(context))
            }
        }
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text(stringResource(MR.strings.translation_offline_license_title)) },
            text = {
                Text(
                    text = licenseBody,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) {
                    Text(stringResource(MR.strings.action_ok))
                }
            },
        )
    }
}

@Composable
private fun offlineStatusText(state: OfflineModelState): String = when (state) {
    OfflineModelState.Missing -> stringResource(MR.strings.translation_offline_status_missing)
    is OfflineModelState.Idle ->
        if (state.partialBytes > 0L) {
            stringResource(
                MR.strings.translation_offline_status_partial,
                formatBytes(state.partialBytes),
                formatBytes(OfflineModelSpec.EXPECTED_SIZE_BYTES),
            )
        } else {
            stringResource(MR.strings.translation_offline_status_missing)
        }
    is OfflineModelState.Downloading -> stringResource(
        MR.strings.translation_offline_status_downloading,
        formatBytes(state.bytesDownloaded),
        formatBytes(state.totalBytes),
    )
    is OfflineModelState.Verifying -> stringResource(
        MR.strings.translation_offline_status_verifying,
        formatBytes(state.bytes),
    )
    is OfflineModelState.Ready -> stringResource(
        MR.strings.translation_offline_status_ready,
        formatBytes(state.bytes),
    )
    is OfflineModelState.Failed -> {
        val detail = state.detail?.let { " ($it)" }.orEmpty()
        when (state.messageKey) {
            "verify_failed" -> stringResource(MR.strings.translation_offline_status_failed_verify, detail)
            "install_failed" -> stringResource(MR.strings.translation_offline_status_failed_install)
            else -> stringResource(MR.strings.translation_offline_status_failed_download, detail)
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.0f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
