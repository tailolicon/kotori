package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Progress of the update download, with the install offered right here.
 *
 * The notification keeps working and is still what catches someone who left the app. This is for
 * the reader who did not leave: they tapped "Update" and are watching, so the bar and the install
 * button belong in front of them rather than behind a trip to the notification shade.
 */
@Composable
fun AppUpdateDownloadDialog(
    state: AppUpdateDownloadState.State,
    onDismissRequest: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
) {
    val finished = state as? AppUpdateDownloadState.State.Finished
    val error = state as? AppUpdateDownloadState.State.Error

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = when {
                    finished != null -> "Đã tải xong"
                    error != null -> "Tải thất bại"
                    else -> stringResource(MR.strings.update_check_notification_download_in_progress)
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            ) {
                when {
                    finished != null -> Text("Bản cập nhật đã sẵn sàng để cài đặt.")

                    error != null -> Text(
                        text = error.message
                            ?.let { "Không tải được bản cập nhật: $it" }
                            ?: "Không tải được bản cập nhật.",
                    )

                    else -> {
                        val progress = (state as? AppUpdateDownloadState.State.Downloading)?.progress
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "$progress%",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            text = "Bạn có thể thoát màn hình này; quá trình tải vẫn tiếp tục và " +
                                "sẽ báo ở thanh thông báo.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                finished != null -> TextButton(onClick = onInstall) {
                    Text(text = stringResource(MR.strings.action_install))
                }
                error != null -> TextButton(onClick = onRetry) {
                    Text(text = stringResource(MR.strings.action_retry))
                }
                else -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_close))
            }
        },
    )
}
