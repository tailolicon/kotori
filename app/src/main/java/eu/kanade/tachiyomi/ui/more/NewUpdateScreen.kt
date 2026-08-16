package eu.kanade.tachiyomi.ui.more

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.AppUpdateDownloadDialog
import eu.kanade.presentation.more.NewUpdateScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadJob
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadState
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.util.system.openInBrowser

class NewUpdateScreen(
    private val versionName: String,
    private val changelogInfo: String,
    private val releaseLink: String,
    private val downloadLink: String,
    private val sha256: String? = null,
    private val size: Long? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val changelogInfoNoChecksum = remember {
            changelogInfo.replace("""---(\R|.)*Checksums(\R|.)*""".toRegex(), "")
        }

        val downloadState by AppUpdateDownloadState.state.collectAsState()

        val startDownload = {
            AppUpdateDownloadJob.start(
                context = context,
                url = downloadLink,
                title = versionName,
                sha256 = sha256,
                size = size,
            )
        }

        NewUpdateScreen(
            versionName = versionName,
            changelogInfo = changelogInfoNoChecksum,
            onOpenInBrowser = releaseLink.takeIf(String::isNotBlank)?.let { link ->
                { context.openInBrowser(link) }
            },
            onRejectUpdate = navigator::pop,
            // Stay on the screen rather than popping: the dialog below is what reports progress and
            // hands over the install.
            onAcceptUpdate = startDownload,
        )

        // Deliberately not reset when this screen opens: a download that finished while the
        // screen was away is exactly the state worth showing, since the apk is sitting there ready
        // to install. It clears when the reader closes the dialog.
        if (downloadState !is AppUpdateDownloadState.State.Idle) {
            AppUpdateDownloadDialog(
                state = downloadState,
                onDismissRequest = {
                    AppUpdateDownloadState.reset()
                    navigator.pop()
                },
                onInstall = {
                    val uri = (downloadState as AppUpdateDownloadState.State.Finished).apkUri
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, ExtensionInstaller.APK_MIME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        },
                    )
                },
                onRetry = {
                    AppUpdateDownloadState.reset()
                    startDownload()
                },
            )
        }
    }
}
