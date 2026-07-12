package mihon.feature.localmedia

import com.hippo.unifile.UniFile
import eu.kanade.domain.ui.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.storage.service.StorageManager

/**
 * Local anime/novel content (design "content-type union"): titles are folders
 * under the storage root (`anime/<title>/` with video files, `novel/<title>/`
 * with text files) — the same pattern as Mihon's local manga source. Online
 * anime/novel sources plug in through the extension contract later; this
 * makes both modes fully functional today.
 */
data class LocalMediaEntry(
    val type: MediaType,
    val title: String,
    val files: List<LocalMediaFile>,
    val sourceName: String = "Cục bộ",
) : java.io.Serializable

data class LocalMediaFile(
    val name: String,
    val uri: String,
) : java.io.Serializable

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts")
private val NOVEL_EXTENSIONS = setOf("txt", "text", "md")

suspend fun loadLocalMedia(
    storageManager: StorageManager,
    type: MediaType,
): List<LocalMediaEntry> = withContext(Dispatchers.IO) {
    val root: UniFile? = when (type) {
        MediaType.ANIME -> storageManager.getLocalAnimeDirectory()
        MediaType.NOVEL -> storageManager.getLocalNovelDirectory()
        MediaType.MANGA -> null
    }
    val extensions = when (type) {
        MediaType.ANIME -> VIDEO_EXTENSIONS
        else -> NOVEL_EXTENSIONS
    }
    root?.listFiles()
        .orEmpty()
        .filter { it.isDirectory }
        .mapNotNull { dir ->
            val name = dir.name ?: return@mapNotNull null
            val files = dir.listFiles()
                .orEmpty()
                .filter { file ->
                    !file.isDirectory &&
                        (file.name ?: "").substringAfterLast('.', "").lowercase() in extensions
                }
                .sortedBy { it.name }
                .map { LocalMediaFile(name = it.name ?: "?", uri = it.uri.toString()) }
            LocalMediaEntry(type = type, title = name, files = files)
                .takeIf { files.isNotEmpty() }
        }
        .sortedBy { it.title }
}

suspend fun readLocalNovelChapter(uniFileUri: String, resolver: android.content.ContentResolver): String =
    withContext(Dispatchers.IO) {
        runCatching {
            resolver.openInputStream(android.net.Uri.parse(uniFileUri))?.use {
                it.readBytes().decodeToString()
            }
        }.getOrNull() ?: "Không đọc được nội dung."
    }
