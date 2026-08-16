package mihon.feature.novelreader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import mihon.feature.novelreader.tts.SpeechScript
import mihon.feature.novelreader.tts.SpeechSentence
import kotlin.math.ceil

private val COLUMN_GAP = 52.dp

/** Head and foot margins. The foot is deeper than the head, as a bound page has always been. */
private val PAGE_MARGIN_TOP = 14.dp
private val PAGE_MARGIN_BOTTOM = 30.dp
private const val PARAGRAPH_BREAK = "\n\n"
private const val DROP_CAP_SP = 52f

/**
 * The chapter's prose flowed into one string, with a map back to the block each character
 * came from. Two-column layout has to break paragraphs across columns, so everything the
 * reader needs per character — which paragraph, which sentence — is resolved through here.
 */
internal class NovelProseFlow(
    val text: String,
    private val starts: IntArray,
    private val blockIndices: IntArray,
) {
    /** The block a flowed offset belongs to, for sentence lookup. */
    fun blockAt(offset: Int): Int {
        if (starts.isEmpty()) return -1
        var low = 0
        var high = starts.lastIndex
        var result = 0
        while (low <= high) {
            val mid = (low + high) / 2
            if (starts[mid] <= offset) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return blockIndices[result]
    }

    /** Where [blockIndex] starts in the flow, or -1 when it contributed nothing. */
    fun startOf(blockIndex: Int): Int {
        val i = blockIndices.indexOfFirst { it == blockIndex }
        return if (i < 0) -1 else starts[i]
    }

    companion object {
        /** Flows one run of consecutive prose blocks. */
        fun of(blocks: List<NovelBlock>, range: IntRange): NovelProseFlow {
            val builder = StringBuilder()
            val starts = ArrayList<Int>()
            val indices = ArrayList<Int>()
            for (index in range) {
                val block = blocks[index] as? NovelBlock.Prose ?: continue
                if (builder.isNotEmpty()) builder.append(PARAGRAPH_BREAK)
                starts.add(builder.length)
                indices.add(index)
                builder.append(block.text)
            }
            return NovelProseFlow(builder.toString(), starts.toIntArray(), indices.toIntArray())
        }
    }
}

/** One column of a page: a slice of its run's flow, optionally the one carrying the drop cap. */
internal data class NovelColumnSlice(
    val start: Int,
    val end: Int,
    /** Lines that sit beside the drop cap, at the narrowed width. Zero when there is none. */
    val dropCapLines: Int = 0,
    val dropCapSplit: Int = 0,
)

/** One screenful: either two (or one) text columns from a run, or a full-bleed illustration. */
internal data class NovelColumnPage(
    val runIndex: Int = -1,
    val columns: List<NovelColumnSlice> = emptyList(),
    val illustration: String? = null,
)

internal data class NovelPagination(
    val pages: List<NovelColumnPage>,
    val runs: List<NovelProseFlow>,
) {
    fun pageIndexForOffset(blockIndex: Int, offsetInBlock: Int): Int {
        pages.forEachIndexed { index, page ->
            val flow = runs.getOrNull(page.runIndex) ?: return@forEachIndexed
            val start = flow.startOf(blockIndex)
            if (start < 0) return@forEachIndexed
            val pos = start + offsetInBlock
            if (page.columns.any { pos in it.start until it.end }) return index
        }
        return -1
    }

    fun firstSentenceOnPage(pageIndex: Int, script: SpeechScript): Int {
        val page = pages.getOrNull(pageIndex) ?: return 0
        val flow = runs.getOrNull(page.runIndex) ?: return 0
        val start = page.columns.minOfOrNull { it.start } ?: return 0
        val block = flow.blockAt(start)
        return script.sentencesIn(block).firstOrNull()?.index ?: 0
    }
}

/**
 * T5 · the two-column reading area: the chapter's prose measured and cut into columns that
 * fill the pane, with a real floated drop cap on the opening column and illustrations
 * given pages of their own.
 */
@Composable
internal fun NovelColumnReader(
    blocks: List<NovelBlock>,
    script: SpeechScript,
    highlight: NovelHighlight,
    fontFamily: FontFamily,
    fontSize: Int,
    lineHeightMultiplier: Float,
    ink: Color,
    accent: Color,
    muted: Color,
    columnCount: Int,
    pageIndex: Int,
    onPageCountChange: (Int) -> Unit,
    onPaginationChange: (NovelPagination) -> Unit = {},
    onSeek: (Int) -> Unit,
    onTapOutsideSeek: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // A printed page is not typed edge to edge. Without a margin the last line of every column
    // sits on the screen boundary, which is what made a correctly-fitted spread still read as
    // something chopped off rather than something bound.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(top = PAGE_MARGIN_TOP, bottom = PAGE_MARGIN_BOTTOM),
    ) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val style = remember(fontFamily, fontSize, lineHeightMultiplier, ink) {
            TextStyle(
                fontFamily = fontFamily,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * lineHeightMultiplier).sp,
                color = ink,
                textAlign = TextAlign.Justify,
            )
        }
        val columnWidth = remember(availableWidth, columnCount) {
            ((availableWidth - COLUMN_GAP * (columnCount - 1)) / columnCount).coerceAtLeast(80.dp)
        }

        val pagination = remember(blocks, style, columnWidth, availableHeight, columnCount) {
            paginate(
                blocks = blocks,
                style = style,
                measurer = measurer,
                columnWidthPx = with(density) { columnWidth.roundToPx() },
                columnHeightPx = with(density) { availableHeight.roundToPx() },
                dropCapWidthPx = with(density) { (DROP_CAP_SP * 0.78f).sp.roundToPx() },
                dropCapHeightPx = with(density) { (DROP_CAP_SP * 0.9f).sp.roundToPx() },
                columnCount = columnCount,
            )
        }

        LaunchedEffect(pagination.pages.size) { onPageCountChange(pagination.pages.size) }
        LaunchedEffect(pagination) { onPaginationChange(pagination) }

        val page = pagination.pages.getOrNull(pageIndex.coerceIn(0, (pagination.pages.size - 1).coerceAtLeast(0)))
        if (page == null) {
            Box(modifier = Modifier.fillMaxSize())
            return@BoxWithConstraints
        }

        if (page.illustration != null) {
            AsyncImage(
                model = novelImageRequest(LocalContext.current, page.illustration),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onTapOutsideSeek() } },
            )
            return@BoxWithConstraints
        }

        val flow = pagination.runs.getOrNull(page.runIndex) ?: return@BoxWithConstraints
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
        ) {
            page.columns.forEachIndexed { index, slice ->
                Box(modifier = Modifier.width(columnWidth).fillMaxHeight()) {
                    if (index > 0) {
                        // The fold, not a rule. A hairline reads as a divider between two panes;
                        // paper darkens gradually toward the spine and lightens again, which is
                        // what makes two columns look like one open book rather than two pages
                        // pushed together.
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .width(COLUMN_GAP)
                                .offset(x = -COLUMN_GAP)
                                .background(
                                    Brush.horizontalGradient(
                                        0f to Color.Transparent,
                                        0.42f to ink.copy(alpha = 0.05f),
                                        0.5f to ink.copy(alpha = 0.13f),
                                        0.58f to ink.copy(alpha = 0.05f),
                                        1f to Color.Transparent,
                                    ),
                                ),
                        )
                    }
                    NovelTextColumn(
                        flow = flow,
                        slice = slice,
                        script = script,
                        highlight = highlight,
                        style = style,
                        accent = accent,
                        onSeek = onSeek,
                        onTapOutsideSeek = onTapOutsideSeek,
                        // Clipped, because a slice that is one pixel too tall for its column
                        // should lose that pixel cleanly instead of bleeding into the next page.
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds(),
                    )
                }
            }
        }
    }
}

/**
 * One column. When the slice carries the drop cap the first lines are laid out beside it at
 * a narrowed width and the rest flows full width underneath — a real float, not a raised
 * initial, so the opening paragraph wraps around the letter the way the mock draws it.
 */
@Composable
private fun NovelTextColumn(
    flow: NovelProseFlow,
    slice: NovelColumnSlice,
    script: SpeechScript,
    highlight: NovelHighlight,
    style: TextStyle,
    accent: Color,
    onSeek: (Int) -> Unit,
    onTapOutsideSeek: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeRange = remember(highlight.sentence, flow) {
        highlight.sentence?.let { sentence ->
            val base = flow.startOf(sentence.blockIndex)
            if (base < 0) null else (base + sentence.range.first)..(base + sentence.range.last)
        }
    }

    fun body(from: Int, to: Int): AnnotatedString = buildAnnotatedString {
        val text = flow.text.substring(from.coerceIn(0, flow.text.length), to.coerceIn(from, flow.text.length))
        append(text)
        val range = activeRange ?: return@buildAnnotatedString
        val start = (range.first - from).coerceIn(0, text.length)
        val end = (range.last + 1 - from).coerceIn(start, text.length)
        if (end > start) addStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium), start, end)
    }

    val seek: (Int, Offset, TextLayoutResult?) -> Unit = { from, position, layout ->
        val local = layout?.getOffsetForPosition(position) ?: 0
        val global = from + local
        val blockIndex = flow.blockAt(global)
        val blockStart = flow.startOf(blockIndex)
        val sentence: SpeechSentence? = if (highlight.seekEnabled && blockStart >= 0) {
            script.sentenceAt(blockIndex, global - blockStart)
        } else {
            null
        }
        if (sentence != null) onSeek(sentence.index) else onTapOutsideSeek()
    }

    Column(modifier = modifier) {
        if (slice.dropCapLines > 0) {
            val capLayout = remember(slice) { mutableStateOf<TextLayoutResult?>(null) }
            Row {
                Text(
                    text = flow.text.take(1).uppercase(),
                    fontFamily = UnboundedFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = DROP_CAP_SP.sp,
                    lineHeight = (DROP_CAP_SP * 0.88f).sp,
                    color = accent,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Text(
                    text = body(slice.start + 1, slice.dropCapSplit),
                    style = style,
                    onTextLayout = { capLayout.value = it },
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(slice, highlight.seekEnabled) {
                            detectTapGestures { seek(slice.start + 1, it, capLayout.value) }
                        },
                )
            }
        }
        val restLayout = remember(slice) { mutableStateOf<TextLayoutResult?>(null) }
        val restStart = if (slice.dropCapLines > 0) slice.dropCapSplit else slice.start
        Text(
            text = body(restStart, slice.end),
            style = style,
            onTextLayout = { restLayout.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(slice, highlight.seekEnabled) {
                    detectTapGestures { seek(restStart, it, restLayout.value) }
                },
        )
    }
}

/**
 * Cuts the chapter into pages.
 *
 * Prose runs are measured once at column width and sliced on line boundaries, so a
 * paragraph can continue across a column the way a printed page does. Illustrations break
 * the run and take a page to themselves rather than being squeezed into a column.
 */
private fun paginate(
    blocks: List<NovelBlock>,
    style: TextStyle,
    measurer: TextMeasurer,
    columnWidthPx: Int,
    columnHeightPx: Int,
    dropCapWidthPx: Int,
    dropCapHeightPx: Int,
    columnCount: Int,
): NovelPagination {
    if (columnWidthPx <= 0 || columnHeightPx <= 0) return NovelPagination(emptyList(), emptyList())

    val pages = mutableListOf<NovelColumnPage>()
    val runs = mutableListOf<NovelProseFlow>()

    var cursor = 0
    var isFirstRun = true
    while (cursor < blocks.size) {
        val block = blocks[cursor]
        if (block is NovelBlock.Illustration) {
            pages += NovelColumnPage(illustration = block.url)
            cursor++
            continue
        }
        var end = cursor
        while (end < blocks.size && blocks[end] is NovelBlock.Prose) end++
        val flow = NovelProseFlow.of(blocks, cursor until end)
        if (flow.text.isNotEmpty()) {
            val runIndex = runs.size
            runs += flow
            pages += sliceRun(
                flow = flow,
                runIndex = runIndex,
                style = style,
                measurer = measurer,
                columnWidthPx = columnWidthPx,
                columnHeightPx = columnHeightPx,
                dropCapWidthPx = if (isFirstRun) dropCapWidthPx else 0,
                dropCapHeightPx = dropCapHeightPx,
                columnCount = columnCount,
            )
            isFirstRun = false
        }
        cursor = end
    }
    return NovelPagination(pages, runs)
}

private fun sliceRun(
    flow: NovelProseFlow,
    runIndex: Int,
    style: TextStyle,
    measurer: TextMeasurer,
    columnWidthPx: Int,
    columnHeightPx: Int,
    dropCapWidthPx: Int,
    dropCapHeightPx: Int,
    columnCount: Int,
): List<NovelColumnPage> {
    val full = measurer.measure(
        text = AnnotatedString(flow.text),
        style = style,
        constraints = Constraints(maxWidth = columnWidthPx),
    )
    if (full.lineCount == 0) return emptyList()

    // The float: the opening lines are re-measured at the narrowed width so the paragraph
    // wraps around the cap, then the remainder of the run continues at full width.
    var dropCapLines = 0
    var dropCapSplit = 0
    var bodyStart = 0
    var narrowExtraLines = 0
    var capHeightPx = 0f
    if (dropCapWidthPx > 0 && flow.text.isNotEmpty()) {
        val lineHeightPx = (full.getLineBottom(0) - full.getLineTop(0)).coerceAtLeast(1f)
        dropCapLines = ceil(dropCapHeightPx / lineHeightPx).toInt().coerceIn(1, 4)
        val narrow = measurer.measure(
            text = AnnotatedString(flow.text.substring(1)),
            style = style,
            constraints = Constraints(maxWidth = (columnWidthPx - dropCapWidthPx).coerceAtLeast(40)),
        )
        val lastNarrowLine = (dropCapLines - 1).coerceAtMost(narrow.lineCount - 1)
        dropCapSplit = 1 + narrow.getLineEnd(lastNarrowLine, visibleEnd = true)
        bodyStart = dropCapSplit
        dropCapLines = lastNarrowLine + 1
        narrowExtraLines = dropCapLines
        capHeightPx = narrow.getLineBottom(lastNarrowLine)
    }

    // Everything after the cap flows at full width; measure that once and slice on lines.
    val body = if (bodyStart > 0) {
        measurer.measure(
            text = AnnotatedString(flow.text.substring(bodyStart)),
            style = style,
            constraints = Constraints(maxWidth = columnWidthPx),
        )
    } else {
        full
    }
    // Fit each column against the laid-out line positions rather than a nominal line height.
    // Paragraph gaps are taller than a line, so `height / lineHeight` overcounts: the column was
    // handed more lines than fit and the last one was sliced through the middle of the letters.
    val slices = mutableListOf<NovelColumnSlice>()
    var line = 0
    var first = true
    while (line < body.lineCount) {
        val budget = if (first && narrowExtraLines > 0) {
            (columnHeightPx - capHeightPx).coerceAtLeast(1f)
        } else {
            columnHeightPx.toFloat()
        }
        val top = body.getLineTop(line)
        var lastLine = line
        while (lastLine + 1 < body.lineCount && body.getLineBottom(lastLine + 1) - top <= budget) {
            lastLine++
        }
        val sliceStart = bodyStart + body.getLineStart(line)
        val sliceEnd = bodyStart + body.getLineEnd(lastLine, visibleEnd = true)
        slices += NovelColumnSlice(
            start = if (first && narrowExtraLines > 0) 0 else sliceStart,
            end = sliceEnd,
            dropCapLines = if (first) narrowExtraLines else 0,
            dropCapSplit = if (first) dropCapSplit else 0,
        )
        line = lastLine + 1
        first = false
    }

    return slices.chunked(columnCount).map { columns ->
        NovelColumnPage(runIndex = runIndex, columns = columns)
    }
}
