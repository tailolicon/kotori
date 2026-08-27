package eu.kanade.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun FactoryExportDialog(
    chapterCount: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Manga TL Factory") },
        text = {
            Column {
                Text(
                    if (chapterCount > 0) {
                        "Sẽ gửi $chapterCount chương đã tải cùng ảnh gốc lên tailolicon/manga-tl-factory."
                    } else {
                        "Chưa có chương nào được tải. Hãy tải chương trước rồi thử lại."
                    },
                )
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
                    enabled = chapterCount > 0,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = chapterCount > 0 && token.isNotBlank(),
                onClick = { onConfirm(token.trim()) },
            ) {
                Text("Gửi")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Hủy")
            }
        },
    )
}
