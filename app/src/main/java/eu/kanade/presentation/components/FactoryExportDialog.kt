package eu.kanade.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import tachiyomi.domain.chapter.model.Chapter

@Composable
fun FactoryExportDialog(
    chapters: List<Chapter>,
    onDismissRequest: () -> Unit,
    onConfirm: (Chapter, String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var selectedChapterId by remember(chapters) { mutableStateOf(chapters.firstOrNull()?.id) }
    val selectedChapter = chapters.firstOrNull { it.id == selectedChapterId }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Manga TL Factory") },
        text = {
            Column {
                Text(
                    "Chọn một chương để Kotori resolve URL trang. Ảnh không được tải lên GitHub; " +
                        "worker của Factory sẽ tự tải và kiểm tra trong thư mục tạm.",
                )
                Spacer(Modifier.height(12.dp))
                if (chapters.isEmpty()) {
                    Text("Nguồn này không có chương có thể gửi.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(chapters, key = Chapter::id) { chapter ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedChapterId = chapter.id },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = chapter.id == selectedChapterId,
                                    onClick = { selectedChapterId = chapter.id },
                                )
                                Text(chapter.name)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Token chỉ dùng cho lần gửi này và không được lưu trong Kotori.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("GitHub fine-grained token") },
                    supportingText = { Text("Cần Contents: Read and write cho manga-tl-factory") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = selectedChapter != null,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedChapter != null && token.isNotBlank(),
                onClick = {
                    selectedChapter?.let { onConfirm(it, token.trim()) }
                },
            ) {
                Text("Gửi handoff")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Hủy")
            }
        },
    )
}
