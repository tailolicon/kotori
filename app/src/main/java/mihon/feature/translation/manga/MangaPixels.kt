package mihon.feature.translation.manga

internal object MangaPixels {
    fun red(color: Int): Int = color shr 16 and 0xFF
    fun green(color: Int): Int = color shr 8 and 0xFF
    fun blue(color: Int): Int = color and 0xFF
    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun gray(color: Int): Int =
        (red(color) * 299 + green(color) * 587 + blue(color) * 114) / 1000
}
