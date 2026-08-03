package mihon.feature.translation.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import mihon.feature.translation.model.TextLineBox
import mihon.feature.translation.model.TranslatedBubble
import tachiyomi.core.common.util.system.logcat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Draws translated dialogue onto a page.
 *
 * Per bubble the sequence is: find the glyph pixels, sample the ink colour from them, reconstruct the
 * background underneath, then find a safe rectangle and lay the new text into it. Nothing outside the
 * glyph mask is ever painted over, so bubble outlines, tails, screentones and colour artwork survive
 * untouched — which is what separates this from stamping a filled box over the bubble.
 */
class BubbleRenderer(private val context: Context) {

    private val bundledTypeface: Typeface by lazy {
        runCatching { Typeface.createFromAsset(context.assets, "translation/HL-Comic.ttf") }
            .getOrDefault(Typeface.DEFAULT_BOLD)
    }

    fun resolveTypeface(name: String): Typeface = when (name) {
        "HL-Comic" -> bundledTypeface
        "serif" -> Typeface.SERIF
        "sans" -> Typeface.SANS_SERIF
        "monospace" -> Typeface.MONOSPACE
        else -> runCatching { Typeface.createFromAsset(context.assets, "translation/$name.ttf") }
            .getOrDefault(bundledTypeface)
    }

    /** Returns a new bitmap; [source] is left untouched so the untranslated page stays cacheable. */
    fun render(source: Bitmap, bubbles: List<TranslatedBubble>, fontName: String): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        if (bubbles.isEmpty()) return output

        val typeface = resolveTypeface(fontName)
        val canvas = Canvas(output)

        // Every bubble is erased before any text is drawn.
        //
        // Doing both in one pass per bubble looks equivalent and is not: bubbles overlap, so erasing
        // the second one repaints the area the first one's translation was just written into. On the
        // page that exposed this, a two-line caption came out with its whole second line scrubbed away
        // — the letters had been drawn, then painted over by a neighbour a few milliseconds later.
        val plans = ArrayList<Plan>(bubbles.size)
        for (bubble in bubbles) {
            if (bubble.translated.isBlank()) continue
            runCatching { eraseBubble(output, bubble)?.let { plans += it } }
                .onFailure { logcat { "Failed to erase bubble ${bubble.box}: ${it.message}" } }
        }

        // One line of dialogue belongs in one place on the page. When a bubble is detected twice and
        // the two boxes do not overlap — one on the bubble, one on the artwork beside it — both get
        // lettered, and the reader sees the sentence again in miniature over the picture. Overlap
        // cannot catch that, so the same text anywhere on the page is resolved here, before drawing,
        // by keeping whichever candidate has the most room. Short lines are exempt: "Hả?!" really
        // can appear twice on a page.
        val ordered = plans.sortedByDescending { it.slot.width().toLong() * it.slot.height() }
        val kept = ArrayList<Plan>(ordered.size)
        for (plan in ordered) {
            val repeat = wordCount(plan.text) >= MIN_WORDS_FOR_PAGE_DEDUPE &&
                kept.any { looksLikeSameLine(it.text, plan.text) }
            if (repeat) {
                logcat { "Skipping ${plan.slot}: this line is already lettered elsewhere on the page" }
                continue
            }
            kept += plan
        }

        val painted = mutableListOf<Rect>()
        val written = mutableListOf<Pair<Rect, String>>()
        for (plan in kept) {
            // The detector sometimes returns two boxes for one bubble that overlap too little for
            // non-maximum suppression to merge them. Both are read, both come back with the same line,
            // and both get lettered — the bubble ends up with its translation printed twice, the second
            // copy shrunk into whatever space the first left over. Same words in the same place is one
            // bubble, however many times it was detected.
            // Equality is too strict a test. Two detections over one bubble are read separately, so
            // the provider paraphrases them differently ("...even if it's for 1 second" vs "...even
            // if it is for1 second") and both get lettered — the bubble ends up with two near-
            // identical paragraphs stacked on top of each other. Near-identical text in the same
            // place is one bubble, however many times it was detected and however the wording drifted.
            val duplicate = written.any { (rect, text) ->
                Rect.intersects(rect, plan.slot) && looksLikeSameLine(text, plan.text)
            }
            if (duplicate) {
                logcat { "Skipping ${plan.slot}: same text already lettered in an overlapping bubble" }
                continue
            }

            // One bubble detected twice is read twice, and the provider splits the sentence: the
            // first box gets "Although I could use the system's channel," the second gets "but at
            // this level there is no point wasting resources." Both are lettered, at different
            // sizes, one above the other inside the same bubble. They are not duplicates by text —
            // they are genuinely different halves — so only the geometry gives it away. A slot that
            // sits almost entirely inside one already lettered is the same bubble again.
            val nested = painted.any { containment(plan.slot, it) >= NESTED_SLOT_FRACTION }
            if (nested) {
                logcat { "Skipping ${plan.slot}: nested inside a bubble already lettered" }
                continue
            }

            val free = clearOf(plan.slot, painted)
            if (free == null) {
                logcat { "Skipping ${plan.slot}: its text slot is already occupied" }
                continue
            }
            // A slot this narrow is a cropped bubble remnant or a detector artefact. Text laid into
            // it can only render as a column of clipped glyphs, which reads as corruption.
            if (free.width() < MIN_DRAW_WIDTH) {
                logcat { "Skipping ${plan.slot}: ${free.width()}px is too narrow to letter" }
                continue
            }
            runCatching {
                canvas.save()
                canvas.clipRect(free.left, free.top, free.right, free.bottom)
                val lettered = drawText(
                    canvas = canvas,
                    text = plan.text,
                    left = free.left,
                    top = free.top,
                    width = free.width(),
                    height = free.height(),
                    inkColor = plan.inkColor,
                    paperColor = plan.paperColor,
                    typeface = typeface,
                    alignLeft = plan.alignLeft,
                )
                if (!lettered) logcat { "Skipping ${plan.slot}: text cannot fit even at minimum size" }
                canvas.restore()
            }.onFailure { logcat { "Failed to letter ${plan.slot}: ${it.message}" } }
            painted += free
            written += plan.slot to plan.text
        }
        return output
    }

    /**
     * True when two strings are the same line of dialogue reworded, rather than two different lines.
     *
     * Word-set overlap: robust to the provider translating one detection of a bubble slightly
     * differently from another, and to trailing clauses one crop caught and the other clipped.
     */
    private fun looksLikeSameLine(a: String, b: String): Boolean {
        if (a == b) return true
        val left = a.lowercase().split(WORD_SPLIT).filter { it.length > 1 }.toSet()
        val right = b.lowercase().split(WORD_SPLIT).filter { it.length > 1 }.toSet()
        if (left.isEmpty() || right.isEmpty()) return false
        val shared = left.count { it in right }
        // Against the smaller set, so a clipped detection still matches the complete one.
        return shared.toFloat() / minOf(left.size, right.size) >= SAME_LINE_OVERLAP
    }

    private fun wordCount(text: String): Int =
        text.split(WORD_SPLIT).count { it.length > 1 }

    /** Fraction of [inner]'s area that lies inside [outer], in 0..1. */
    private fun containment(inner: Rect, outer: Rect): Float {
        val area = inner.width().toLong() * inner.height()
        if (area <= 0) return 0f
        val overlap = Rect(inner)
        if (!overlap.intersect(outer)) return 0f
        return (overlap.width().toLong() * overlap.height()).toFloat() / area
    }

    /** One bubble that has been cleared, and the text still to be written into it. */
    private class Plan(
        /** Where the text may go, in page coordinates. */
        val slot: Rect,
        val text: String,
        val inkColor: Int,
        val paperColor: Int,
        /** The original was set flush left — a status window or caption, not a speech bubble. */
        val alignLeft: Boolean = false,
    )

    /**
     * True when the original lettering was set flush left rather than centred.
     *
     * Speech bubbles are centred; the game-style status windows these series are full of are not —
     * they are ragged-right blocks of stat lines, and re-centring them destroys the one visual cue
     * that says "this is an interface, not someone talking". The test compares how tightly the line
     * starts agree against how tightly their centres agree: on centred text the centres line up and
     * the left edges do not, and on flush-left text it is the other way round.
     *
     * OCR emits one box per word, so boxes are grouped into lines by vertical overlap first.
     */
    private fun looksLeftAligned(lines: List<TextLineBox>): Boolean {
        if (lines.size < MIN_LINES_FOR_ALIGNMENT) return false

        val rows = ArrayList<Rect>()
        for (line in lines.sortedBy { it.rect.top }) {
            val row = rows.lastOrNull()
            val height = line.rect.height()
            // Same row when the vertical overlap is most of a line's height.
            if (row != null && line.rect.top < row.bottom - height * ROW_OVERLAP) {
                row.union(line.rect)
            } else {
                rows += Rect(line.rect)
            }
        }
        if (rows.size < MIN_LINES_FOR_ALIGNMENT) return false

        // Short rows carry no alignment information — a one-word last line sits left under centred
        // text just as it does under flush-left text — and including them muddies both measures.
        val widest = rows.maxOf { it.width() }
        val usable = rows.filter { it.width() >= widest * MIN_ROW_WIDTH_RATIO }
        if (usable.size < MIN_LINES_FOR_ALIGNMENT) return false

        val leftSpread = spreadOf(usable.map { it.left })
        val centreSpread = spreadOf(usable.map { it.centerX() })
        return leftSpread < centreSpread - ALIGNMENT_MARGIN
    }

    /** Mean absolute deviation: robust enough here, and cheaper than a real standard deviation. */
    private fun spreadOf(values: List<Int>): Float {
        if (values.size < 2) return 0f
        val mean = values.sum().toFloat() / values.size
        return values.sumOf { kotlin.math.abs(it - mean).toDouble() }.toFloat() / values.size
    }

    /**
     * Trims [slot] clear of text already laid down on this page.
     *
     * Two detections over one wide caption both find the same field and both expand into all of it, so
     * each writes its line across the other's — the page ends up with two translations superimposed
     * and neither readable. Yielding the overlap to whichever bubble was drawn first keeps them apart.
     *
     * Only whole horizontal bands are given up. Text is laid out in lines, so cutting the top or
     * bottom leaves a usable slot, while cutting a side would leave a column too narrow to wrap into.
     * Returns null when nothing usable survives, and the bubble is then skipped rather than stacked.
     */
    private fun clearOf(slot: Rect, painted: List<Rect>): Rect? {
        val result = Rect(slot)
        for (other in painted) {
            if (!Rect.intersects(result, other)) continue
            val cutFromTop = other.bottom - result.top
            val cutFromBottom = result.bottom - other.top
            if (cutFromTop <= cutFromBottom) {
                result.top = other.bottom
            } else {
                result.bottom = other.top
            }
            if (result.height() < MIN_SLOT_SIDE) return null
        }
        return if (result.width() < MIN_SLOT_SIDE || result.height() < MIN_SLOT_SIDE) null else result
    }

    /** Clears one bubble's original lettering and reports where its translation may be written. */
    private fun eraseBubble(bitmap: Bitmap, bubble: TranslatedBubble): Plan? {
        val box = bubble.box.clampTo(bitmap.width, bitmap.height)
        if (!box.isUsable()) return null

        // Work on a padded crop: the detector's box often clips stroke tips, and the inpainter needs
        // known pixels on all sides of the mask to reconstruct from.
        val pad = (min(box.width, box.height) * REGION_PAD_RATIO).roundToInt().coerceIn(4, 24)
        val regionLeft = (box.left - pad).coerceAtLeast(0)
        val regionTop = (box.top - pad).coerceAtLeast(0)
        val regionRight = (box.right + pad).coerceAtMost(bitmap.width)
        val regionBottom = (box.bottom + pad).coerceAtMost(bitmap.height)
        val width = regionRight - regionLeft
        val height = regionBottom - regionTop
        if (width < MIN_REGION_SIDE || height < MIN_REGION_SIDE) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, regionLeft, regionTop, width, height)

        val searchArea = glyphSearchArea(bubble, regionLeft, regionTop, width, height, box.width, box.height)
        // Preferred path: repaint a flat bubble interior wholesale. It erases the lettering with no
        // residue, leaves no fringe where a glyph mask stopped, and hands back the bubble's real shape
        // so the replacement text is fitted to the bubble rather than to a guess.
        // OCR geometry, moved into region coordinates so the fill can tell lettering from outline.
        val textLines = bubble.lines.map { line ->
            Rect(line.rect).apply { offset(-regionLeft, -regionTop) }
        }

        // Measured from the original lettering, before any of it is erased.
        val leftAligned = looksLeftAligned(bubble.lines)

        // Text blocks are handled below, *after* the flood fill has had its chance.
        //
        // Order matters here and the obvious order is wrong. Wiping OCR line strips only clears what
        // OCR saw; the ends of each line, where the boxes stop short, survive — the reader gets the
        // translation with the original's first and last letters still showing on either side of it.
        // A flood fill has no such gap: it repaints the bubble's whole interior. So the strip wipe is
        // strictly the fallback, for the panels that have no floodable interior at all.
        val isTextBlock = bubble.box.isTextBlock && textLines.isNotEmpty()

        val flat = BubbleFill.detect(pixels, width, height, searchArea, textLines)
        if (flat != null) {
            for (i in pixels.indices) if (flat.region[i]) pixels[i] = flat.fillColor

            // Sweep the whole filled bubble for leftover lettering, not just the detector's box.
            //
            // The box routinely covers only part of the bubble it found — the flood fill then finds
            // the real extent, but the glyph search was bounded by the box, so any line of the
            // original outside it survives. On the page that exposed this the reader saw a finished
            // Vietnamese sentence with "THING ELSE, SIR?" still sitting underneath it. The fill
            // region is the bubble's true shape, so that is what has to be swept.
            val sweepArea = Rect(flat.bounds).apply {
                union(searchArea)
                left = left.coerceAtLeast(0)
                top = top.coerceAtLeast(0)
                right = right.coerceAtMost(width)
                bottom = bottom.coerceAtMost(height)
            }
            val leftover = GlyphMask.detect(pixels, width, height, sweepArea)
            if (leftover.covered > 0) {
                var outside = 0
                for (i in leftover.mask.indices) {
                    if (leftover.mask[i] && !flat.region[i]) outside++ else if (leftover.mask[i]) leftover.mask[i] = false
                }
                if (outside > MIN_LEFTOVER_GLYPHS) {
                    Inpainter.fill(pixels, width, height, leftover.mask)
                    logcat { "Erased $outside leftover glyph px outside the flat fill of ${bubble.box}" }
                }
            }
            bitmap.setPixels(pixels, 0, width, regionLeft, regionTop, width, height)

            val slot = TextArea.largestInscribedRect(flat.region, width, flat.bounds) ?: flat.bounds
            val page = Rect(slot).apply { offset(regionLeft, regionTop) }
            logcat {
                "bubble=${bubble.box} flat-fill bounds=${flat.bounds} slot=$slot page=$page " +
                    "fill=${Integer.toHexString(flat.fillColor)} ink=${Integer.toHexString(flat.textColor)}"
            }
            return Plan(page, bubble.translated, flat.textColor, flat.fillColor, leftAligned)
        }

        // No floodable interior. A status panel lands here — translucent gradient, no flat region to
        // fill — and this is where wiping the OCR line strips is the right tool.
        if (isTextBlock) {
            eraseTextBlock(
                bitmap, bubble, pixels, width, height, textLines, regionLeft, regionTop, leftAligned,
            )?.let { return it }
        }

        val mask = GlyphMask.detect(pixels, width, height, searchArea)
        if (mask.covered == 0) {
            logcat { "No glyphs found in ${bubble.box}; leaving artwork untouched" }
            return null
        }

        // Sample from the undilated core: the dilated mask has already grown into the paper, and
        // averaging that turns black lettering grey.
        val inkColor = coreInkColor(pixels, mask.core, mask.lightOnDark) ?: fallbackInk(mask.lightOnDark)

        // No enclosing bubble *and* saturated ink means this is display lettering painted onto the
        // artwork — a series logo, a chapter title, a stylised sound effect. It is part of the
        // picture, not dialogue: the detector finds it, the provider dutifully translates it, and
        // the result is a Vietnamese caption stamped over the title art. Real speech is drawn in
        // black or white essentially without exception, so saturation is the tell.
        //
        // Brightness has to gate the test. HSV saturation is (max-min)/max, so at low brightness a
        // couple of levels of colour noise reads as fully saturated: near-black ink sampled as
        // #0c1924 scores 0.67 and a perfectly ordinary line of dialogue over dark artwork gets
        // dropped. Only lettering bright enough for its hue to be real can be judged by hue.
        if (brightnessOf(inkColor) >= MIN_SATURATION_BRIGHTNESS &&
            saturationOf(inkColor) > MAX_DIALOGUE_SATURATION
        ) {
            logcat {
                "Skipping ${bubble.box}: saturated ink ${Integer.toHexString(inkColor)} with no bubble " +
                    "— decorative lettering, not dialogue"
            }
            return null
        }

        Inpainter.fill(pixels, width, height, mask.mask)
        bitmap.setPixels(pixels, 0, width, regionLeft, regionTop, width, height)

        // Fallback slot: the footprint of the original lettering. Used when nothing encloses the text,
        // so a translation over artwork never covers more than the original did.
        val coreBounds = boundsOf(mask.core, width, height) ?: return null
        val glyphFallback = Rect(coreBounds).apply {
            inset(-(mask.strokeRadius + 2), -(mask.strokeRadius + 2))
            left = left.coerceAtLeast(0)
            top = top.coerceAtLeast(0)
            right = right.coerceAtMost(width)
            bottom = bottom.coerceAtMost(height)
        }

        val textArea = TextArea.find(
            pixels = pixels,
            width = width,
            height = height,
            bounds = Rect(0, 0, width, height),
            seed = coreBounds.centerX() to coreBounds.centerY(),
            blocked = mask.blocked,
            fallback = glyphFallback,
        )

        val paperColor = averageColorIn(pixels, width, textArea)

        // Geometry trace. The failure mode this exists for is subtle: when the mask under-covers, the
        // leftover glyph edges survive inpainting as a blur, and the text slot then lands below the
        // ghost instead of over it. Coverage plus the two rectangles say immediately which stage went
        // wrong, which a screenshot cannot.
        logcat {
            "bubble=${bubble.box} region=${width}x$height search=$searchArea " +
                "stroke=${mask.strokeRadius} core=${countTrue(mask.core)} masked=${mask.covered} " +
                "blocked=${countTrue(mask.blocked)} glyphBounds=$coreBounds textArea=$textArea " +
                "lines=${bubble.lines.size} ink=${Integer.toHexString(inkColor)}"
        }

        val page = Rect(textArea).apply { offset(regionLeft, regionTop) }
        return Plan(page, bubble.translated, inkColor, paperColor, leftAligned)
    }

    /**
     * Clears a block of lettering that has no bubble around it — a status window, a caption.
     *
     * Masking by glyph fails here and cannot be made to work: the panels are translucent gradients
     * and their lettering is a pale tint of the same hue as the panel, so no threshold separates
     * them. Left to the glyph mask, the original shows through the translation as a ghost, which is
     * exactly what the reader sees on a skill panel.
     *
     * The OCR line boxes are the reliable geometry, so the whole line strip is inpainted rather than
     * the strokes inside it. That is safe precisely because these panels are smooth gradients: the
     * inpainter has consistent colour on every side of the strip to reconstruct from. It would not
     * be safe over drawn artwork, which is why it is reserved for boxes the text pass produced.
     */
    private fun eraseTextBlock(
        bitmap: Bitmap,
        bubble: TranslatedBubble,
        pixels: IntArray,
        width: Int,
        height: Int,
        textLines: List<Rect>,
        regionLeft: Int,
        regionTop: Int,
        leftAligned: Boolean,
    ): Plan? {
        // Grow each strip sideways much more than vertically.
        //
        // ML Kit's boxes hug the glyphs it recognised, and the first and last glyph of a line are
        // exactly the ones it clips or misses. Padding by a fraction of line height — symmetric and
        // small — left those ends behind, so the translation appeared with the original's opening
        // and closing letters still flanking it. Vertical padding stays tight because neighbouring
        // lines are close and over-reaching would eat into the line above.
        val strips = textLines
            .map { line ->
                val growX = max(6, (line.height() * LINE_STRIP_PAD_X).roundToInt())
                val growY = max(2, (line.height() * LINE_STRIP_PAD_Y).roundToInt())
                Rect(line).apply {
                    inset(-growX, -growY)
                    left = left.coerceIn(0, width)
                    top = top.coerceIn(0, height)
                    right = right.coerceIn(0, width)
                    bottom = bottom.coerceIn(0, height)
                }
            }
            .filter { it.width() > 0 && it.height() > 0 }
        if (strips.isEmpty()) return null

        // Wiping a whole line strip is only safe where the background is smooth enough for the
        // inpainter to rebuild — a status panel's gradient is, a drawing is not. Lettering does
        // appear over artwork (signs, banners, writing on a wall), and blanking strips across that
        // would erase the picture itself. Where the surroundings are busy, fall back to the glyph
        // mask: it may leave a faint ghost, but a ghost is recoverable and a hole in the artwork is
        // not.
        if (!backgroundIsSmooth(pixels, width, height, strips)) {
            logcat { "text-block ${bubble.box}: background is not flat, using the glyph mask instead" }
            return null
        }

        // Sample the ink before erasing: the replacement should match the panel's own lettering,
        // which is usually a bright tint rather than the black a bubble would use.
        val inkColor = strokeColorIn(pixels, width, strips) ?: Color.WHITE

        val mask = BooleanArray(pixels.size)
        for (strip in strips) {
            for (y in strip.top until strip.bottom) {
                val base = y * width
                for (x in strip.left until strip.right) mask[base + x] = true
            }
        }
        Inpainter.fill(pixels, width, height, mask)
        bitmap.setPixels(pixels, 0, width, regionLeft, regionTop, width, height)

        val bounds = Rect(strips[0])
        for (strip in strips) bounds.union(strip)
        val paperColor = averageColorIn(pixels, width, bounds)

        val page = Rect(bounds).apply { offset(regionLeft, regionTop) }
        logcat {
            "text-block=${bubble.box} strips=${strips.size} bounds=$bounds page=$page " +
                "left=$leftAligned ink=${Integer.toHexString(inkColor)}"
        }
        return Plan(page, bubble.translated, inkColor, paperColor, leftAligned)
    }

    /**
     * Colour of the lettering inside [strips].
     *
     * Deliberately hue-agnostic. Panels come in whatever colour the artist chose — blue system
     * windows, red warnings, gold titles — and their text may be lighter or darker than the panel
     * behind it. So rather than assuming any particular colour, the panel colour is taken as the
     * median (lettering is always the minority of a text strip) and the ink is averaged from the
     * pixels furthest from it in full RGB. Distance in RGB rather than luminance matters for the
     * case luminance cannot see: coloured text on a background of similar brightness.
     */
    private fun strokeColorIn(pixels: IntArray, width: Int, strips: List<Rect>): Int? {
        val samples = ArrayList<Int>()
        for (strip in strips) {
            for (y in strip.top until strip.bottom) {
                val base = y * width
                for (x in strip.left until strip.right) samples.add(pixels[base + x])
            }
        }
        if (samples.size < MIN_INK_SAMPLES) return null

        val byLuma = samples.sortedBy { luminanceOf(it) }
        val paper = byLuma[byLuma.size / 2]
        val take = max(1, (samples.size * TEXT_BLOCK_INK_PERCENTILE).roundToInt())
        val selected = samples.sortedByDescending { colorDistance(it, paper) }.take(take)

        var r = 0L
        var g = 0L
        var b = 0L
        for (p in selected) {
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        val count = selected.size
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    /** Summed absolute per-channel difference — the measure [backgroundIsSmooth] was tuned against. */
    private fun channelSum(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)
    }

    /** Squared RGB distance. Squared is enough — it is only ever compared against itself. */
    private fun colorDistance(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    /**
     * True when the background behind the lettering is uniform enough that blanking whole strips
     * across it will reconstruct cleanly.
     *
     * Measured on the rows *between* the lines of text, across the full width of the block. Those
     * rows are pure background: on a status panel they are flat or a smooth gradient and contain
     * essentially no horizontal edges; over artwork they contain the drawing.
     *
     * An earlier version sampled two thin rows immediately above and below each line instead, and
     * inverted on real pages — a panel's glyph overshoot and filigree fall exactly there, while an
     * artwork band of open sky looked pristine. Measured on real regions from a chapter, this
     * version reads 0.000 for every skill panel against 0.007-0.043 for artwork.
     */
    private fun backgroundIsSmooth(
        pixels: IntArray,
        width: Int,
        height: Int,
        strips: List<Rect>,
    ): Boolean {
        val bounds = Rect(strips[0])
        for (strip in strips) bounds.union(strip)
        bounds.left = bounds.left.coerceIn(0, width - 1)
        bounds.right = bounds.right.coerceIn(bounds.left + 1, width)
        bounds.top = bounds.top.coerceIn(0, height - 1)
        bounds.bottom = bounds.bottom.coerceIn(bounds.top + 1, height)
        if (bounds.width() < MIN_SMOOTHNESS_WIDTH) return false

        val gapRows = ArrayList<Float>()
        for (y in bounds.top until bounds.bottom) {
            if (strips.any { y >= it.top && y < it.bottom }) continue
            val base = y * width
            var edges = 0
            var seen = 0
            for (x in bounds.left until bounds.right - 1) {
                if (channelSum(pixels[base + x], pixels[base + x + 1]) > EDGE_STEP_LIMIT) edges++
                seen++
            }
            if (seen > 0) gapRows += edges.toFloat() / seen
        }
        if (gapRows.size < MIN_GAP_ROWS) return false

        gapRows.sort()
        val median = gapRows[gapRows.size / 2]
        return median <= MAX_GAP_ROUGHNESS
    }

    /**
     * Where to look for glyphs: the detector's box, inset slightly because the outer few percent is
     * outline and margin rather than lettering.
     *
     * OCR line boxes are deliberately *not* used to bound the search, even though they are available
     * and hug the glyphs more tightly. Restricting the mask to them left visible ghosts of the
     * original lettering on real pages — the boxes clip ascenders, punctuation and the outer stroke of
     * the first and last glyph on a line, and anything outside them survives inpainting. Searching the
     * whole box and letting the structural-ink filter protect the outline is the configuration that
     * was verified against real artwork.
     */
    private fun glyphSearchArea(
        bubble: TranslatedBubble,
        regionLeft: Int,
        regionTop: Int,
        width: Int,
        height: Int,
        boxWidth: Int,
        boxHeight: Int,
    ): Rect {
        val insetX = (boxWidth * BOX_INSET_RATIO).roundToInt()
        val insetY = (boxHeight * BOX_INSET_RATIO).roundToInt()
        val left = (bubble.box.left - regionLeft + insetX).coerceIn(0, width - 1)
        val top = (bubble.box.top - regionTop + insetY).coerceIn(0, height - 1)
        val right = (bubble.box.right - regionLeft - insetX).coerceIn(left + 1, width)
        val bottom = (bubble.box.bottom - regionTop - insetY).coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    /**
     * Colour of the original lettering, taken from the stroke interiors.
     *
     * Only the darkest (or lightest) two-fifths of the core mask are averaged. The rest of the mask is
     * anti-aliased toward the paper, and including it visibly washes the replacement text out.
     */
    private fun coreInkColor(pixels: IntArray, core: BooleanArray, lightOnDark: Boolean): Int? {
        val samples = ArrayList<Int>()
        for (i in pixels.indices) if (core[i]) samples.add(pixels[i])
        if (samples.size < MIN_INK_SAMPLES) return null

        val sorted = samples.sortedBy { luminanceOf(it) }
        val take = max(1, (sorted.size * INK_PERCENTILE).roundToInt())
        val selected = if (lightOnDark) sorted.takeLast(take) else sorted.take(take)

        var r = 0L
        var g = 0L
        var b = 0L
        for (p in selected) {
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        val count = selected.size
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun countTrue(mask: BooleanArray): Int {
        var count = 0
        for (value in mask) if (value) count++
        return count
    }

    private fun luminanceOf(color: Int): Float =
        0.299f * ((color shr 16) and 0xFF) + 0.587f * ((color shr 8) and 0xFF) + 0.114f * (color and 0xFF)

    private fun boundsOf(mask: BooleanArray, width: Int, height: Int): Rect? {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (i in mask.indices) {
            if (!mask[i]) continue
            val x = i % width
            val y = i / width
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    private fun averageColorIn(pixels: IntArray, width: Int, rect: Rect): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (y in rect.top until rect.bottom) {
            val base = y * width
            for (x in rect.left until rect.right) {
                val p = pixels[base + x]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                count++
            }
        }
        if (count == 0) return Color.WHITE
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun fallbackInk(lightOnDark: Boolean): Int = if (lightOnDark) Color.WHITE else Color.BLACK

    /** HSV value in 0..255: the largest channel. */
    private fun brightnessOf(color: Int): Int =
        maxOf(Color.red(color), Color.green(color), Color.blue(color))

    /** HSV saturation in 0..1. Zero for any grey, including pure black and pure white. */
    private fun saturationOf(color: Int): Float {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        return if (max == 0) 0f else (max - min).toFloat() / max
    }

    /**
     * Fits [text] into the rectangle, shrinking until it fits, then lays it out.
     *
     * A contrasting halo is added only when ink and reconstructed paper are too close in luminance —
     * which happens with coloured dialogue over busy artwork. Adding it unconditionally would look
     * wrong on ordinary white bubbles.
     *
     * @param alignLeft set the block flush left instead of centred, and honour the line breaks the
     *   translation came with. Status windows and stat blocks are written as one item per line; run
     *   together into a centred paragraph they stop reading as an interface at all.
     */
    private fun drawText(
        canvas: Canvas,
        text: String,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        inkColor: Int,
        paperColor: Int,
        typeface: Typeface,
        alignLeft: Boolean = false,
    ): Boolean {
        val padX = max(3, (width * TEXT_PADDING_RATIO).roundToInt())
        val padY = max(3, (height * TEXT_PADDING_RATIO).roundToInt())
        val usableWidth = (width - padX * 2).coerceAtLeast(1)
        val usableHeight = (height - padY * 2).coerceAtLeast(1)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            color = inkColor
        }

        // Collapsing every run of whitespace is right for a speech bubble, where the line breaks are
        // an artefact of how the original was fitted. It is wrong for a stat block, where they are
        // the structure — so there, only spaces and tabs collapse and the newlines survive.
        val normalized = if (alignLeft) {
            text.trim().lines().joinToString("\n") { it.replace(SPACE_RUN, " ").trim() }
                .replace(BLANK_LINE_RUN, "\n")
        } else {
            text.trim().replace(WHITESPACE_RUN, " ")
        }

        // Start from the largest size the box could possibly hold and walk down to the first that
        // fits, so the text ends up as large as the bubble allows. Starting from an area-based
        // estimate instead — as the reference does — undershoots whenever the wrap lands favourably,
        // and the result is dialogue that sits small and lost in a large bubble.
        val ceiling = min(MAX_FONT_SIZE, max(MIN_FONT_SIZE, usableHeight))
        var size = ceiling
        var lines: List<String> = listOf(normalized)
        var chosen = false

        // A size that fits only by splitting a word through the middle. Held in reserve: a narrow
        // caption column can be too tight for the longest Vietnamese word at any size, and a hyphenless
        // break mid-word — "TRƯỜN / G" — is the single most obvious tell that a page was not lettered
        // by hand. Shrinking further to keep words whole is almost always the better trade.
        var fallbackSize = 0
        var fallbackLines: List<String>? = null

        // The search runs below MIN_FONT_SIZE rather than stopping there. That floor is a legibility
        // preference, but honouring it when nothing fits does not make the text larger — it makes the
        // overflow get clipped, and the reader loses the end of the sentence outright. A verbose line
        // in a tight bubble set small is recoverable; a translation missing its last clause is not.
        while (size >= ABSOLUTE_MIN_FONT_SIZE) {
            paint.textSize = size.toFloat()
            val candidate = wrap(normalized, paint, usableWidth)
            val lineHeight = paint.fontSpacing
            if (candidate.lines.size * lineHeight <= usableHeight &&
                candidate.lines.all { paint.measureText(it) <= usableWidth }
            ) {
                if (!candidate.brokeWord) {
                    lines = candidate.lines
                    chosen = true
                    break
                }
                if (fallbackLines == null) {
                    fallbackLines = candidate.lines
                    fallbackSize = size
                }
            }
            size -= 1
        }

        if (!chosen && fallbackLines != null) {
            size = fallbackSize
            lines = fallbackLines
            chosen = true
        }

        // Nothing fits, even at the absolute minimum with words broken — the slot is simply too
        // small for this string. Drawing anyway would clip it into fragments; leaving the bubble
        // empty (its original text survives outside flat-fill, or the bubble stays blank) is the
        // less broken page.
        if (!chosen) return false

        paint.textSize = size.coerceAtLeast(ABSOLUTE_MIN_FONT_SIZE).toFloat()

        val lineHeight = paint.fontSpacing
        val blockHeight = lines.size * lineHeight
        var baseline = top + padY + (usableHeight - blockHeight) / 2f - paint.fontMetrics.ascent

        val needsHalo = luminanceDistance(inkColor, paperColor) < MIN_CONTRAST
        val halo = if (needsHalo) {
            Paint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(1.5f, paint.textSize * HALO_WIDTH_RATIO)
                color = contrastingColor(inkColor)
            }
        } else {
            null
        }

        for (line in lines) {
            val x = if (alignLeft) {
                (left + padX).toFloat()
            } else {
                left + padX + (usableWidth - paint.measureText(line)) / 2f
            }
            halo?.let { canvas.drawText(line, x, baseline, it) }
            canvas.drawText(line, x, baseline, paint)
            baseline += lineHeight
        }
        return true
    }

    /** @param brokeWord a token had to be split mid-word to fit, which the caller tries to avoid. */
    private class Wrapped(val lines: List<String>, val brokeWord: Boolean)

    /**
     * Greedy word wrap that falls back to character wrapping.
     *
     * The character fallback matters for CJK output and for Vietnamese compounds that exceed a narrow
     * bubble: without it a single long token would overflow the clip and be cut off mid-word.
     *
     * Newlines in [text] are hard breaks — each is wrapped on its own, so a stat line too long for
     * the box still continues on the next line rather than running into the following stat.
     */
    private fun wrap(text: String, paint: Paint, maxWidth: Int): Wrapped {
        if ('\n' in text) {
            val all = mutableListOf<String>()
            var broke = false
            for (paragraph in text.split('\n')) {
                if (paragraph.isBlank()) {
                    all += ""
                    continue
                }
                val wrapped = wrapOne(paragraph, paint, maxWidth)
                all += wrapped.lines
                broke = broke || wrapped.brokeWord
            }
            return Wrapped(all.ifEmpty { listOf(text) }, broke)
        }
        return wrapOne(text, paint, maxWidth)
    }

    private fun wrapOne(text: String, paint: Paint, maxWidth: Int): Wrapped {
        if (text.isEmpty()) return Wrapped(listOf(""), false)
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var brokeWord = false

        fun flush() {
            if (current.isNotEmpty()) {
                lines += current.toString()
                current.setLength(0)
            }
        }

        for (word in text.split(' ')) {
            if (word.isEmpty()) continue
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current.setLength(0)
                current.append(candidate)
                continue
            }
            flush()
            if (paint.measureText(word) <= maxWidth) {
                current.append(word)
            } else {
                brokeWord = true
                var chunk = StringBuilder()
                for (char in word) {
                    if (paint.measureText(chunk.toString() + char) > maxWidth && chunk.isNotEmpty()) {
                        lines += chunk.toString()
                        chunk = StringBuilder()
                    }
                    chunk.append(char)
                }
                if (chunk.isNotEmpty()) current.append(chunk)
            }
        }
        flush()
        return Wrapped(lines.ifEmpty { listOf(text) }, brokeWord)
    }

    private fun luminanceDistance(a: Int, b: Int): Float {
        fun luma(color: Int) = 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)
        return kotlin.math.abs(luma(a) - luma(b))
    }

    private fun contrastingColor(ink: Int): Int {
        val luma = 0.299f * Color.red(ink) + 0.587f * Color.green(ink) + 0.114f * Color.blue(ink)
        return if (luma > 127f) Color.BLACK else Color.WHITE
    }

    private companion object {
        val WHITESPACE_RUN = Regex("\\s+")
        val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")
        /** Horizontal whitespace only: collapses runs of spaces without touching line breaks. */
        val SPACE_RUN = Regex("[ \\t\\x0B\\f\\r]+")
        val BLANK_LINE_RUN = Regex("\n{2,}")
        /** Fewer lines than this and the alignment measurement has nothing to work with. */
        const val MIN_LINES_FOR_ALIGNMENT = 3
        /** Fraction of a line's height that must overlap for two OCR boxes to be the same row. */
        const val ROW_OVERLAP = 0.5f
        /** Rows narrower than this fraction of the widest carry no alignment information. */
        const val MIN_ROW_WIDTH_RATIO = 0.5f
        /** Pixels by which line starts must agree better than line centres to call it flush left. */
        const val ALIGNMENT_MARGIN = 2f
        /** Word overlap above which two overlapping bubbles are treated as one line reworded. */
        const val SAME_LINE_OVERLAP = 0.6f
        /** Slot area inside an already-lettered slot above which it is the same bubble again. */
        const val NESTED_SLOT_FRACTION = 0.7f
        /** Shorter lines than this may legitimately repeat on one page, so they are not deduped. */
        const val MIN_WORDS_FOR_PAGE_DEDUPE = 4

        const val REGION_PAD_RATIO = 0.12f
        const val MIN_REGION_SIDE = 10
        const val BOX_INSET_RATIO = 0.06f
        const val MIN_INK_SAMPLES = 12
        const val INK_PERCENTILE = 0.40f
        /** Lettering is a minority of a text strip, so a narrower tail than a glyph mask needs. */
        const val TEXT_BLOCK_INK_PERCENTILE = 0.15f
        /** Horizontal padding of an OCR line box before inpainting, as a fraction of line height. */
        const val LINE_STRIP_PAD_X = 0.45f
        /** Vertical padding: kept tight so a strip does not reach into the line above or below. */
        const val LINE_STRIP_PAD_Y = 0.18f
        /**
         * Summed absolute RGB step between neighbouring pixels counted as a drawn edge. Matches the
         * threshold the measurement was tuned at against real pages.
         */
        const val EDGE_STEP_LIMIT = 40
        const val MIN_SMOOTHNESS_WIDTH = 60
        const val MIN_GAP_ROWS = 8
        /** Median edge fraction in the rows between lines, above which strip-wiping is unsafe. */
        const val MAX_GAP_ROUGHNESS = 0.003f

        const val MIN_SLOT_SIDE = 12
        /** Narrower than this and no line of dialogue can render unclipped. */
        const val MIN_DRAW_WIDTH = 32
        /** Leftover-glyph sweeps smaller than this are noise, not a surviving line of text. */
        const val MIN_LEFTOVER_GLYPHS = 40
        /** Above this ink saturation, unenclosed lettering is title/logo art rather than speech. */
        const val MAX_DIALOGUE_SATURATION = 0.35f
        /** Below this brightness, hue is noise and the saturation test must not be applied. */
        const val MIN_SATURATION_BRIGHTNESS = 110
        const val TEXT_PADDING_RATIO = 0.08f
        const val CHAR_ASPECT = 0.62f
        const val MIN_FONT_SIZE = 9
        /** Only reached when the slot cannot hold the text at a comfortable size; beats clipping it. */
        const val ABSOLUTE_MIN_FONT_SIZE = 5
        const val MAX_FONT_SIZE = 72
        const val MIN_CONTRAST = 60f
        const val HALO_WIDTH_RATIO = 0.14f
    }
}
