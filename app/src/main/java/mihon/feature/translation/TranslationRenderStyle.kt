package mihon.feature.translation

/**
 * How translated dialogue is painted back onto the page.
 *
 * [SIMPLE] erases the recognised glyphs and writes into that footprint — safe on artwork, tight on
 * vertical Japanese. [BUBBLE] is the older balloon-aware path (fills, recentring, a dozen guards).
 * [TYPESET] is the letterer's path used by scanlation tools: recover the balloon interior, paint it
 * flat, and set the translation in the whole balloon. That is what vertical Japanese needs, and it
 * is also the right answer for any page whose balloons the detector can see or whose paper the
 * flood can recover.
 *
 * [AUTO] is the default and picks between the first two from the page itself. Which mode suits a
 * page is a property of the page, not of the reader: [TYPESET] is what vertical Japanese needs and
 * what wrecks a colour webtoon, where it floods dark panels into blocks and buries the lettering.
 * That was not hypothetical — an install that had been pinned to [TYPESET] while manga was being
 * worked on then rendered every webtoon that way, and the reader saw a chapter of dark slabs.
 */
enum class TranslationRenderStyle {
    AUTO,
    SIMPLE,
    BUBBLE,
    TYPESET,
}
