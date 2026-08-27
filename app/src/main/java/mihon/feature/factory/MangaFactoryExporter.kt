package mihon.feature.factory

import android.util.Base64
import com.hippo.unifile.UniFile
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class MangaFactoryExporter(
    private val downloadProvider: DownloadProvider,
) {
    data class ExportResult(
        val projectId: String,
        val chapterCount: Int,
        val pageCount: Int,
        val commitSha: String,
    )

    private data class PageUpload(
        val index: Int,
        val path: String,
        val sha256: String,
        val blobSha: String,
    )

    private data class ChapterUpload(
        val chapter: Chapter,
        val chapterId: String,
        val pages: List<PageUpload>,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    fun export(
        manga: Manga,
        chapters: List<Chapter>,
        source: Source,
        token: String,
        targetLanguage: String = "vi",
    ): ExportResult {
        require(token.isNotBlank()) { "GitHub token is empty" }
        require(chapters.isNotEmpty()) { "No downloaded chapters to export" }

        val sourceUrl = resolveSourceUrl(manga, source)
        val projectId = slugifySource(sourceUrl.ifBlank { manga.title })
        val importId = "kotori-${manga.id}-${System.currentTimeMillis().toString(36)}"
        val requestId = "req-$importId"
        val basePath = "imports/kotori/$projectId/$importId"

        val headSha = api("git/ref/heads/$TARGET_BRANCH", token)
            .getJSONObject("object")
            .getString("sha")
        val baseTreeSha = api("git/commits/$headSha", token)
            .getJSONObject("tree")
            .getString("sha")

        val chapterUploads = chapters
            .sortedWith(compareBy<Chapter> { it.chapterNumber }.thenBy { it.sourceOrder })
            .mapIndexed { chapterIndex, chapter ->
                val chapterId = chapterId(chapter)
                val chapterPath = "$basePath/chapters/${"%04d".format(Locale.ENGLISH, chapterIndex + 1)}-$chapterId"
                val downloaded = downloadProvider.findChapterDir(
                    chapterName = chapter.name,
                    chapterScanlator = chapter.scanlator,
                    chapterUrl = chapter.url,
                    mangaTitle = manga.title,
                    source = source,
                ) ?: throw IOException("Downloaded chapter not found: ${chapter.name}")

                ChapterUpload(
                    chapter = chapter,
                    chapterId = chapterId,
                    pages = uploadChapterPages(downloaded, chapterPath, token),
                )
            }

        val sourceManifest = buildSourceManifest(
            projectId = projectId,
            sourceUrl = sourceUrl,
            manga = manga,
            source = source,
            chapters = chapterUploads,
        )
        val project = JSONObject()
            .put("project_id", projectId)
            .put("source", JSONObject().put("kind", "url").put("value", sourceUrl))
            .put("source_language", JSONObject.NULL)
            .put("target_language", targetLanguage)
            .put("status", "source_ready")
            .put("context_version", JSONObject.NULL)
            .put("pipeline_version", "1.0.0")
            .put("created_from_request", requestId)
            .put("acquired_by", "kotori")
        val request = JSONObject()
            .put("request_id", requestId)
            .put("source", JSONObject().put("kind", "url").put("value", sourceUrl))
            .put("source_language", JSONObject.NULL)
            .put("target_language", targetLanguage)
            .put("created_at", Instant.now().toString())
            .put("status", "source_ready")
            .put("project_id", projectId)
            .put("acquired_by", "kotori")
        val importMeta = JSONObject()
            .put("schema", 1)
            .put("import_id", importId)
            .put("project_id", projectId)
            .put("created_at", Instant.now().toString())
            .put("source_id", source.id)
            .put("source_name", source.name)
            .put("source_url", sourceUrl)
            .put("manga_id", manga.id)
            .put("manga_title", manga.title)
            .put("chapter_count", chapterUploads.size)
            .put("page_count", chapterUploads.sumOf { it.pages.size })

        val treeEntries = JSONArray()
        chapterUploads.flatMap { it.pages }.forEach { page ->
            treeEntries.put(treeEntry(page.path, page.blobSha))
        }
        treeEntries.put(textTreeEntry("$basePath/import.json", importMeta, token))
        treeEntries.put(textTreeEntry("projects/$projectId/project.json", project, token))
        treeEntries.put(textTreeEntry("projects/$projectId/source_manifest.json", sourceManifest, token))
        treeEntries.put(textTreeEntry("requests/$requestId.json", request, token))

        val treeSha = api(
            "git/trees",
            token,
            method = "POST",
            body = JSONObject()
                .put("base_tree", baseTreeSha)
                .put("tree", treeEntries),
        ).getString("sha")

        val commitSha = api(
            "git/commits",
            token,
            method = "POST",
            body = JSONObject()
                .put("message", "import(kotori): ${manga.title} · ${chapterUploads.size} chapters")
                .put("tree", treeSha)
                .put("parents", JSONArray().put(headSha)),
        ).getString("sha")

        api(
            "git/refs/heads/$TARGET_BRANCH",
            token,
            method = "PATCH",
            body = JSONObject()
                .put("sha", commitSha)
                .put("force", false),
        )

        return ExportResult(
            projectId = projectId,
            chapterCount = chapterUploads.size,
            pageCount = chapterUploads.sumOf { it.pages.size },
            commitSha = commitSha,
        )
    }

    private fun uploadChapterPages(
        downloaded: UniFile,
        chapterPath: String,
        token: String,
    ): List<PageUpload> {
        return if (downloaded.isDirectory) {
            val images = downloaded.listFiles()
                .orEmpty()
                .filter { file ->
                    !file.isDirectory && ImageUtil.isImage(file.name) { file.openInputStream() }
                }
                .sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }

            images.mapIndexed { index, file ->
                val bytes = file.openInputStream().use { it.readBytes() }
                uploadPage(bytes, file.name.orEmpty(), chapterPath, index + 1, token)
            }
        } else if (downloaded.name.orEmpty().endsWith(".cbz", ignoreCase = true)) {
            uploadCbzPages(downloaded, chapterPath, token)
        } else {
            throw IOException("Unsupported downloaded chapter format: ${downloaded.name}")
        }
    }

    private fun uploadCbzPages(
        cbz: UniFile,
        chapterPath: String,
        token: String,
    ): List<PageUpload> {
        val imageNames = mutableListOf<String>()
        ZipInputStream(cbz.openInputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && isImageName(entry.name)) {
                    imageNames += entry.name
                }
                zip.closeEntry()
            }
        }
        val orderedNames = imageNames.sortedBy { it.lowercase(Locale.ROOT) }
        val order = orderedNames.withIndex().associate { it.value to it.index + 1 }
        val result = mutableListOf<PageUpload>()

        ZipInputStream(cbz.openInputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val pageIndex = order[entry.name]
                if (!entry.isDirectory && pageIndex != null) {
                    val bytes = zip.readBytes()
                    result += uploadPage(bytes, entry.name, chapterPath, pageIndex, token)
                }
                zip.closeEntry()
            }
        }
        return result.sortedBy { it.index }
    }

    private fun uploadPage(
        bytes: ByteArray,
        originalName: String,
        chapterPath: String,
        index: Int,
        token: String,
    ): PageUpload {
        val extension = originalName.substringAfterLast('.', "jpg")
            .lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }
            ?: "jpg"
        val path = "$chapterPath/${"%04d".format(Locale.ENGLISH, index)}.$extension"
        val blobSha = api(
            "git/blobs",
            token,
            method = "POST",
            body = JSONObject()
                .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("encoding", "base64"),
        ).getString("sha")
        return PageUpload(
            index = index,
            path = path,
            sha256 = sha256(bytes),
            blobSha = blobSha,
        )
    }

    private fun buildSourceManifest(
        projectId: String,
        sourceUrl: String,
        manga: Manga,
        source: Source,
        chapters: List<ChapterUpload>,
    ): JSONObject {
        val chapterJson = JSONArray()
        chapters.forEach { uploaded ->
            val pages = JSONArray()
            uploaded.pages.sortedBy { it.index }.forEach { page ->
                pages.put(
                    JSONObject()
                        .put("index", page.index)
                        .put("asset_ref", rawUrl(page.path))
                        .put("sha256", page.sha256),
                )
            }
            chapterJson.put(
                JSONObject()
                    .put("chapter_id", uploaded.chapterId)
                    .put("title", uploaded.chapter.name)
                    .put("source_url", uploaded.chapter.url)
                    .put("chapter_number", uploaded.chapter.chapterNumber)
                    .put("pages", pages),
            )
        }

        return JSONObject()
            .put("project_id", projectId)
            .put(
                "source",
                JSONObject()
                    .put("kind", "url")
                    .put("value", sourceUrl)
                    .put("acquired_by", "kotori")
                    .put("source_id", source.id)
                    .put("source_name", source.name),
            )
            .put(
                "series",
                JSONObject()
                    .put("title", manga.title)
                    .put("author", manga.author ?: JSONObject.NULL)
                    .put("artist", manga.artist ?: JSONObject.NULL)
                    .put("description", manga.description ?: JSONObject.NULL)
                    .put("thumbnail_url", manga.thumbnailUrl ?: JSONObject.NULL),
            )
            .put("chapters", chapterJson)
    }

    private fun resolveSourceUrl(manga: Manga, source: Source): String {
        return if (source is HttpSource) {
            runCatching { source.getMangaUrl(manga.toSManga()) }
                .getOrNull()
                .orEmpty()
        } else {
            "kotori://source/${source.id}/manga/${manga.id}"
        }
    }

    private fun chapterId(chapter: Chapter): String {
        val seed = chapter.url.ifBlank { "${chapter.id}:${chapter.name}" }
        return "ch-${sha256(seed.toByteArray()).take(12)}"
    }

    private fun slugifySource(source: String): String {
        val seed = runCatching {
            val uri = URI(source)
            if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
                "${uri.host}${uri.path.orEmpty()}".trim('/')
            } else {
                source
            }
        }.getOrDefault(source)
        val slug = seed
            .replace(Regex("[^a-zA-Z0-9]+"), "-")
            .trim('-')
            .lowercase(Locale.ROOT)
        return (slug.takeLast(64).ifBlank { "series" })
    }

    private fun rawUrl(path: String): String =
        "https://raw.githubusercontent.com/$TARGET_REPOSITORY/$TARGET_BRANCH/$path"

    private fun textTreeEntry(path: String, json: JSONObject, token: String): JSONObject {
        val blobSha = api(
            "git/blobs",
            token,
            method = "POST",
            body = JSONObject()
                .put("content", json.toString(2) + "\n")
                .put("encoding", "utf-8"),
        ).getString("sha")
        return treeEntry(path, blobSha)
    }

    private fun treeEntry(path: String, blobSha: String): JSONObject =
        JSONObject()
            .put("path", path)
            .put("mode", "100644")
            .put("type", "blob")
            .put("sha", blobSha)

    private fun api(
        path: String,
        token: String,
        method: String = "GET",
        body: JSONObject? = null,
    ): JSONObject {
        val requestBuilder = Request.Builder()
            .url("$GITHUB_API/repos/$TARGET_REPOSITORY/$path")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Kotori-Manga-TL-Factory")

        if (method != "GET") {
            val requestBody = (body ?: JSONObject()).toString().toRequestBody(JSON_MEDIA_TYPE)
            when (method) {
                "POST" -> requestBuilder.post(requestBody)
                "PATCH" -> requestBuilder.patch(requestBody)
                else -> error("Unsupported HTTP method: $method")
            }
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val responseText = response.body.string()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(responseText).optString("message") }
                    .getOrDefault(responseText.take(200))
                throw IOException("GitHub ${response.code}: $message")
            }
            return JSONObject(responseText)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun isImageName(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in IMAGE_EXTENSIONS
    }

    companion object {
        private const val TARGET_REPOSITORY = "tailolicon/manga-tl-factory"
        private const val TARGET_BRANCH = "main"
        private const val GITHUB_API = "https://api.github.com"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "jxl", "bmp")
    }
}
