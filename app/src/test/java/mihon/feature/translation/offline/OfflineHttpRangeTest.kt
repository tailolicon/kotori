package mihon.feature.translation.offline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OfflineHttpRangeTest {

    @Test
    fun `parses content-range with total`() {
        val r = OfflineHttpRange.parseContentRange("bytes 100-199/1133080512")
        assertEquals(100L, r!!.start)
        assertEquals(199L, r.end)
        assertEquals(1_133_080_512L, r.total)
    }

    @Test
    fun `parses content-range with star total`() {
        val r = OfflineHttpRange.parseContentRange("bytes 0-99/*")
        assertEquals(0L, r!!.start)
        assertNull(r.total)
    }

    @Test
    fun `malformed content-range is null`() {
        assertNull(OfflineHttpRange.parseContentRange("invalid"))
        assertNull(OfflineHttpRange.parseContentRange(null))
    }

    @Test
    fun `http 200 always writes from start`() {
        val plan = OfflineHttpRange.plan(existing = 500L, httpCode = 200, contentRangeHeader = null)
        assertEquals(OfflineHttpRange.ResumePlan.WriteFromStart, plan)
    }

    @Test
    fun `http 206 with matching offset appends`() {
        val plan = OfflineHttpRange.plan(
            existing = 100L,
            httpCode = 206,
            contentRangeHeader = "bytes 100-200/1000",
        )
        assertTrue(plan is OfflineHttpRange.ResumePlan.Append)
        plan as OfflineHttpRange.ResumePlan.Append
        assertEquals(100L, plan.fileOffset)
        assertEquals(1000L, plan.total)
    }

    @Test
    fun `http 206 with wrong offset retries full get without writing`() {
        val plan = OfflineHttpRange.plan(
            existing = 100L,
            httpCode = 206,
            contentRangeHeader = "bytes 0-50/1000",
        )
        assertEquals(OfflineHttpRange.ResumePlan.RetryFullGet, plan)
    }

    @Test
    fun `http 206 with missing content-range retries full get`() {
        val plan = OfflineHttpRange.plan(
            existing = 100L,
            httpCode = 206,
            contentRangeHeader = null,
        )
        assertEquals(OfflineHttpRange.ResumePlan.RetryFullGet, plan)
    }
}
