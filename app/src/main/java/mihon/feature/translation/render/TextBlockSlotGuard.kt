package mihon.feature.translation.render

/** Chooses the full cleared field for shallow rectangular captions instead of a glyph-shaped gap. */
internal object TextBlockSlotGuard {

    fun useWholeFlatBounds(isTextBlock: Boolean, width: Int, height: Int): Boolean =
        isTextBlock && width > 0 && height > 0 && width >= height * MIN_ASPECT

    private const val MIN_ASPECT = 2
}
