package tachiyomi.data.history.anime

import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.domain.history.anime.model.AnimeHistory
import tachiyomi.domain.history.anime.model.AnimeHistoryWithRelations
import java.util.Date

object AnimeHistoryMapper {
    fun mapAnimeHistory(
        id: Long,
        episodeId: Long,
        seenAt: Date?,
    ): AnimeHistory = AnimeHistory(
        id = id,
        episodeId = episodeId,
        seenAt = seenAt,
    )

    fun mapAnimeHistoryWithRelations(
        historyId: Long,
        animeId: Long,
        episodeId: Long,
        title: String,
        episodeName: String,
        thumbnailUrl: String?,
        sourceId: Long,
        isFavorite: Boolean,
        coverLastModified: Long,
        episodeNumber: Double,
        lastSecondSeen: Long,
        totalSeconds: Long,
        seenAt: Date?,
    ): AnimeHistoryWithRelations = AnimeHistoryWithRelations(
        id = historyId,
        episodeId = episodeId,
        animeId = animeId,
        title = title,
        episodeName = episodeName,
        episodeNumber = episodeNumber,
        lastSecondSeen = lastSecondSeen,
        totalSeconds = totalSeconds,
        seenAt = seenAt,
        coverData = AnimeCover(
            animeId = animeId,
            sourceId = sourceId,
            isAnimeFavorite = isFavorite,
            url = thumbnailUrl,
            lastModified = coverLastModified,
        ),
    )
}
