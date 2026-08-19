package mihon.feature.translation.manga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Color as AndroidColor

/** Port of Manga-Translator `add_text.add_text`. */
internal object MangaLetterer {

    fun addText(
        image: Bitmap,
        text: String,
        typeface: Typeface,
        contourLeft: Int,
        contourTop: Int,
        contourWidth: Int,
        contourHeight: Int,
        lightText: Boolean,
    ): Bitmap {
        if (text.isBlank()) return image
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            color = if (lightText) AndroidColor.WHITE else AndroidColor.BLACK
            textAlign = Paint.Align.LEFT
        }
        val (fontSize, lineHeight, wrapped) = MangaTextWrap.optimalSize(
            text,
            contourWidth,
            contourHeight,
        ) { line, size ->
            paint.textSize = size.toFloat()
            paint.measureText(line)
        }
        paint.textSize = fontSize.toFloat()
        val canvas = Canvas(image)
        val lines = wrapped.split('\n')
        val totalHeight = lines.size * lineHeight
        var textY = contourTop + (contourHeight - totalHeight) / 2
        val fontMetrics = paint.fontMetrics
        for (line in lines) {
            val textLength = paint.measureText(line)
            val textX = contourLeft + (contourWidth - textLength) / 2f
            val baseline = textY - fontMetrics.ascent
            canvas.drawText(line, textX, baseline, paint)
            textY += lineHeight
        }
        return image
    }
}
