package mihon.feature.translation

/**
 * Decides whether a short OCR reading is enough to convict a provider's echo of being someone
 * else's.
 *
 * A vision model is asked to echo the text it read from each numbered bubble, and that echo is what
 * places its translation. When the echo disagrees with what the recogniser read, the translation is
 * in the wrong bubble. Short readings were exempted from that test, because a lone misread glyph
 * must not get a real translation dropped on its account — and that exemption is how a panel
 * captioned "REC" came out saying "sướng quá đi": the model echoed "HAAAH, THAT WAS GOOD, THAT WAS
 * GOOD." for it, thirty characters that share nothing with the three that were read, and nothing
 * looked because three is fewer than four.
 *
 * Short OCR is weak evidence, not absent evidence. It convicts when the echo is far longer *and*
 * does not contain it.
 */
internal object ShortOcrEchoGuard {

    /**
     * @param ocr what the recogniser read, normalised
     * @param echo what the provider says it read, normalised
     */
    fun contradicts(ocr: String, echo: String): Boolean {
        if (ocr.isBlank() || echo.isBlank()) return false
        return echo.length >= ocr.length * ECHO_RATIO &&
            echo.length >= MIN_ECHO_LENGTH &&
            !echo.contains(ocr)
    }

    /** Keeps a two-letter misread from indicting a five-letter echo of the same sound. */
    private const val ECHO_RATIO = 3

    /** Keeps the rule away from short lettering, where a recogniser and a model honestly differ. */
    private const val MIN_ECHO_LENGTH = 8
}
