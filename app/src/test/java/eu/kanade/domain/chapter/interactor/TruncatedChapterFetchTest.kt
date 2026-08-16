package eu.kanade.domain.chapter.interactor

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TruncatedChapterFetchTest {

    @Test
    fun `automatic fetch that shrank the list is treated as truncated even if one chapter is new`() {
        // 100 known, source returned 31 (page 1 plus a newly published chapter), 70 missing.
        assertTrue(
            TruncatedChapterFetch.shouldKeepExisting(
                dbCount = 100,
                sourceCount = 31,
                removedCount = 70,
                manualFetch = false,
            ),
        )
    }

    @Test
    fun `scanlator switch that replaces the list is trusted`() {
        assertFalse(
            TruncatedChapterFetch.shouldKeepExisting(
                dbCount = 100,
                sourceCount = 100,
                removedCount = 100,
                manualFetch = false,
            ),
        )
    }

    @Test
    fun `manual refresh keeps authority`() {
        assertFalse(
            TruncatedChapterFetch.shouldKeepExisting(
                dbCount = 100,
                sourceCount = 30,
                removedCount = 70,
                manualFetch = true,
            ),
        )
    }

    @Test
    fun `a handful of genuine removals still go through`() {
        assertFalse(
            TruncatedChapterFetch.shouldKeepExisting(
                dbCount = 100,
                sourceCount = 97,
                removedCount = 3,
                manualFetch = false,
            ),
        )
    }
}
