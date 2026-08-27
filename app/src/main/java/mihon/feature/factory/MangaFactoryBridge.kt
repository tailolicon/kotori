package mihon.feature.factory

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Small UI bridge so the shared manga download menu can ask the currently visible manga screen
 * to open the Factory export dialog without threading another callback through every phone/tablet
 * presentation layer.
 */
object MangaFactoryBridge {
    private data class Entry(
        val ownerId: Long,
        val handler: () -> Unit,
    )

    private var entry by mutableStateOf<Entry?>(null)

    val available: Boolean
        get() = entry != null

    fun bind(ownerId: Long, handler: () -> Unit) {
        entry = Entry(ownerId, handler)
    }

    fun unbind(ownerId: Long) {
        if (entry?.ownerId == ownerId) {
            entry = null
        }
    }

    fun requestExport() {
        entry?.handler?.invoke()
    }
}
