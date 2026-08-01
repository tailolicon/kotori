package mihon.data.airing

import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mihon.domain.airing.model.AiringRelease
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * The season broadcast schedule, read from AniList's public GraphQL endpoint.
 *
 * This is the same data the site's own airing calendar shows, and it needs no account: the
 * query is unauthenticated, so the calendar works whether or not the user has linked a
 * tracker. Rate limited well under AniList's published ceiling.
 */
class AniListAiringApi(client: OkHttpClient) {

    private val json: Json by injectLazy()

    private val apiClient = client.newBuilder()
        .rateLimit(permits = 85, period = 1.minutes)
        .build()

    /**
     * Every episode airing between [from] and [to], oldest first.
     *
     * A week of a busy season runs to a few hundred entries, so pages are followed until
     * AniList says there are no more rather than trusting one page to cover it.
     */
    suspend fun schedule(from: Instant, to: Instant): List<AiringRelease> = withIOContext {
        val releases = mutableListOf<AiringRelease>()
        var page = 1
        while (page <= MAX_PAGES) {
            val result = requestPage(from.epochSecond, to.epochSecond, page) ?: break
            result.data.page.airingSchedules
                .filterNot { it.media?.isAdult == true }
                .mapNotNullTo(releases) { it.toRelease() }
            if (!result.data.page.pageInfo.hasNextPage) break
            page++
        }
        releases.sortedBy { it.airingAt }
    }

    private suspend fun requestPage(from: Long, to: Long, page: Int): ALScheduleResult? {
        val payload = buildJsonObject {
            put("query", QUERY)
            putJsonObject("variables") {
                put("start", from)
                put("end", to)
                put("page", page)
            }
        }
        return runCatching {
            with(json) {
                apiClient.newCall(POST(API_URL, body = payload.toString().toRequestBody(jsonMime)))
                    .awaitSuccess()
                    .parseAs<ALScheduleResult>()
            }
        }.getOrNull()
    }

    private fun ALAiringSchedule.toRelease(): AiringRelease? {
        val media = media ?: return null
        val title = media.title?.let { it.userPreferred ?: it.romaji ?: it.english ?: it.native }
            ?: return null
        return AiringRelease(
            remoteId = media.id,
            title = title,
            coverUrl = media.coverImage?.large,
            episode = episode,
            airingAt = Instant.ofEpochSecond(airingAt),
        )
    }

    companion object {
        private const val API_URL = "https://graphql.anilist.co/"

        /** A week of a busy season is a few hundred entries; this is headroom, not a target. */
        private const val MAX_PAGES = 10

        private val QUERY = """
            |query (${'$'}start: Int, ${'$'}end: Int, ${'$'}page: Int) {
                |Page(page: ${'$'}page, perPage: 50) {
                    |pageInfo { hasNextPage }
                    |airingSchedules(airingAt_greater: ${'$'}start, airingAt_lesser: ${'$'}end, sort: TIME) {
                        |episode
                        |airingAt
                        |media {
                            |id
                            |isAdult
                            |title { userPreferred romaji english native }
                            |coverImage { large }
                        |}
                    |}
                |}
            |}
        """.trimMargin()
    }
}

@Serializable
private data class ALScheduleResult(val data: ALScheduleData)

@Serializable
private data class ALScheduleData(@kotlinx.serialization.SerialName("Page") val page: ALSchedulePage)

@Serializable
private data class ALSchedulePage(
    val pageInfo: ALPageInfo,
    val airingSchedules: List<ALAiringSchedule>,
)

@Serializable
private data class ALPageInfo(val hasNextPage: Boolean = false)

@Serializable
private data class ALAiringSchedule(
    val episode: Int,
    val airingAt: Long,
    val media: ALScheduleMedia? = null,
)

@Serializable
private data class ALScheduleMedia(
    val id: Long,
    val isAdult: Boolean = false,
    val title: ALScheduleTitle? = null,
    val coverImage: ALScheduleCover? = null,
)

@Serializable
private data class ALScheduleTitle(
    val userPreferred: String? = null,
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
private data class ALScheduleCover(val large: String? = null)
