package eu.kanade.tachiyomi.data.backup.restore.restorers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.items.episode.model.Episode

class SyncProgressMergeTest {

    @Test
    fun `older cloud chapter cannot erase local reading progress`() {
        val remote = Chapter.create().copy(id = 9, lastPageRead = 10, version = 2)
        val local = Chapter.create().copy(id = 42, lastPageRead = 63, version = 5, bookmark = true)

        val merged = mergeChapterProgress(remote, local)

        assertEquals(42, merged.id)
        assertEquals(63, merged.lastPageRead)
        assertEquals(5, merged.version)
        assertTrue(merged.bookmark)
    }

    @Test
    fun `newer cloud chapter is applied immediately on another device`() {
        val remote = Chapter.create().copy(lastPageRead = 81, version = 7, read = true)
        val local = Chapter.create().copy(id = 42, lastPageRead = 20, version = 3)

        val merged = mergeChapterProgress(remote, local)

        assertEquals(81, merged.lastPageRead)
        assertEquals(7, merged.version)
        assertTrue(merged.read)
    }

    @Test
    fun `older cloud episode cannot erase local playback position`() {
        val remote = Episode.create().copy(lastSecondSeen = 90, totalSeconds = 900, version = 2)
        val local = Episode.create().copy(
            id = 21,
            lastSecondSeen = 420,
            totalSeconds = 1_200,
            version = 6,
            bookmark = true,
        )

        val merged = mergeEpisodeProgress(remote, local)

        assertEquals(21, merged.id)
        assertEquals(420, merged.lastSecondSeen)
        assertEquals(1_200, merged.totalSeconds)
        assertEquals(6, merged.version)
        assertTrue(merged.bookmark)
    }
}
