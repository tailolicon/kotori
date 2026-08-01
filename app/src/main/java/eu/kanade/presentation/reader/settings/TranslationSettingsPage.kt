package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import mihon.feature.translation.TRANSLATION_PREFETCH_CHAPTERS
import mihon.feature.translation.TranslationProviderType
import mihon.feature.translation.TranslationStatus
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.util.collectAsState

private val providerLabels = listOf(
    "Gemini" to TranslationProviderType.GEMINI,
    "Groq" to TranslationProviderType.GROQ,
    "Google Dịch" to TranslationProviderType.GOOGLE,
)

/**
 * Gemini models worth offering for this workload, cheapest first.
 *
 * Flash-Lite is the fastest and cheapest but drops and misplaces Vietnamese diacritics often enough
 * to be noticeable — "khả năng" comes back as "kh năng". The heavier Flash models cost more per page
 * and are the ones to reach for when accent errors matter more than throughput.
 */
private val geminiModels = listOf(
    "3.5 Flash-Lite · rẻ" to "gemini-3.5-flash-lite",
    "3.6 Flash · chuẩn" to "gemini-3.6-flash",
    "3.5 Flash Cyber" to "gemini-3.5-flash-cyber",
    "2.5 Flash · cũ" to "gemini-2.5-flash",
)

private val sourceLanguages = listOf(
    "Nhật" to "ja",
    "Trung" to "zh",
    "Hàn" to "ko",
    "Anh" to "en",
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

    val mangaId = manga?.id
    val enabled = mangaId?.let { manager.isEnabled(it) } == true

    val missingKey = when (provider) {
        TranslationProviderType.GEMINI -> geminiKey.isBlank()
        TranslationProviderType.GROQ -> groqKey.isBlank()
        TranslationProviderType.GOOGLE -> false
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
                text = "Cần API key cho ${providerLabels.first { it.second == provider }.first}. " +
                    "Nhập bên dưới, hoặc chọn Google Dịch để dùng miễn phí.",
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
            TranslationStatus.Idle -> if (enabled) {
                Text(
                    text = "Đã dịch xong phần đang đọc. Chương tiếp theo được dịch sẵn ở chế độ nền.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Unit
            }
        }
    }

    LabelledChipRow(label ="Dịch bằng") {
        providerLabels.forEach { (label, type) ->
            FilterChip(
                selected = provider == type,
                onClick = { preferences.provider.set(type) },
                label = { Text(label) },
            )
        }
    }

    LabelledChipRow(label ="Ngôn ngữ gốc") {
        sourceLanguages.forEach { (label, code) ->
            FilterChip(
                selected = sourceLanguage == code,
                onClick = { preferences.sourceLanguage.set(code) },
                label = { Text(label) },
            )
        }
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

/**
 * Chip row with a plain-string heading.
 *
 * The shared `SettingsChipRow` only accepts a `StringResource`; these labels are feature-local
 * Vietnamese strings that are not part of the translated resource set yet.
 */
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
