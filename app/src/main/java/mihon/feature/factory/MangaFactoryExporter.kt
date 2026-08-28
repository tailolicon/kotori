package mihon.feature.factory

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

class MangaFactoryExporter {
    data class ExportResult(
        val projectId: String,
        val chapterCount: Int,
        val pageCount: Int,
        val handoffPath: String,
        val commitSha: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .build()

    suspend fun export(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        token: String,
    ): ExportResult {
        require(token.isNotBlank()) { "GitHub token is empty" }
        require(source is HttpSource) { "Only HTTP manga sources can create a source handoff" }

        val sourceUrl = resolveSourceUrl(manga, source)
        require(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://")) {
            "Manga source did not resolve to an HTTP URL"
        }
        val chapterUrl = source.getChapterUrl(chapter.toSChapter())
        val projectId = slugifySeriesTitle(manga.title)
        val sourceKey = slugifySource(sourceUrl)
        val importId = "kotori-${manga.id}-${System.currentTimeMillis().toString(36)}"
        val handoffPath = "work/imports/$projectId/$importId/source_handoff.json"

        // Resolve through the installed extension, but never request or read the image body.
        val pages = source.getPageList(chapter.toSChapter())
        require(pages.isNotEmpty()) { "Source returned no pages for ${chapter.name}" }
        val safeHeaders = safeImageHeaders(source, chapterUrl)
        val pageJson = JSONArray()
        pages.forEachIndexed { index, page ->
            val imageUrl = page.imageUrl
                ?.takeIf { it.isNotBlank() }
                ?: source.getImageUrl(page)
            require(imageUrl.startsWith("https://") || imageUrl.startsWith("http://")) {
                "Page ${index + 1} did not resolve to an HTTP URL"
            }
            pageJson.put(
                JSONObject()
                    .put("index", index + 1)
                    .put("url", imageUrl)
                    .put("headers", JSONObject(safeHeaders)),
            )
        }
        logcat { "Manga Factory handoff resolved: source=${source.id}, pages=${pages.size}" }

        val handoff = JSONObject()
            .put("schema", 1)
            .put("provider", "kotori")
            .put("created_at", Instant.now().toString())
            .put("project_id", projectId)
            .put("source_key", sourceKey)
            .put(
                "source",
                JSONObject()
                    .put("source_id", source.id)
                    .put("source_name", source.name)
                    .put("manga_url", sourceUrl),
            )
            .put(
                "series",
                JSONObject()
                    .put("title", manga.title)
                    .put("author", manga.author ?: JSONObject.NULL)
                    .put("artist", manga.artist ?: JSONObject.NULL)
                    .put("thumbnail_url", manga.thumbnailUrl ?: JSONObject.NULL),
            )
            .put(
                "chapters",
                JSONArray().put(
                    JSONObject()
                        .put("id", chapterId(chapter))
                        .put("name", chapter.name)
                        .put("source_url", chapterUrl)
                        .put("chapter_number", chapter.chapterNumber)
                        .put("pages_resolved", true)
                        .put("pages", pageJson),
                ),
            )

        val headSha = api("git/ref/heads/$TARGET_BRANCH", token)
            .getJSONObject("object")
            .getString("sha")
        val baseTreeSha = api("git/commits/$headSha", token)
            .getJSONObject("tree")
            .getString("sha")
        val handoffBlobSha = createTextBlob(handoff, token)
        val treeSha = api(
            "git/trees",
            token,
            method = "POST",
            body = JSONObject()
                .put("base_tree", baseTreeSha)
                .put("tree", JSONArray().put(treeEntry(handoffPath, handoffBlobSha))),
        ).getString("sha")
        val commitSha = api(
            "git/commits",
            token,
            method = "POST",
            body = JSONObject()
                .put("message", "handoff(kotori): ${manga.title} · ${chapter.name}")
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
            chapterCount = 1,
            pageCount = pages.size,
            handoffPath = handoffPath,
            commitSha = commitSha,
        )
    }

    private fun safeImageHeaders(source: HttpSource, chapterUrl: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        SAFE_HEADER_NAMES.forEach { name ->
            source.headers[name]
                ?.takeIf { it.isNotBlank() }
                ?.let { result[name] = it }
        }
        result.putIfAbsent("Referer", chapterUrl)
        result.putIfAbsent("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        return result
    }

    private fun resolveSourceUrl(manga: Manga, source: HttpSource): String =
        runCatching { source.getMangaUrl(manga.toSManga()) }
            .getOrNull()
            .orEmpty()

    private fun chapterId(chapter: Chapter): String {
        val seed = chapter.url.ifBlank { "${chapter.id}:${chapter.name}" }
        return "ch-${sha256(seed.toByteArray()).take(12)}"
    }

    private fun slugifySeriesTitle(title: String): String =
        title
            .replace(Regex("[^a-zA-Z0-9]+"), "-")
            .trim('-')
            .lowercase(Locale.ROOT)
            .take(64)
            .ifBlank { "series" }

    private fun slugifySource(source: String): String {
        val seed = runCatching {
            val uri = URI(source)
            if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
                "${uri.host}${uri.path.orEmpty()}".trim('/')
            } else {
                source
            }
        }.getOrDefault(source)
        return seed
            .replace(Regex("[^a-zA-Z0-9]+"), "-")
            .trim('-')
            .lowercase(Locale.ROOT)
            .takeLast(64)
            .ifBlank { "source" }
    }

    private fun createTextBlob(json: JSONObject, token: String): String =
        api(
            "git/blobs",
            token,
            method = "POST",
            body = JSONObject()
                .put("content", json.toString(2) + "\n")
                .put("encoding", "utf-8"),
        ).getString("sha")

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

    companion object {
        private const val TARGET_REPOSITORY = "tailolicon/manga-tl-factory"
        private const val TARGET_BRANCH = "main"
        private const val GITHUB_API = "https://api.github.com"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SAFE_HEADER_NAMES = listOf("User-Agent", "Accept", "Referer", "Origin")
    }
}
