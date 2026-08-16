package eu.kanade.tachiyomi.data.updater

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Where the update download has got to, for the UI to watch.
 *
 * The download runs in a worker and has always reported through a notification. That is the right
 * place for it to keep reporting — the reader may well leave the app — but it should not be the
 * *only* place: someone who just tapped "Update" is still looking at the screen, and sending them
 * to the shade to find a bar and then a button is a detour. The worker publishes here as well, so
 * the screen can show the same progress and offer the install itself.
 */
object AppUpdateDownloadState {

    val state = MutableStateFlow<State>(State.Idle)

    fun reset() {
        state.value = State.Idle
    }

    sealed interface State {
        data object Idle : State

        /** [progress] is 0..100, or null while the size is still unknown. */
        data class Downloading(val progress: Int?) : State

        data class Finished(val apkUri: Uri) : State

        data class Error(val message: String?) : State
    }
}
