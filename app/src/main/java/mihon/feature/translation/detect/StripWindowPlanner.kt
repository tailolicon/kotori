package mihon.feature.translation.detect

/** Plans overlapping detector windows while always aligning one window with the strip bottom. */
internal object StripWindowPlanner {

    fun starts(imageHeight: Int, windowHeight: Int, overlap: Int): List<Int> {
        require(imageHeight > 0)
        require(windowHeight > 0)
        require(overlap in 0 until windowHeight)
        if (imageHeight <= windowHeight) return listOf(0)

        val lastStart = imageHeight - windowHeight
        val stride = windowHeight - overlap
        val starts = mutableListOf(0)
        var next = stride
        while (next < lastStart) {
            starts += next
            next += stride
        }
        if (starts.last() != lastStart) starts += lastStart
        return starts
    }
}
