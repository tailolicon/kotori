package mihon.feature.translation.manga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Color as AndroidColor

/** Port of Manga-Translator `add_text.add_text`. */
internal object MangaLetterer {

    /**
     * Sets [text] inside the balloon contour.
     *
     * @param minFont / @param maxFont legible bounds for the page this balloon came off — see
     *   [MangaTextWrap.boundsFor]
     * @return false when the translation could not be set at a legible size, so the caller can
     *   leave the original artwork alone instead of stamping an unreadable smudge on it
     */
    fun addText(
        image: Bitmap,
        text: String,
        typeface: Typeface,
        contourLeft: Int,
        contourTop: Int,
        contourWidth: Int,
        contourHeight: Int,
        lightText: Boolean,
        minFont: Int = MangaTextWrap.MIN_FONT_SIZE,
        maxFont: Int = MangaTextWrap.MAX_FONT_SIZE,
    ): Boolean {
        if (text.isBlank()) return false
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            color = if (lightText) AndroidColor.WHITE else AndroidColor.BLACK
            textAlign = Paint.Align.LEFT
        }
        val fit = MangaTextWrap.optimalSize(
            text,
            contourWidth,
            contourHeight,
            minFont,
            maxFont,
        ) { line, size ->
            paint.textSize = size.toFloat()
            paint.measureText(line)
        }
        if (!fit.fits) return false
        paint.textSize = fit.size.toFloat()
        val canvas = Canvas(image)
        val lines = fit.wrapped.split('\n')
        val totalHeight = lines.size * fit.lineHeight
        var textY = contourTop + (contourHeight - totalHeight) / 2
        val fontMetrics = paint.fontMetrics
        for (line in lines) {
            val textLength = paint.measureText(line)
            val textX = contourLeft + (contourWidth - textLength) / 2f
            val baseline = textY - fontMetrics.ascent
            canvas.drawText(line, textX, baseline, paint)
            textY += fit.lineHeight
        }
        return true
    }
}
