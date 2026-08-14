package mihon.feature.translation

/** Keeps full manga pages sharp while retaining strip batching for continuous webtoon readers. */
internal object PageBatchPolicy {
    fun maxPages(joinContinuousPages: Boolean, stripLimit: Int): Int =
        if (joinContinuousPages) stripLimit.coerceAtLeast(1) else 1
}
