package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PageBatchPolicyTest {

    @Test
    fun `pager manga is translated one native-resolution page at a time`() {
        assertEquals(1, PageBatchPolicy.maxPages(joinContinuousPages = false, stripLimit = 8))
    }

    @Test
    fun `continuous webtoon keeps strip batching`() {
        assertEquals(8, PageBatchPolicy.maxPages(joinContinuousPages = true, stripLimit = 8))
    }
}
