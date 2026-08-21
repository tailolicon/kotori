package mihon.feature.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.feature.translation.detect.BubbleDetector
import mihon.feature.translation.detect.LowConfidenceSpeechGuard
import mihon.feature.translation.detect.OversizedSpeechRefiner
import mihon.feature.translation.detect.SimplePageReader
import mihon.feature.translation.detect.TextBlockDetector
import mihon.feature.translation.manga.MangaPageTranslator
import mihon.feature.translation.manga.MangaPipeline
import mihon.feature.translation.detect.BalloonTrustGuard
import mihon.feature.translation.model.BubbleBox
import mihon.feature.translation.model.BubbleText
import mihon.feature.translation.model.TextLineBox
import mihon.feature.translation.model.TranslatedBubble
import mihon.feature.translation.ocr.BubbleTextRecognizer
import mihon.feature.translation.provider.BubbleTranslation
import mihon.feature.translation.provider.ProviderRateLimited
import mihon.feature.translation.provider.ProviderRejected
import mihon.feature.translation.provider.TranslationContext
import mihon.feature.translation.provider.TranslationProvider
import mihon.feature.translation.provider.TranslationProviders
import mihon.feature.translation.render.BubbleFill
import mihon.feature.translation.render.BubbleRenderer
import mihon.feature.translation.render.PaperBalloonFinder
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.cancellation.CancellationException

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
    private val simpleReader by lazy { SimplePageReader() }
    private val recognizer by lazy { BubbleTextRecognizer(context) }
    private val renderer by lazy { BubbleRenderer(context) }
    private val mangaTranslator by lazy {
        MangaPageTranslator(context, localWork, simpleReader, renderer) { preferences.font.get() }
    }

    /**
     * Guards the on-device stages only: the ONNX detector session and the ML Kit recogniser
     * are each single-lane, and running two pages through them at once corrupts or blocks.
     * The provider call sits outside it on purpose — see [translate].
     */
    private val localWork = Mutex()

    /**
     * What script the series being read is written in, once a page has established it.
     *
     * Held as the preference itself rather than a copy so it survives the reader being closed: a
     * series is read over days, and re-probing on every session is the cost this exists to avoid.
     */
    @Volatile private var seriesScript: tachiyomi.core.common.preference.Preference<String>? = null

    /** The reader moved to another series; its own remembered script applies from here. */
    fun beginSeries(script: tachiyomi.core.common.preference.Preference<String>) {
        seriesScript = script
    }

    /** Thrown when the page simply has no dialogue to translate — not an error worth surfacing. */
    class NothingToTranslate : Exception("No speech bubbles found")

    /**
     * The recogniser returned nothing at all, which is a failure and not an answer.
     *
     * These are different facts and the pipeline used to record them identically. "I read the page
     * and found no dialogue" earns a permanent marker so the page is never paid for twice. "I read
     * the page and got zero lines back" is the recogniser failing — on one emulator ML Kit returns
     * nothing for whole chapters of halftone manga that a phone reads 41 lines from, same image and
     * same build. Writing the permanent marker for that told the reader "this chapter has no text"
     * forever, and the only cure was clearing the cache by hand.
     */
    class PageUnreadable : Exception("The page reader returned no text at all")

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
        // Deliberately not `preferences.sourceLanguage`. A comic page carries its own script and
        // both readers below can see it — the vision providers detect it in the same call that
        // translates, and [SimplePageReader] probes a band before it commits. A stored language is
        // only ever the *previous* series' answer, which is how a Korean series went on being read
        // as Japanese. (Light novels are different: there is no page to look at, so
        // [mihon.feature.translation.novel.NovelTranslator] still reads the preference.)
        var translationContext = TranslationContext(
            sourceLanguage = TranslationContext.AUTO,
            targetLanguage = preferences.targetLanguage.get(),
            styleHint = preferences.styleHint.get(),
        )

        // Routing asks the page, and asks it twice: is this the shape of a bound page, and is it
        // written in Japanese. Shape alone is not enough — a webtoon slice is 676x952 and a manga
        // page is 900x1300 — and getting that wrong put webtoon credits pages through the balloon
        // filler, which merged their columns into one grey block.
        //
        // The script comes from what this series has already been found to be written in, and is
        // probed only when nothing is known yet. Probing every page cost three extra recogniser
        // loads per page and, worse, could answer from bands that happened to hold no text.
        val remembered = seriesScript?.get().orEmpty()
        val mayBeManga = MangaPipeline.mayHandle(
            source.width,
            source.height,
            currentProvider().displayName,
            currentProvider().supportsVisionOcr,
        )
        val pageScript = when {
            remembered.isNotBlank() -> remembered
            !mayBeManga -> ""
            else -> localWork.withLock {
                runCatching { simpleReader.probeScript(source) }.getOrDefault("")
            }.also { probed -> if (probed.isNotBlank()) seriesScript?.set(probed) }
        }
        val useMangaPort = MangaPipeline.shouldHandle(
            source.width,
            source.height,
            pageScript,
            currentProvider().displayName,
            currentProvider().supportsVisionOcr,
        )
        // Everything that can make the same chapter come out differently on two devices, on one
        // line. Settings are per install, the cache key is derived from them, and the model can step
        // down when a quota runs out — so "it looks different on my phone" is answerable by
        // comparing this line rather than by guessing.
        logcat {
            "$diagnosticLabel pipeline: ${if (useMangaPort) "manga port" else "original path"} " +
                "(${source.width}x${source.height}, script='${pageScript.ifEmpty { "not probed" }}', " +
                "style=${preferences.renderStyle()}, model=${currentProvider().displayName}, " +
                "target=${preferences.targetLanguage.get()}, stamp=${preferences.outputStamp()})"
        }
        if (useMangaPort) {
            val outcome = try {
                mangaTranslator.translate(source, translationContext, currentProvider(), diagnosticLabel)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProviderRateLimited) {
                throw error
            } catch (error: ProviderRejected) {
                throw error
            } catch (error: Exception) {
                logcat { "$diagnosticLabel manga pipeline failed: ${error.message}; using original path" }
                MangaPageTranslator.Outcome.NotHandled
            }
            when (outcome) {
                is MangaPageTranslator.Outcome.Rendered -> return@withIOContext outcome.bitmap
                // The balloons were found and read: "nothing here" is an answer, not a gap to be
                // filled by running the whole original pipeline over the same page for a second
                // opinion it would pay for in another provider call.
                MangaPageTranslator.Outcome.NoDialogue -> throw NothingToTranslate()
                MangaPageTranslator.Outcome.NotHandled -> Unit
            }
        }

        val startedAt = System.currentTimeMillis()
        var lockedAt = startedAt
        var localDoneAt = startedAt
        // What the page turned out to need. Set once the page has been read; the renderer is never
        // handed AUTO, because by then the question has an answer.
        var resolvedStyle = preferences.renderStyle()

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

            // Simple lettering needs two facts — where the dialogue is and what it says — and reading
            // the page once produces both. The three stages below produce them too, at four times the
            // cost: a bubble model proposes balloons, ML Kit reads the page to find the lettering the
            // model cannot see, and then every proposed region is cropped and read *again*. On the
            // strip this was measured against that came to 10.6s + 38.4s + 79.5s, most of it spent
            // re-reading text the page pass had already read correctly.
            val renderStyle = preferences.renderStyle()
            if (renderStyle != TranslationRenderStyle.BUBBLE) {
                // The series' own script, when it has one. Blank falls through to the reader's
                // probe, whose answer is remembered below so the next page pays nothing.
                val read = simpleReader.read(source, remembered.ifBlank { translationContext.sourceLanguage })
                val pageFormat = PageFormatDetector.detect(
                    source.width,
                    source.height,
                    ScriptKindDetector.ofLanguage(read.language),
                )
                detectDoneAt = System.currentTimeMillis()
                blocksDoneAt = detectDoneAt
                // Remember the script only when the page actually read something. A page that is
                // pure artwork returns the fallback, and writing that down would pin a whole
                // Japanese series to the Latin recogniser on the evidence of one wordless page.
                val readCharacters = read.texts.sumOf { text -> text.text.count { it.isLetterOrDigit() } }
                if (
                    read.language.isNotBlank() &&
                    read.language != remembered &&
                    readCharacters >= MIN_CHARACTERS_TO_TRUST_SCRIPT
                ) {
                    logcat { "This series reads as '${read.language}' ($readCharacters chars); remembering it" }
                    seriesScript?.set(read.language)
                }
                read.language
                    .takeIf { it != translationContext.sourceLanguage }
                    ?.let { pageLanguage ->
                        translationContext = translationContext.copy(sourceLanguage = pageLanguage)
                    }
                val usable = read.boxes.indices.filterNot { index ->
                    read.boxes[index].isEdgeSliver(source.width) ||
                        isDecorativeDisplayText(read.boxes[index], read.texts[index], source.width)
                }
                read.boxes.indices.filterNot(usable::contains).forEach { index ->
                    logcat { "Dropped ${read.boxes[index]} as an edge sliver or decoration: ${read.texts[index].text}" }
                }
                var blocks = usable.map(read.boxes::get)
                var blockTexts = usable.map(read.texts::get)

                // Japanese *manga* decides how this page is set, not a setting. Vertical columns
                // give a recognised footprint that is a tall sliver, and a sentence written into a
                // sliver comes out one or two words per row — translated and unreadable. The balloon
                // around it has the room. A reader should not have to know that, so the page says so.
                //
                // Deliberately gated on the page being a page. Flooding balloons on a colour webtoon
                // is how neon ovals used to get painted over and how a dark credits panel becomes a
                // slab, so a strip that merely happens to read as Japanese must not trigger it.
                val typeset = when (renderStyle) {
                    TranslationRenderStyle.TYPESET -> true
                    TranslationRenderStyle.SIMPLE -> false
                    else -> read.language == "ja" && pageFormat == PageFormat.MANGA
                }
                resolvedStyle = if (typeset) {
                    TranslationRenderStyle.TYPESET
                } else {
                    TranslationRenderStyle.SIMPLE
                }

                // Where the lettering *may go* is a different question from where it is, and only the
                // balloon answers it — for vertical Japanese, whose recognised footprint is a sliver
                // no sentence fits into. That is the only reason this runs, and it is expensive: the
                // ONNX detector was 10.6s of the original 136s page, and this whole mode exists
                // because reading the page once got that page to 4.3s by skipping it.
                //
                // It also does a second job that a strip very much needs, and skipping it here for
                // speed was a mistake that shipped: it is what tells the reader that two recognised
                // fragments belong to *one* balloon. ML Kit routinely splits a Korean balloon into an
                // upper and a lower region; without a balloon to join them each fragment is erased
                // and lettered on its own, so half the bubble keeps its Korean and the translation
                // written into the lower half spills out of the bubble. That is what "ô thoại có
                // phần tô trắng chèn thẳng ra ngoài" is.
                //
                // So it runs wherever there is lettering to group — which is everywhere — and the
                // speed comes from the passes that were genuinely redundant instead: the page is
                // read once, the script is probed once per series, and strips no longer queue.
                val needsBalloons = true
                val balloons = if (!needsBalloons) {
                    logcat { "$diagnosticLabel skipping the balloon detector: $pageFormat lettered in place" }
                    emptyList()
                } else {
                    suppressOverlaps(
                        runCatching { detector.detect(source, horizontalSeams) }
                            .onFailure { logcat { "Bubble detection failed: ${it.message}" } }
                            .getOrDefault(emptyList())
                            .filterNot { it.isEdgeSliver(source.width) },
                    )
                }

                // Read the page once, but do not accept "once" as the last word on a balloon the page
                // pass got nothing usable out of. Reading a whole page in bands is what makes this
                // pipeline thirty times faster than cropping and reading every region separately, and
                // it costs exactly this: a balloon whose lettering the band pass misreads is simply
                // lost, where the old per-region path would have rescued it by cropping tight,
                // enlarging and raising contrast. On the page that exposed it, four lines of dialogue
                // came back as "iSNOH ONo)" - mirrored fragments of one word - which the decoration
                // guard then correctly discarded, leaving the balloon in its source language.
                //
                // So: keep the single pass, and pay the old cost only for the balloons that need it.
                val unread = balloons
                    .filter { it.confidence >= MIN_RESCUE_CONFIDENCE }
                    .filter { balloon ->
                        blocks.withIndex().none { (index, block) ->
                            containedFraction(block, balloon) >= MIN_BALLOON_CONTAINMENT &&
                                blockTexts[index].text.isNotBlank()
                        }
                    }
                    .sortedByDescending { it.confidence }
                    .take(MAX_RESCUE_BALLOONS)
                if (unread.isNotEmpty()) {
                    logcat { "Re-reading ${unread.size} balloon(s) the page pass could not read" }
                    val rescued = runCatching {
                        recognizer.recognize(source, unread, translationContext.sourceLanguage)
                    }
                        .onFailure { logcat { "Balloon re-read failed: ${it.message}" } }
                        .getOrDefault(emptyList())
                    // Accept a recovery only where nothing is being lettered already. A status panel
                    // is covered by several overlapping detections, each of which re-reads a
                    // different fragment of it — "NATIONAL", "LEADE", "MILLION" — and lettering all
                    // of them puts three half-sentences on top of each other across the panel. One
                    // region per piece of page, and the first one wins.
                    val taken = blocks.toMutableList()
                    val recovered = unread.indices.filter { index ->
                        val text = rescued.getOrNull(index)?.text.orEmpty()
                        if (text.isBlank()) return@filter false
                        if (isDecorativeDisplayText(unread[index], rescued[index], source.width)) {
                            return@filter false
                        }
                        // A detection much narrower than the page is not a balloon the reader was
                        // meant to read; it is a scrap of one. Re-reading those recovered fragments
                        // of a status panel — "NATIONAL / LEADE", "MILLON / ON" — and each fragment
                        // was then lettered into its own thin strip, four half-sentences scattered
                        // across the panel. Every recovery worth keeping so far has been at least a
                        // third of the page wide.
                        if (PageFormatDetector.refuseNarrowRescue(
                                pageFormat,
                                unread[index].width,
                                source.width,
                            )
                        ) {
                            return@filter false
                        }
                        val clashes = taken.any { containedFraction(unread[index], it) >= MAX_RESCUE_OVERLAP }
                        if (!clashes) taken += unread[index]
                        !clashes
                    }
                    recovered.forEach { index ->
                        logcat { "Recovered ${unread[index].toRect()}: ${rescued[index].text.lines().joinToString(" / ")}" }
                    }
                    blocks = blocks + recovered.map(unread::get)
                    blockTexts = blockTexts + recovered.map(rescued::get)
                }

                if (blocks.isEmpty()) {
                    // Nothing recognised at all is a failed read; nothing *kept* is a real answer.
                    if (read.texts.isNotEmpty()) throw NothingToTranslate()

                    // The recogniser can come back empty on a page that plainly is not: measured on
                    // one emulator, ML Kit returns zero lines for halftone manga that a phone reads
                    // 41 lines from — same image, same build. The balloon model does not use ML Kit
                    // and is unaffected, and a vision provider reads the artwork itself, so between
                    // them the page is still translatable. Only the erase footprint is lost, and a
                    // balloon is a footprint.
                    if (balloons.isEmpty() || !currentProvider().supportsVisionOcr) throw PageUnreadable()
                    logcat {
                        "$diagnosticLabel read nothing; falling back to ${balloons.size} detected " +
                            "balloon(s) and letting ${currentProvider().displayName} read them"
                    }
                    blocks = balloons
                    blockTexts = balloons.map { BubbleText("", emptyList()) }
                }
                // Manga needs the balloon interior: vertical columns are slivers. Webtoon
                // lettering already sits in a readable footprint; typeset fill on colour
                // strips is how neon ovals used to get painted over. Picking TYPESET still
                // forces that path on a strip.
                var placed = placeInBalloons(blocks, blockTexts, balloons, preferBalloon = typeset)
                if (typeset) {
                    placed = recoverPaperBalloons(source, placed.first, placed.second)
                }
                placed = rereadMismatchedScripts(source, placed.first, placed.second, translationContext.sourceLanguage)
                if (translationContext.sourceLanguage == "ja") {
                    placed = inJapaneseReadingOrder(placed.first, placed.second)
                }
                localDoneAt = System.currentTimeMillis()
                return@withLock placed
            }

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

            val ordered = (refinedDetected + extras)
                .filterNot { it.isEdgeSliver(source.width) }
                .inReadingOrder(rightToLeft = translationContext.sourceLanguage == "ja")
            if (ordered.size < refinedDetected.size + extras.size) {
                logcat { "Dropped ${refinedDetected.size + extras.size - ordered.size} edge-sliver box(es)" }
            }
            if (ordered.isEmpty()) {
                if (detected.isEmpty() && extras.isEmpty()) throw PageUnreadable()
                throw NothingToTranslate()
            }
            logcat {
                "Detected ${ordered.size} regions (${refinedDetected.size} bubbles + ${extras.size} text " +
                    "blocks) on ${source.width}x${source.height} page"
            }
            // Glyph geometry is collected regardless of who does the reading, because the renderer
            // needs it to mask strokes rather than whole bubbles. It is cheap and fully local.
            val read = recognizer.recognize(source, ordered, translationContext.sourceLanguage)
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
            bubbleText.copy(
                text = ShortDialogueNormalizer.normalize(JapaneseOcrCleaner.clean(bubbleText.text)),
            )
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
            if (NoisyVocalizationGuard.shouldLeaveUntouched(ocrText) || JapaneseSfxGuard.shouldDrop(ocrText)) {
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
            if (isOnlyPunctuation(translated) && !isOnlyPunctuation(ocrText)) {
                // "HEY!!" comes back as "!!", "STOP..." as "...", "SEE?" as "?" — the model drops
                // the interjection and returns the punctuation that followed it. Erasing the
                // hand-drawn word to stamp a bare "!!" in its place empties the balloon, which is
                // what a whole chapter of these looks like: page after page of bubbles holding one
                // ellipsis. Nothing to write means nothing to erase.
                logcat { "Dropping punctuation-only translation for bubble ${index + 1}" }
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
        val rendered = renderer.render(
            source,
            bubbles,
            preferences.font.get(),
            horizontalSeams,
            style = resolvedStyle,
        )
        // Stage timings, because "translation is slow" is not actionable without them: waiting for
        // the single-lane local stages, running them, waiting on the network, and drawing are four
        // very different problems with four different fixes.
        logcat {
            // Stage names as the *simple* path actually uses them: it reads the page first and does
            // everything else after, so calling the read "detect" and the rest "ocr" — which is what
            // the bubble-aware path does — reads as though the ONNX detector were taking twenty
            // seconds when it is the page read.
            "$diagnosticLabel timing: queue=${lockedAt - startedAt}ms " +
                "read=${detectDoneAt - lockedAt}ms blocks=${blocksDoneAt - detectDoneAt}ms " +
                "detect+place=${localDoneAt - blocksDoneAt}ms provider=${providerDoneAt - localDoneAt}ms " +
                "render=${System.currentTimeMillis() - providerDoneAt}ms " +
                "total=${System.currentTimeMillis() - startedAt}ms"
        }
        rendered
    }

    /**
     * Hands each block of lettering the balloon it sits in, and joins the blocks that share one.
     *
     * Two things come out of this. The renderer gets a region it may letter *into* — a balloon has
     * room for a sentence where the source text's own footprint may not, which is the whole of the
     * vertical-Japanese problem. And a balloon read as two or three blocks becomes one translation
     * unit, so a sentence is not cut into fragments that are then set at their own sizes.
     *
     * Lettering no balloon claims — a caption over artwork, a sign, a status panel — keeps its own
     * bounds and the renderer keeps erasing it stroke by stroke, because painting a block over open
     * artwork is the defect that mode exists to avoid.
     */
    private fun placeInBalloons(
        regions: List<BubbleBox>,
        texts: List<BubbleText>,
        balloons: List<BubbleBox>,
        preferBalloon: Boolean = false,
    ): Pair<List<BubbleBox>, List<BubbleText>> {
        if (balloons.isEmpty()) return regions to texts

        // Smallest balloon that holds most of the block, so nested detections pick the tight one.
        val host = regions.map { region ->
            balloons
                .filter { containedFraction(region, it) >= MIN_BALLOON_CONTAINMENT }
                .minByOrNull { it.width.toLong() * it.height }
        }

        val outBoxes = ArrayList<BubbleBox>(regions.size)
        val outTexts = ArrayList<BubbleText>(regions.size)
        val done = HashSet<BubbleBox>()
        regions.indices.forEach { index ->
            val balloon = host[index]
            if (balloon == null) {
                val best = balloons.maxOfOrNull { containedFraction(regions[index], it) } ?: 0f
                logcat { "No balloon for ${regions[index].toRect()}: best containment ${(best * 100).toInt()}%" }
                outBoxes += regions[index]
                outTexts += texts[index]
                return@forEach
            }
            if (!done.add(balloon)) return@forEach
            val shared = regions.indices.filter { host[it] === balloon }

            // Is this detection a balloon at all? A box that fails either test below is a panel the
            // model mislabelled, and then it may not be used as the box *or* as evidence that the
            // lettering inside it belongs together. Splitting that decision is what set a sentence
            // across the artwork: the box was rejected as oversized, the join was allowed anyway,
            // and a bubble at x=628 became one unit with a "NO..." at x=16 — so the renderer, which
            // sizes type to the lettering it is handed, set it 716px wide for a region 112px wide.
            // Either the balloon is trusted for both decisions, or for neither.
            val claimed = shared.map { regions[it] }
            if (!BalloonTrustGuard.isBelievable(balloon, claimed)) {
                logcat { "Balloon ${balloon.toRect()} is not one; keeping ${claimed.size} region(s) apart" }
                shared.forEach { outBoxes += regions[it]; outTexts += texts[it] }
                return@forEach
            }
            val hull = claimed.reduce(BalloonTrustGuard::hullOf)

            val merged = BubbleText(
                text = shared.joinToString("\n") { texts[it].text }.trim(),
                lines = shared.flatMap { texts[it].lines },
            )
            // Hand the renderer the balloon only where the lettering's own footprint cannot hold a
            // translation: columns of vertical Japanese, where that footprint is a sliver a few
            // characters wide and a horizontal sentence written into it comes out one word per row.
            //
            // Horizontal lettering does not have that problem, and giving it the balloon instead
            // makes the page worse, not better: the type is set to fill a box larger than the words
            // it replaces, and the erase reaches the balloon's own outline. On the page that showed
            // it, a balloon lost its outline entirely and its last line ran off the panel below.
            val vertical = merged.lines.isNotEmpty() &&
                merged.lines.count { it.rect.height() > it.rect.width() * VERTICAL_ASPECT } * 2 >
                merged.lines.size
            outBoxes += if (vertical || preferBalloon) balloon else hull
            outTexts += merged
        }
        val joined = regions.size - outBoxes.size
        logcat {
            "Grouped ${outBoxes.size} region(s) by balloon" +
                if (joined > 0) ", joining $joined that shared one" else ""
        }
        return outBoxes to outTexts
    }

    /**
     * Drops detections that name a balloon another, more confident detection already names.
     *
     * The model routinely returns four or five boxes around one balloon, each a slightly different
     * crop of it. Left alone they cost real time — every one of them is re-read when the page pass
     * missed that balloon — and they cost correctness: the same sentence comes back five times, in
     * five slightly clipped readings, and the renderer letters all five on top of each other.
     *
     * Intersection over union at [BALLOON_IOU], the same threshold the reference implementation uses.
     */
    private fun suppressOverlaps(boxes: List<BubbleBox>): List<BubbleBox> {
        if (boxes.size < 2) return boxes
        val kept = ArrayList<BubbleBox>(boxes.size)
        for (box in boxes.sortedByDescending { it.confidence }) {
            // Overlap alone misses the common shape of a duplicate: a small box sitting wholly
            // inside a larger one scores a low IoU precisely because it is small, so both survived
            // and the same banner was read and lettered twice — once whole, once as "S EN: /
            // ANLATION.COM". Anything almost entirely inside a box already kept is that box again.
            val duplicate = kept.any { other ->
                intersectionOverUnion(other, box) >= BALLOON_IOU ||
                    containedFraction(box, other) >= BALLOON_CONTAINED
            }
            if (!duplicate) kept += box
        }
        if (kept.size < boxes.size) {
            logcat { "Merged ${boxes.size - kept.size} overlapping balloon detection(s)" }
        }
        return kept
    }

    private fun intersectionOverUnion(a: BubbleBox, b: BubbleBox): Float {
        val overlap = Rect(a.toRect())
        if (!overlap.intersect(b.toRect())) return 0f
        val intersection = overlap.width().toLong() * overlap.height()
        val union = a.width.toLong() * a.height + b.width.toLong() * b.height - intersection
        return if (union <= 0) 0f else intersection.toFloat() / union
    }

    /** Fraction of [inner] that lies inside [outer]. */
    private fun containedFraction(inner: BubbleBox, outer: BubbleBox): Float {
        val overlap = Rect(inner.toRect())
        if (!overlap.intersect(outer.toRect())) return 0f
        val area = inner.width.toLong() * inner.height
        if (area <= 0) return 0f
        return (overlap.width().toLong() * overlap.height()).toFloat() / area
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
        // real translation dropped on its account — but it is not *no* evidence either. A bubble the
        // model says it read as "haaah that was good that was good" cannot be the bubble the
        // recogniser read as "rec": an echo that long which does not contain even the little that
        // was read is an echo of some other bubble. Left unchecked, that is how a panel captioned
        // REC came out saying "sướng quá đi".
        fun misplacedAt(i: Int): Boolean {
            val source = sourceAt[i]
            if (source.isBlank() || ocr[i].isBlank()) return false
            if (ocr[i].length >= MIN_OCR_EVIDENCE) return similarity(source, ocr[i]) < DROP_BELOW
            return ShortOcrEchoGuard.contradicts(ocr[i], source)
        }

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
    /** True when nothing in [text] is a letter or a digit — punctuation, spaces and marks only. */
    private fun isOnlyPunctuation(text: String): Boolean =
        text.isNotBlank() && text.none { it.isLetterOrDigit() }

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
    private fun List<BubbleBox>.inReadingOrder(rightToLeft: Boolean = false): List<BubbleBox> {
        if (size < 2) return this
        val band = maxOf(MIN_READING_BAND, (sumOf { it.bottom - it.top } / size) / 2)
        return if (rightToLeft) {
            sortedWith(compareBy({ it.top / band }, { -it.left }))
        } else {
            sortedWith(compareBy({ it.top / band }, { it.left }))
        }
    }

    /**
     * Japanese manga is read right-to-left within a row of panels. Reordering here is what keeps
     * the vision prompt and the translator walking the page the way a reader does.
     */
    private fun inJapaneseReadingOrder(
        boxes: List<BubbleBox>,
        texts: List<BubbleText>,
    ): Pair<List<BubbleBox>, List<BubbleText>> {
        if (boxes.size < 2) return boxes to texts
        val order = boxes.indices.toList().let { indices ->
            val band = maxOf(MIN_READING_BAND, (boxes.sumOf { it.bottom - it.top } / boxes.size) / 2)
            indices.sortedWith(compareBy({ boxes[it].top / band }, { -boxes[it].left }))
        }
        return order.map(boxes::get) to order.map(texts::get)
    }

    /**
     * Expands a text-block box to the paper balloon around it when the detector missed that balloon.
     *
     * Typeset lettering needs the balloon, not the glyph sliver. Growing anisotropically and
     * flooding the paper is how the reference implementation finds a hand-drawn manga balloon that
     * YOLO never saw.
     */
    private fun recoverPaperBalloons(
        source: Bitmap,
        boxes: List<BubbleBox>,
        texts: List<BubbleText>,
    ): Pair<List<BubbleBox>, List<BubbleText>> {
        val recovered = boxes.indices.map { index ->
            val box = boxes[index]
            val text = texts[index]
            if (!box.isTextBlock) return@map box to text
            val lines = text.lines.map { it.rect }
            if (lines.isEmpty()) return@map box to text
            val bounds = Rect(lines[0])
            lines.drop(1).forEach { bounds.union(it) }
            val vertical = PaperBalloonFinder.isVertical(bounds.width(), bounds.height())
            val search = PaperBalloonFinder.searchArea(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                source.width,
                source.height,
                vertical,
            )
            val fill = detectPaperFill(source, search, lines) ?: return@map box to text
            val balloon = BubbleBox(
                left = fill.bounds.left,
                top = fill.bounds.top,
                right = fill.bounds.right,
                bottom = fill.bounds.bottom,
                confidence = box.confidence,
                isTextBlock = false,
            )
            logcat { "Recovered paper balloon ${balloon.toRect()} around ${box.toRect()}" }
            balloon to text
        }
        return mergeOverlappingBalloons(recovered)
    }

    private fun detectPaperFill(
        source: Bitmap,
        search: PaperBalloonFinder.Area,
        lines: List<Rect>,
    ): BubbleFill.Result? {
        val width = search.width
        val height = search.height
        if (width < MIN_REGION_SIDE || height < MIN_REGION_SIDE) return null
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, search.left, search.top, width, height)
        val localSearch = Rect(0, 0, width, height)
        val localLines = lines.map { Rect(it).apply { offset(-search.left, -search.top) } }
        val fill = BubbleFill.detect(pixels, width, height, localSearch, localLines) ?: return null
        fill.bounds.offset(search.left, search.top)
        return fill
    }

    private fun mergeOverlappingBalloons(
        items: List<Pair<BubbleBox, BubbleText>>,
    ): Pair<List<BubbleBox>, List<BubbleText>> {
        if (items.size < 2) return items.map { it.first } to items.map { it.second }
        val used = BooleanArray(items.size)
        val outBoxes = ArrayList<BubbleBox>(items.size)
        val outTexts = ArrayList<BubbleText>(items.size)
        for (index in items.indices) {
            if (used[index]) continue
            var box = items[index].first
            val members = mutableListOf(index)
            var grew = true
            while (grew) {
                grew = false
                for (other in items.indices) {
                    if (used[other] || other in members) continue
                    val candidate = items[other].first
                    val overlap = containedFraction(box, candidate)
                    val reverse = containedFraction(candidate, box)
                    val iou = intersectionOverUnion(box, candidate)
                    val shares = overlap >= MERGE_BALLOON_CONTAINED ||
                        reverse >= MERGE_BALLOON_CONTAINED ||
                        iou >= MERGE_BALLOON_IOU
                    if (!shares) continue
                    val united = unionBox(box, candidate)
                    val unitedArea = united.width.toLong() * united.height
                    val larger = maxOf(
                        box.width.toLong() * box.height,
                        candidate.width.toLong() * candidate.height,
                    )
                    // Two neighbouring balloons that barely touch must not become one panel.
                    if (larger > 0 && unitedArea > larger * 3) continue
                    box = united
                    members += other
                    grew = true
                }
            }
            members.forEach { used[it] = true }
            val merged = BubbleText(
                text = members.joinToString("\n") { items[it].second.text }.trim(),
                lines = members.flatMap { items[it].second.lines },
            )
            outBoxes += box
            outTexts += merged
        }
        val joined = items.size - outBoxes.size
        if (joined > 0) logcat { "Merged $joined region(s) that share a recovered balloon" }
        return outBoxes to outTexts
    }

    private fun unionBox(a: BubbleBox, b: BubbleBox): BubbleBox = a.copy(
        left = minOf(a.left, b.left),
        top = minOf(a.top, b.top),
        right = maxOf(a.right, b.right),
        bottom = maxOf(a.bottom, b.bottom),
        isTextBlock = a.isTextBlock && b.isTextBlock,
    )

    /**
     * A mixed-language page is read once in the page script, then any region that came back as
     * junk — or as a different script — is cropped and read again with the matching recogniser.
     * That is how a Spanish balloon on a Korean page (or the reverse) survives.
     */
    private fun rereadMismatchedScripts(
        source: Bitmap,
        boxes: List<BubbleBox>,
        texts: List<BubbleText>,
        pageLanguage: String,
    ): Pair<List<BubbleBox>, List<BubbleText>> {
        val expected = ScriptKindDetector.ofLanguage(pageLanguage)
        val mismatched = boxes.indices.filter { index ->
            ScriptKindDetector.looksLikeJunk(texts[index].text, expected)
        }
        if (mismatched.isEmpty()) return boxes to texts
        val outTexts = texts.toMutableList()
        val candidates = listOf("ja", "ko", "zh", "en").filter { it != pageLanguage }
        for (index in mismatched) {
            val box = boxes[index]
            var best = outTexts[index]
            var bestKind = ScriptKindDetector.of(best.text)
            for (language in candidates) {
                val reread = runCatching {
                    recognizer.recognize(source, listOf(box), language).firstOrNull()
                }.getOrNull() ?: continue
                val cleaned = reread.copy(text = JapaneseOcrCleaner.clean(reread.text))
                val kind = ScriptKindDetector.of(cleaned.text)
                val letters = cleaned.text.count { it.isLetter() }
                val previous = best.text.count { it.isLetter() }
                val matches = kind != ScriptKind.NONE &&
                    ScriptKindDetector.languageCode(kind) == language
                if (matches && letters > previous) {
                    best = cleaned
                    bestKind = kind
                }
            }
            if (best !== outTexts[index]) {
                logcat {
                    "Re-read ${box.toRect()} as ${bestKind.name}: ${best.text.lines().joinToString(" / ")}"
                }
                outTexts[index] = best
            }
        }
        return boxes to outTexts
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
        runCatching { mangaTranslator.close() }
    }

    private companion object {
        const val CAPTION_ROW_OVERLAP = 0.45f
        const val CAPTION_MAX_GAP_HEIGHTS = 2
        const val CAPTION_CONTAINMENT = 0.65f


        /** Floor for the row-banding height, so a page of tiny boxes still bands sensibly. */
        const val MIN_READING_BAND = 24

        /** Share of a block that must lie inside a balloon before that balloon owns it. */
        const val MIN_BALLOON_CONTAINMENT = 0.75f

        /** Recovered paper balloons that overlap this much are the same balloon. */
        const val MERGE_BALLOON_IOU = 0.4f
        const val MERGE_BALLOON_CONTAINED = 0.6f
        const val MIN_REGION_SIDE = 10
        /** Overlap above which two detections are the same balloon. */
        const val BALLOON_IOU = 0.5f
        /** Share of a detection lying inside a better one above which it is that one again. */
        const val BALLOON_CONTAINED = 0.8f
        /** Share of a re-read region already spoken for above which it is not lettered again. */
        const val MAX_RESCUE_OVERLAP = 0.3f
        /**
         * Narrower than this share of the page, a detection is a scrap rather than a balloon.
         *
         * The two recoveries worth having measured 36% and 69% of their page; the fragment of a
         * status panel that had to be refused measured 23%. A balloon carrying a sentence is a
         * quarter of the page wide as a rule, and a detection well under that is part of something
         * bigger that re-reading can only damage.
         */
        const val MIN_RESCUE_WIDTH_RATIO = 0.25f

        /** Height-to-width ratio above which a recognised line is a column, not a row. */
        const val VERTICAL_ASPECT = 1.5f
        /**
         * Confidence floor for a re-read.
         *
         * Measured both ways. Dropping it to 0.10 bought one extra recovery on one page and cost
         * fifty percent more time on every page, plus duplicate readings of the same banner. The
         * floor stays.
         */
        /** Characters a page must yield before its script is written down for the whole series. */
        const val MIN_CHARACTERS_TO_TRUST_SCRIPT = 24

        const val MIN_RESCUE_CONFIDENCE = 0.35f
        /** Ceiling on re-reads per page, so a page of false positives cannot dominate its own cost. */
        const val MAX_RESCUE_BALLOONS = 8

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
