package mihon.feature.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.feature.translation.detect.BubbleDetector
import mihon.feature.translation.detect.LowConfidenceSpeechGuard
import mihon.feature.translation.detect.OversizedSpeechRefiner
import mihon.feature.translation.detect.TextBlockDetector
import mihon.feature.translation.model.BubbleBox
import mihon.feature.translation.model.BubbleText
import mihon.feature.translation.model.TextLineBox
import mihon.feature.translation.model.TranslatedBubble
import mihon.feature.translation.ocr.BubbleTextRecognizer
import mihon.feature.translation.provider.BubbleTranslation
import mihon.feature.translation.provider.TranslationContext
import mihon.feature.translation.provider.TranslationProvider
import mihon.feature.translation.provider.TranslationProviders
import mihon.feature.translation.render.BubbleRenderer
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Translates a single manga page end to end: detect bubbles, read them, translate, repaint.
 *
 * Detection and rendering are always on-device. Only the text leaves the device, and only to the
 * provider the user picked.
 */
class PageTranslator(
    private val context: Context,
    private val preferences: TranslationPreferences,
) {

    private val detector by lazy { BubbleDetector(context) }
    private val textBlocks by lazy { TextBlockDetector() }
    private val recognizer by lazy { BubbleTextRecognizer(context) }
    private val renderer by lazy { BubbleRenderer(context) }

    /**
     * Guards the on-device stages only: the ONNX detector session and the ML Kit recogniser
     * are each single-lane, and running two pages through them at once corrupts or blocks.
     * The provider call sits outside it on purpose — see [translate].
     */
    private val localWork = Mutex()

    /** Thrown when the page simply has no dialogue to translate — not an error worth surfacing. */
    class NothingToTranslate : Exception("No speech bubbles found")

    /**
     * Translates several consecutive pages as one tall image, then cuts the result back apart.
     *
     * Manhwa sources deliver a chapter as a hundred short images rather than one strip, and the
     * provider charges by the *image*, not by its size: measured on this endpoint, one 1280x12755
     * page costs 1050 tokens while the same content cut into 40 slices costs 42880 — forty times as
     * much for identical pixels. Sending the slices re-joined is simply how the page was meant to be
     * read, and it collapses both the token cost and the request count by the same factor.
     *
     * Two smaller benefits fall out of it. A bubble split across the seam between two slices is
     * whole again, where separately it would be two clipped fragments that [isEdgeSliver] discards.
     * And the model sees a continuous passage, so pronouns and register stay consistent in a way
     * page-at-a-time translation cannot manage.
     *
     * @return one bitmap per input, in order; entries are new bitmaps and none of [sources] is
     *   recycled here
     * @throws NothingToTranslate when the joined strip yielded no dialogue at all
     */
    suspend fun translateStrip(sources: List<Bitmap>, diagnosticLabel: String = "strip"): List<Bitmap> {
        require(sources.isNotEmpty()) { "translateStrip needs at least one page" }
        if (sources.size == 1) return listOf(translate(sources[0], diagnosticLabel))

        val width = sources.maxOf { it.width }
        // Heights after scaling every page to the common width, so the seams land where expected.
        val heights = sources.map { (it.height.toLong() * width / it.width).toInt().coerceAtLeast(1) }
        val strip = Bitmap.createBitmap(width, heights.sum(), Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(strip)
            var y = 0
            sources.forEachIndexed { index, page ->
                val destination = android.graphics.Rect(0, y, width, y + heights[index])
                canvas.drawBitmap(page, null, destination, null)
                y += heights[index]
            }
            logcat { "Stitched ${sources.size} pages into ${width}x${strip.height} strip" }

            val seams = heights.runningFold(0, Int::plus).drop(1).dropLast(1).toIntArray()
            val translated = translate(strip, seams, diagnosticLabel)
            return try {
                var top = 0
                sources.mapIndexed { index, page ->
                    val slice = Bitmap.createBitmap(translated, 0, top, width, heights[index])
                    top += heights[index]
                    // Give each page back at its own resolution so the reader's cache entries match
                    // the artwork it would otherwise have shown.
                    if (slice.width == page.width && slice.height == page.height) {
                        slice
                    } else {
                        Bitmap.createScaledBitmap(slice, page.width, page.height, true)
                            .also { if (it !== slice) slice.recycle() }
                    }
                }
            } finally {
                if (translated !== strip) translated.recycle()
            }
        } finally {
            strip.recycle()
        }
    }

    /**
     * @return a new bitmap with translated dialogue; [source] is never recycled here
     * @throws NothingToTranslate when no bubble yielded usable text
     */
    suspend fun translate(source: Bitmap, diagnosticLabel: String = "page"): Bitmap =
        translate(source, intArrayOf(), diagnosticLabel)

    private suspend fun translate(
        source: Bitmap,
        horizontalSeams: IntArray,
        diagnosticLabel: String,
    ): Bitmap = withIOContext {
        var translationContext = TranslationContext(
            sourceLanguage = preferences.sourceLanguage.get(),
            targetLanguage = preferences.targetLanguage.get(),
            styleHint = preferences.styleHint.get(),
        )

        val startedAt = System.currentTimeMillis()
        var lockedAt = startedAt
        var localDoneAt = startedAt

        // Detection and OCR hold the lock; the provider call deliberately does not.
        //
        // The ONNX session and the ML Kit recogniser are single-lane, so those stages must not
        // overlap. Waiting on the network is a different matter: holding the same lock across it
        // meant a page could not even begin reading while the page before it sat waiting on an HTTP
        // response, which is most of a page's wall-clock time. Releasing it here lets one page be
        // read while another is in flight.
        var detectDoneAt = startedAt
        var blocksDoneAt = startedAt
        val (boxes, recognized) = localWork.withLock {
            lockedAt = System.currentTimeMillis()
            val detected = detector.detect(source, horizontalSeams)
            detectDoneAt = System.currentTimeMillis()
            // The bubble model only knows speech bubbles. Status windows, captions and narration
            // boxes are lettering too, and readers care about them just as much — a chapter whose
            // skill panels stay in English is not a translated chapter.
            val textBlockResult = runCatching {
                textBlocks.detect(source, detected, translationContext.sourceLanguage)
            }
                .onFailure { logcat { "Text-block detection failed: ${it.message}" } }
                .getOrNull()
            val extras = textBlockResult?.boxes.orEmpty()
            blocksDoneAt = System.currentTimeMillis()

            // The text-block pass reads the whole page, so when it had to fall back to another
            // script it has already established what this page is written in. Adopt that for the
            // bubble OCR and for the provider: reading Japanese artwork with the Latin recogniser
            // returns plausible-looking garbage ("LEM,TMIE UVGELSW"), which then gets translated
            // and stamped as if it were dialogue.
            textBlockResult?.language
                ?.takeIf { it != translationContext.sourceLanguage }
                ?.let { pageLanguage ->
                    logcat { "Reading this page as '$pageLanguage' rather than the configured source" }
                    translationContext = translationContext.copy(sourceLanguage = pageLanguage)
                }

            val refinedDetected = detected.filterNot { speech ->
                extras.any { text ->
                    !text.isFallbackTextBlock &&
                    OversizedSpeechRefiner.shouldReplace(
                        OversizedSpeechRefiner.Bounds(speech.left, speech.top, speech.right, speech.bottom),
                        OversizedSpeechRefiner.Bounds(text.left, text.top, text.right, text.bottom),
                    )
                }
            }
            if (refinedDetected.size < detected.size) {
                logcat {
                    "Replaced ${detected.size - refinedDetected.size} oversized speech box(es) " +
                        "with precise OCR text blocks"
                }
            }

            val ordered = (refinedDetected + extras).filterNot { it.isEdgeSliver(source.width) }.inReadingOrder()
            if (ordered.size < refinedDetected.size + extras.size) {
                logcat { "Dropped ${refinedDetected.size + extras.size - ordered.size} edge-sliver box(es)" }
            }
            if (ordered.isEmpty()) throw NothingToTranslate()
            logcat {
                "Detected ${ordered.size} regions (${refinedDetected.size} bubbles + ${extras.size} text " +
                    "blocks) on ${source.width}x${source.height} page"
            }
            // Glyph geometry is collected regardless of who does the reading, because the renderer
            // needs it to mask strokes rather than whole bubbles. It is cheap and fully local.
            // Reuse what the whole-page pass already read.
            //
            // That pass runs ML Kit over every band of the page and keeps line geometry; asking ML
            // Kit again, once per bubble, was measured at 40s of a 76s page — over half the total,
            // spent re-reading lettering the pipeline had already read. A bubble whose interior the
            // page pass covered takes its text from there. Bubbles it did not cover — small or faint
            // lettering the banded pass misses, which is exactly what the per-bubble crop with its
            // padding, upscaling and retry ladder is for — still go through the recogniser.
            val pageText = textBlockResult?.pageText.orEmpty()
            val reused = arrayOfNulls<BubbleText>(ordered.size)
            if (pageText.isNotEmpty()) {
                ordered.forEachIndexed { index, box ->
                    val inside = pageText.filter { block ->
                        val overlap = Rect(block.rect)
                        if (!overlap.intersect(box.toRect())) return@filter false
                        val area = block.rect.width().toLong() * block.rect.height()
                        area > 0 && overlap.width().toLong() * overlap.height() >=
                            area * REUSED_BLOCK_CONTAINMENT
                    }
                    if (inside.isEmpty()) return@forEachIndexed
                    val lines = inside.flatMap { it.lines }
                    if (lines.isEmpty()) return@forEachIndexed
                    val text = inside
                        .sortedBy { it.rect.top }
                        .joinToString("\n") { it.text.trim() }
                        .trim()
                    if (text.isEmpty()) return@forEachIndexed
                    reused[index] = BubbleText(text, lines)
                }
            }
            val needsOcr = ordered.indices.filter { reused[it] == null }
            val freshlyRead = if (needsOcr.isEmpty()) {
                emptyList()
            } else {
                recognizer.recognize(
                    source,
                    needsOcr.map(ordered::get),
                    translationContext.sourceLanguage,
                )
            }
            needsOcr.forEachIndexed { slot, index -> reused[index] = freshlyRead.getOrNull(slot) }
            logcat {
                "OCR: reused ${ordered.size - needsOcr.size}/${ordered.size} region(s) from the " +
                    "page pass, read ${needsOcr.size} directly"
            }
            val read = reused.map { it ?: BubbleText("", emptyList()) }
            val longStrip = source.height > source.width * 3
            val evidenced = ordered.indices.filter { index ->
                ordered[index].isTextBlock ||
                    LowConfidenceSpeechGuard.shouldKeep(
                        ordered[index].confidence,
                        read[index].text,
                        strictShortFragment = longStrip,
                    )
            }
            if (evidenced.size < ordered.size) {
                logcat { "Dropped ${ordered.size - evidenced.size} low-confidence box(es) without OCR evidence" }
            }
            val evidencedBoxes = evidenced.map(ordered::get)
            val evidencedRead = evidenced.map(read::get)
            val speechResolved = SpeechDuplicateResolver.keepIndices(
                evidencedBoxes.mapIndexed { index, box ->
                    SpeechDuplicateResolver.Candidate(
                        left = box.left,
                        top = box.top,
                        right = box.right,
                        bottom = box.bottom,
                        isSpeech = !box.isTextBlock,
                        text = evidencedRead[index].text,
                    )
                },
            )
            if (speechResolved.size < evidencedBoxes.size) {
                logcat { "Dropped ${evidencedBoxes.size - speechResolved.size} duplicate speech box(es)" }
            }
            val speechResolvedBoxes = speechResolved.map(evidencedBoxes::get)
            val speechResolvedRead = speechResolved.map(evidencedRead::get)
            val resolved = FallbackTextBlockResolver.keepIndices(
                speechResolvedBoxes.mapIndexed { index, box ->
                    FallbackTextBlockResolver.Candidate(
                        left = box.left,
                        top = box.top,
                        right = box.right,
                        bottom = box.bottom,
                        isSpeech = !box.isTextBlock,
                        isFallback = box.isFallbackTextBlock,
                        text = speechResolvedRead[index].text,
                    )
                },
            )
            if (resolved.size < speechResolvedBoxes.size) {
                logcat { "Resolved ${speechResolvedBoxes.size - resolved.size} overlapping speech OCR fallback(s)" }
            }
            val resolvedBoxes = resolved.map(speechResolvedBoxes::get)
            val resolvedRead = resolved.map(speechResolvedRead::get)
            val (coalescedBoxes, coalescedRead) = coalesceTextRegions(resolvedBoxes, resolvedRead)
            val kept = coalescedBoxes.indices.filterNot { index ->
                isDecorativeDisplayText(coalescedBoxes[index], coalescedRead[index], source.width)
            }
            if (kept.size < coalescedBoxes.size) {
                logcat { "Dropped ${coalescedBoxes.size - kept.size} decorative title/SFX region(s)" }
            }
            val finalBoxes = kept.map { coalescedBoxes[it] }
            val finalRead = kept.map { coalescedRead[it] }
            localDoneAt = System.currentTimeMillis()
            finalBoxes to finalRead
        }

        val readForTranslation = recognized.map { bubbleText ->
            bubbleText.copy(text = ShortDialogueNormalizer.normalize(bubbleText.text))
        }
        val provider = currentProvider()

        val answered = if (provider.supportsVisionOcr) {
            provider.ocrAndTranslate(source, boxes, translationContext)
        } else {
            translateFromOcr(provider, boxes, readForTranslation, translationContext)
        }
        val aligned = realignToOcr(answered, readForTranslation)
        var translations = aligned.mapIndexed { index, answer ->
            val sourceText = readForTranslation[index].text
            ShortDialogueNormalizer.directTranslation(
                sourceText,
                translationContext.sourceLanguage,
                translationContext.targetLanguage,
            ) ?: if (
                NearEchoNameGuard.preserveSource(
                    sourceText,
                    answer.translation,
                    translationContext.sourceLanguage,
                    translationContext.targetLanguage,
                )
            ) {
                sourceText
            } else {
                answer.translation
            }
        }
        if (provider.supportsVisionOcr) {
            val missing = VisionFallbackSelector.missingIndices(
                translations = translations,
                ocrTexts = recognized.map { it.text },
                speechBoxes = boxes.map { !it.isTextBlock },
                providerSources = aligned.map { it.source },
            )
            if (missing.isNotEmpty()) {
                // Vision models occasionally omit a tiny numbered bubble even though the native OCR
                // crop read it cleanly. One batched text-only fallback recovers only those omissions;
                // meaningful-text filtering keeps short hand-drawn SFX out of this path.
                val recovered = provider.translateLines(
                    missing.map { index ->
                        recognized[index].text.ifBlank { aligned[index].source }
                    },
                    translationContext,
                )
                val patched = translations.toMutableList()
                var filled = 0
                missing.forEachIndexed { position, index ->
                    val translated = recovered.getOrNull(position)?.translation?.trim().orEmpty()
                    if (translated.isNotEmpty()) {
                        patched[index] = translated
                        filled++
                    }
                }
                translations = patched
                logcat { "Recovered $filled/${missing.size} omitted vision bubble(s) from on-device OCR" }
            }

            // A vision model can label a tiny handwritten interjection as unreadable and return both
            // fields empty even though the region is a detector-confirmed speech balloon. Native OCR
            // cannot seed the text fallback in that case either. Retry only those blank speech slots
            // as a much smaller labelled set; the page stays at native resolution and the model no
            // longer has dozens of unrelated regions competing for attention.
            val focusedIndices = FocusedVisionRetrySelector.indices(
                translations = translations,
                speechBoxes = boxes.map { !it.isTextBlock },
                suspectedEchoes = translations.map { text ->
                    SourceEchoHeuristic.isLikely(
                        text,
                        translationContext.sourceLanguage,
                        translationContext.targetLanguage,
                    )
                },
                limit = MAX_FOCUSED_VISION_BOXES,
            )
            if (focusedIndices.isNotEmpty()) {
                val focusedBoxes = focusedIndices.map(boxes::get)
                val focusedRead = focusedIndices.map(recognized::get)
                val focusedAnswered = provider.ocrAndTranslate(source, focusedBoxes, translationContext)
                val focusedAligned = realignToOcr(focusedAnswered, focusedRead)
                val focusedTranslations = focusedAligned.map { it.translation }.toMutableList()
                val focusedFallback = VisionFallbackSelector.missingIndices(
                    translations = focusedTranslations,
                    ocrTexts = focusedRead.map { it.text },
                    speechBoxes = List(focusedIndices.size) { true },
                    providerSources = focusedAligned.map { it.source },
                )
                if (focusedFallback.isNotEmpty()) {
                    val recovered = provider.translateLines(
                        focusedFallback.map { position ->
                            focusedRead[position].text.ifBlank { focusedAligned[position].source }
                        },
                        translationContext,
                    )
                    focusedFallback.forEachIndexed { position, focusedIndex ->
                        val translated = recovered.getOrNull(position)?.translation?.trim().orEmpty()
                        if (translated.isNotEmpty()) focusedTranslations[focusedIndex] = translated
                    }
                }
                val patched = translations.toMutableList()
                var filled = 0
                focusedIndices.forEachIndexed { position, globalIndex ->
                    val translated = focusedTranslations.getOrNull(position)?.trim().orEmpty()
                    if (translated.isNotEmpty()) {
                        patched[globalIndex] = translated
                        filled++
                    }
                }
                translations = patched
                logcat { "Recovered $filled/${focusedIndices.size} blank speech bubble(s) with focused vision" }
            }
        }

        val bubbles = boxes.mapIndexedNotNull { index, box ->
            val ocrText = recognized.getOrNull(index)?.text.orEmpty()
            if (NoisyVocalizationGuard.shouldLeaveUntouched(ocrText)) {
                logcat { "Leaving non-lexical vocalization untouched in bubble ${index + 1}" }
                return@mapIndexedNotNull null
            }
            val translated = translations.getOrNull(index)?.trim().orEmpty()
            if (translated.isEmpty()) return@mapIndexedNotNull null
            if (!plausibleForTarget(translated, translationContext.targetLanguage)) {
                // A CJK string offered as a Vietnamese translation is never a translation — it is the
                // model echoing source text (typically a logo or decorated title it could not render
                // into the target). Drawing it would stamp Chinese into the reader's page.
                logcat { "Dropping non-target-script translation for bubble ${index + 1}" }
                return@mapIndexedNotNull null
            }
            if (isUntranslated(ocrText, translated)) {
                // Providers echo the input when they cannot translate it, which is what happens to
                // misread sound effects ("MHM~~" recognised as "WAWHW"). Erasing hand-drawn lettering
                // only to stamp the same garbled string back is strictly worse than leaving the
                // artwork alone.
                logcat { "Dropping unchanged translation for bubble ${index + 1}" }
                return@mapIndexedNotNull null
            }
            TranslatedBubble(
                box = box,
                original = recognized.getOrNull(index)?.text.orEmpty(),
                translated = translated,
                lines = recognized.getOrNull(index)?.lines.orEmpty(),
            )
        }

        if (bubbles.isEmpty()) throw NothingToTranslate()
        logcat { "$diagnosticLabel rendering ${bubbles.size}/${boxes.size} translated bubbles" }

        val providerDoneAt = System.currentTimeMillis()
        val rendered = renderer.render(source, bubbles, preferences.font.get(), horizontalSeams)
        // Stage timings, because "translation is slow" is not actionable without them: waiting for
        // the single-lane local stages, running them, waiting on the network, and drawing are four
        // very different problems with four different fixes.
        logcat {
            "$diagnosticLabel timing: queue=${lockedAt - startedAt}ms " +
                "detect=${detectDoneAt - lockedAt}ms blocks=${blocksDoneAt - detectDoneAt}ms " +
                "ocr=${localDoneAt - blocksDoneAt}ms provider=${providerDoneAt - localDoneAt}ms " +
                "render=${System.currentTimeMillis() - providerDoneAt}ms " +
                "total=${System.currentTimeMillis() - startedAt}ms"
        }
        rendered
    }

    /**
     * Folds small detector fragments back into the whole-page OCR block that owns the same line.
     *
     * On manga captions ML Kit may stop a wide block just before its final word while the bubble
     * model independently detects that word. Sending both regions to translation produces a clipped
     * sentence plus a second translation stamped over it. Combining their line geometry here gives
     * the provider one complete sentence and gives the renderer one complete erasure region.
     */
    private fun coalesceTextRegions(
        boxes: List<BubbleBox>,
        recognized: List<BubbleText>,
    ): Pair<List<BubbleBox>, List<BubbleText>> {
        val merged = recognized.toMutableList()
        val consumed = BooleanArray(boxes.size)

        boxes.indices.filter { boxes[it].isTextBlock }.forEach { blockIndex ->
            val block = boxes[blockIndex]
            val lines = merged[blockIndex].lines.toMutableList()
            boxes.indices.filter { !boxes[it].isTextBlock && recognized[it].text.isNotBlank() }
                .forEach { candidateIndex ->
                    val candidateLines = recognized[candidateIndex].lines
                    if (candidateLines.isEmpty() ||
                        candidateLines.any { !isSameCaptionLane(it.rect, block.toRect()) }
                    ) {
                        return@forEach
                    }
                    lines += candidateLines
                    consumed[candidateIndex] = true
                }
            if (lines.size != merged[blockIndex].lines.size) {
                val canonical = canonicalCaptionLines(lines)
                merged[blockIndex] = BubbleText(
                    text = canonical.joinToString("\n") { it.text },
                    lines = canonical,
                )
            }
        }

        if (consumed.any { it }) {
            logcat { "Merged ${consumed.count { it }} detector fragment(s) into complete caption blocks" }
        }
        val keep = boxes.indices.filterNot { consumed[it] }
        return keep.map { boxes[it] } to keep.map { merged[it] }
    }

    private fun isSameCaptionLane(line: Rect, block: Rect): Boolean {
        val vertical = minOf(line.bottom, block.bottom) - maxOf(line.top, block.top)
        val shorter = minOf(line.height(), block.height()).coerceAtLeast(1)
        if (vertical.coerceAtLeast(0).toFloat() / shorter < CAPTION_ROW_OVERLAP) return false
        val horizontalGap = when {
            line.right < block.left -> block.left - line.right
            line.left > block.right -> line.left - block.right
            else -> 0
        }
        return horizontalGap <= line.height() * CAPTION_MAX_GAP_HEIGHTS
    }

    private fun canonicalCaptionLines(lines: List<TextLineBox>): List<TextLineBox> {
        val rows = mutableListOf<MutableList<TextLineBox>>()
        lines.sortedBy { it.rect.centerY() }.forEach { line ->
            val row = rows.firstOrNull { existing ->
                val anchor = existing.first().rect
                val overlap = minOf(anchor.bottom, line.rect.bottom) - maxOf(anchor.top, line.rect.top)
                overlap.coerceAtLeast(0).toFloat() /
                    minOf(anchor.height(), line.rect.height()).coerceAtLeast(1) >= CAPTION_ROW_OVERLAP
            }
            if (row == null) rows += mutableListOf(line) else row += line
        }
        return rows.map { row ->
            val useful = row.sortedBy { it.rect.left }.filterIndexed { index, line ->
                row.sortedBy { it.rect.left }.take(index).none { prior ->
                    val overlap = Rect(line.rect)
                    overlap.intersect(prior.rect) &&
                        overlap.width().toFloat() / line.rect.width().coerceAtLeast(1) >= CAPTION_CONTAINMENT
                }
            }
            val text = useful.fold("") { acc, segment -> joinCaptionSegments(acc, segment.text.trim()) }
            val bounds = Rect(useful.first().rect)
            useful.drop(1).forEach { bounds.union(it.rect) }
            TextLineBox(bounds, text)
        }
    }

    private fun joinCaptionSegments(left: String, right: String): String {
        if (left.isBlank()) return right
        if (right.isBlank()) return left
        val last = left.substringAfterLast(' ')
        val cleaned = if (last.length == 1 && right.startsWith(last, ignoreCase = true)) {
            left.dropLast(1).trimEnd()
        } else {
            left
        }
        return "$cleaned $right"
    }

    /**
     * Large unenclosed display typography is artwork (chapter titles, logos and oversized SFX), not
     * dialogue. Synthetic text blocks are the only candidates: real speech-balloon detections keep
     * their large shouts. Element height separates a title set at poster scale from ordinary manga
     * captions while the area condition avoids dropping a single short exclamation.
     */
    private fun isDecorativeDisplayText(box: BubbleBox, text: BubbleText, pageWidth: Int): Boolean {
        return DecorativeTextGuard.shouldDrop(
            isTextBlock = box.isTextBlock,
            text = text.text,
            lineHeights = text.lines.map { it.rect.height() },
            boxWidth = box.width,
            boxHeight = box.height,
            pageWidth = pageWidth,
        )
    }

    /**
     * Sends only the bubbles that produced text, then scatters the results back to their original
     * indices so blank bubbles stay blank instead of shifting every later translation up by one.
     */
    private suspend fun translateFromOcr(
        provider: TranslationProvider,
        boxes: List<BubbleBox>,
        recognized: List<BubbleText>,
        translationContext: TranslationContext,
    ): List<BubbleTranslation> {
        val blank = BubbleTranslation("", "")
        val indexed = recognized.withIndex().filter { it.value.text.isNotBlank() }
        if (indexed.isEmpty()) return List(boxes.size) { blank }

        val translated = provider.translateLines(indexed.map { it.value.text }, translationContext)
        val result = MutableList(boxes.size) { blank }
        indexed.forEachIndexed { position, entry ->
            // The provider was given the OCR text, so that is the source for this slot regardless of
            // what it echoed back.
            result[entry.index] = translated.getOrNull(position)
                ?.copy(source = entry.value.text)
                ?: blank
        }
        return result
    }

    /**
     * Puts each translation in the bubble whose text it actually corresponds to.
     *
     * Ids make placement deterministic, but they cannot make the model's *reading* correct: on a
     * webtoon strip twenty thousand pixels tall, given a downscaled page and a list of coordinates, it
     * will sometimes attach the right translation to the wrong id. What the reader then sees is one
     * character speaking another's line — indistinguishable from a bad translation, and worse, because
     * the dialogue no longer follows.
     *
     * On-device OCR is the arbiter. It is run per bubble on a native-resolution crop, so its text is
     * bound to the correct box by construction; the model's echoed source text can therefore be
     * matched against it. Where a permutation agrees with OCR better than the model's own ordering
     * did, that permutation is the truth.
     *
     * Deliberately conservative: a swap has to be a clear improvement on both entries involved before
     * it is taken, because OCR on stylised lettering is itself imperfect and a wrong "correction"
     * would introduce exactly the fault it is meant to remove. When in doubt nothing moves.
     */
    private fun realignToOcr(
        answered: List<BubbleTranslation>,
        recognized: List<BubbleText>,
    ): List<BubbleTranslation> {
        val current = answered
        if (answered.size < 2 || answered.size != recognized.size) return current
        // Nothing to match against if the provider did not echo what it read.
        if (answered.none { it.source.isNotBlank() }) return current

        val ocr = recognized.map { normalizeForMatch(it.text) }
        val said = answered.map { normalizeForMatch(it.source) }

        val order = IntArray(answered.size) { it }
        var moved = 0

        // Pairwise swaps only. A full assignment solve would chase permutations that similarity this
        // noisy cannot justify; almost every real failure is two bubbles trading places.
        for (i in answered.indices) {
            for (j in i + 1 until answered.size) {
                val a = order[i]
                val b = order[j]
                if (said[a].isBlank() || said[b].isBlank()) continue
                if (ocr[i].isBlank() || ocr[j].isBlank()) continue

                val keep = similarity(said[a], ocr[i]) + similarity(said[b], ocr[j])
                val swap = similarity(said[b], ocr[i]) + similarity(said[a], ocr[j])
                if (swap < keep + SWAP_MARGIN) continue
                if (similarity(said[b], ocr[i]) < MIN_MATCH || similarity(said[a], ocr[j]) < MIN_MATCH) continue

                order[i] = b
                order[j] = a
                moved++
            }
        }

        if (moved > 0) {
            logcat { "Re-keyed $moved bubble translation(s) to match on-device OCR" }
        }

        // Second pass: relocation. A swap can only fix two bubbles that traded places; on pages with
        // non-dialogue lettering (credits, promos, logos) the model produces longer shifts — bubble A
        // wearing B's text wearing C's. Any translation whose echoed source clearly disagrees with the
        // OCR of the bubble it sits in is misplaced; if its source matches exactly one other bubble
        // strongly, and that bubble's own entry is itself misplaced or empty, it moves there. When no
        // such home exists the translation is dropped outright: wrong dialogue in a bubble reads as a
        // worse defect than an untranslated bubble.
        val translations = MutableList(order.size) { answered[order[it]] }
        val sourceAt = List(order.size) { said[order[it]] }
        // OCR shorter than a few characters is not evidence — a lone misread glyph must not get a
        // real translation dropped on its account.
        fun misplacedAt(i: Int): Boolean =
            sourceAt[i].isNotBlank() && ocr[i].length >= MIN_OCR_EVIDENCE &&
                similarity(sourceAt[i], ocr[i]) < DROP_BELOW

        val result = translations.toMutableList()
        val claimed = mutableSetOf<Int>()
        var relocated = 0
        var dropped = 0
        for (i in order.indices) {
            if (translations[i].translation.isBlank() || !misplacedAt(i)) continue
            val home = order.indices
                .filter { it != i && it !in claimed && ocr[it].isNotBlank() }
                .maxByOrNull { similarity(sourceAt[i], ocr[it]) }
            val score = home?.let { similarity(sourceAt[i], ocr[it]) } ?: 0f
            if (home != null && score >= RELOCATE_MIN && (sourceAt[home].isBlank() || misplacedAt(home))) {
                result[home] = translations[i]
                claimed += home
                relocated++
            } else {
                dropped++
            }
            if (i !in claimed) result[i] = BubbleTranslation("", "")
        }
        if (relocated > 0 || dropped > 0) {
            logcat { "Relocated $relocated and dropped $dropped misplaced translation(s)" }
        }
        return result
    }

    /**
     * Symmetric similarity in 0..1. Word overlap when both sides have enough words to make that
     * meaningful; character-bigram Dice otherwise, which is what makes CJK strings — no spaces, so
     * "one word" however long — comparable at all.
     */
    private fun similarity(a: String, b: String): Float {
        val left = a.split(' ').filter { it.length > 1 }.toSet()
        val right = b.split(' ').filter { it.length > 1 }.toSet()
        if (left.size >= 2 && right.size >= 2) {
            val shared = left.count { it in right }
            return 2f * shared / (left.size + right.size)
        }
        return bigramDice(a.replace(" ", ""), b.replace(" ", ""))
    }

    private fun bigramDice(a: String, b: String): Float {
        if (a.length < 2 || b.length < 2) return if (a.isNotEmpty() && a == b) 1f else 0f
        val left = HashSet<Int>(a.length)
        val right = HashSet<Int>(b.length)
        for (i in 0 until a.length - 1) left += a[i].code * 0x10000 + a[i + 1].code
        for (i in 0 until b.length - 1) right += b[i].code * 0x10000 + b[i + 1].code
        val shared = left.count { it in right }
        return 2f * shared / (left.size + right.size)
    }

    /**
     * True when the "translation" is just the source text handed back.
     *
     * Only applied to short strings. A long line that survives translation unchanged does not happen
     * in practice, whereas a legitimately identical rendering of a short one does — a name, a number,
     * an interjection that reads the same in both languages — and dropping those would leave real
     * dialogue untranslated.
     */
    private fun isUntranslated(source: String, translated: String): Boolean {
        if (source.isBlank()) return false
        val a = normalizeForMatch(source)
        val b = normalizeForMatch(translated)
        return a == b && b.length <= MAX_ECHO_LENGTH
    }

    /**
     * True when [text] is written in the script the reader asked for — or close enough. Latin-target
     * translations that come back mostly CJK are the model refusing (or failing) to translate, and
     * must never reach the renderer.
     */
    private fun plausibleForTarget(text: String, targetLanguage: String): Boolean {
        if (targetLanguage in CJK_TARGETS) return true
        var letters = 0
        var cjk = 0
        for (ch in text) {
            if (!Character.isLetter(ch)) continue
            letters++
            val cp = ch.code
            if (cp in 0x1100..0x11FF || // Hangul jamo
                cp in 0x3040..0x30FF || // Hiragana + katakana
                cp in 0x3400..0x9FFF || // Han
                cp in 0xAC00..0xD7AF || // Hangul syllables
                cp in 0xF900..0xFAFF // Han compatibility
            ) {
                cjk++
            }
        }
        return letters == 0 || cjk * 10 < letters * 3
    }

    private fun normalizeForMatch(text: String): String =
        text.lowercase().replace(NON_WORD, " ").replace(WHITESPACE, " ").trim()

    /**
     * Orders bubbles the way they are read: down the page, then across each band.
     *
     * The detector emits boxes in descending confidence, which is effectively random on the page. The
     * prompt asks the model to read them "in the listed order", so handing it a scrambled list makes
     * it work against the natural flow of the dialogue and invites it to re-order the reply to match
     * what it sees — the exact thing that used to shift translations into neighbouring bubbles.
     *
     * Boxes within one band are treated as the same row so a left-hand bubble sitting a few pixels
     * lower than its neighbour does not jump ahead of it.
     */
    private fun List<BubbleBox>.inReadingOrder(): List<BubbleBox> {
        if (size < 2) return this
        val band = maxOf(MIN_READING_BAND, (sumOf { it.bottom - it.top } / size) / 2)
        return sortedWith(compareBy({ it.top / band }, { it.left }))
    }

    /**
     * A bubble mostly cropped off by the page edge — webtoon strips routinely cut a bubble in half at
     * a page boundary, and its other half lives on the neighbouring page.
     *
     * The visible sliver is dropped before the provider ever sees it. It has no readable text of its
     * own, so the model invents some from context, and the renderer then letters that invention into
     * a strip a few dozen pixels wide: the reader sees clipped orphan glyphs hanging on the page edge.
     */
    private fun BubbleBox.isEdgeSliver(pageWidth: Int): Boolean {
        val touchesEdge = left <= EDGE_SLOP || right >= pageWidth - EDGE_SLOP
        return touchesEdge && width < maxOf(SLIVER_MIN_WIDTH, (pageWidth * SLIVER_WIDTH_RATIO).toInt())
    }

    fun currentProvider(): TranslationProvider = TranslationProviders.current(preferences)

    fun close() {
        runCatching { detector.close() }
        runCatching { recognizer.close() }
    }

    private companion object {
        /** Share of a page-pass block that must sit inside a bubble before its read is reused. */
        const val REUSED_BLOCK_CONTAINMENT = 0.7f

        const val CAPTION_ROW_OVERLAP = 0.45f
        const val CAPTION_MAX_GAP_HEIGHTS = 2
        const val CAPTION_CONTAINMENT = 0.65f


        /** Floor for the row-banding height, so a page of tiny boxes still bands sensibly. */
        const val MIN_READING_BAND = 24

        /** How much better a swap must score before it is trusted over the model's own ordering. */
        const val SWAP_MARGIN = 0.30f
        /** Neither half of a swap may be a weak match, however good the pair looks together. */
        const val MIN_MATCH = 0.34f
        /** Below this, a translation's echoed source plainly is not what its bubble says. */
        const val DROP_BELOW = 0.15f
        /** Normalised OCR text shorter than this cannot convict a translation of being misplaced. */
        const val MIN_OCR_EVIDENCE = 4
        /** A relocation target must match this strongly before a translation is moved into it. */
        const val RELOCATE_MIN = 0.50f
        /** Targets whose native scripts are CJK; the script sanity check does not apply to them. */
        val CJK_TARGETS = setOf("zh", "ja", "ko")
        /** Longest echoed string still treated as "the provider gave up" rather than a real match. */
        const val MAX_ECHO_LENGTH = 24

        /** Boxes narrower than this fraction of the page that hug its edge are cropped remnants. */
        const val SLIVER_WIDTH_RATIO = 0.10f
        const val SLIVER_MIN_WIDTH = 48
        const val EDGE_SLOP = 2

        /** Avoid turning a page full of false detector boxes into an unbounded second request. */
        const val MAX_FOCUSED_VISION_BOXES = 12

        val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        val WHITESPACE = Regex("\\s+")
    }
}
