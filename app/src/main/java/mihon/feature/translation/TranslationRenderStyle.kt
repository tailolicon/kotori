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
 */
enum class TranslationRenderStyle {
    SIMPLE,
    BUBBLE,
    TYPESET,
}
