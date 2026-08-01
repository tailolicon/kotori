package mihon.feature.translation.model

import android.graphics.Rect

/** A speech-bubble candidate produced by the on-device detector, in source-image pixel space. */
data class BubbleBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
    /**
     * Found by reading the page rather than by the bubble model — a status window, caption or other
     * block of lettering that has no bubble around it.
     *
     * The renderer treats these differently. A bubble has a flat interior it can flood and repaint;
     * a status panel is a translucent gradient over artwork, so flooding fails and the glyph mask
     * cannot separate pale blue lettering from the pale blue behind it. What is reliable for these
     * is the OCR line geometry, which says exactly where the lettering sits.
     */
    val isTextBlock: Boolean = false,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun toRect(): Rect = Rect(left, top, right, bottom)

    fun clampTo(imageWidth: Int, imageHeight: Int): BubbleBox = copy(
        left = left.coerceIn(0, imageWidth),
        top = top.coerceIn(0, imageHeight),
        right = right.coerceIn(0, imageWidth),
        bottom = bottom.coerceIn(0, imageHeight),
    )

    fun isUsable(): Boolean = width > 8 && height > 8
}

/**
 * Glyph geometry for one recognised text line, in source-image pixel space.
 *
 * The renderer needs this — not just the recognised string — because masking the *glyphs* rather
 * than the whole bubble is what keeps bubble outlines and artwork intact.
 */
data class TextLineBox(
    val rect: Rect,
    val text: String,
)

/** OCR result for a single bubble. */
data class BubbleText(
    val text: String,
    val lines: List<TextLineBox>,
)

/** Everything the renderer needs to replace one bubble's dialogue. */
data class TranslatedBubble(
    val box: BubbleBox,
    val original: String,
    val translated: String,
    val lines: List<TextLineBox>,
)

/** Colour and polarity characteristics sampled from a bubble's interior. */
data class BubbleStyle(
    /** Representative background colour, used only as a last-resort fill. */
    val backgroundColor: Int,
    /** Colour sampled from the original glyph strokes; the replacement text reuses it. */
    val textColor: Int,
    /** True when glyphs are lighter than their background (white-on-black dialogue). */
    val lightTextOnDark: Boolean,
)
