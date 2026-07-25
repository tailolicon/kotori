package mihon.feature.novelreader

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import eu.kanade.tachiyomi.source.novel.NovelChapterHtml
import eu.kanade.tachiyomi.source.novel.NovelImagePolicy
import mihon.feature.novelreader.tts.SpeechScript
import mihon.feature.novelreader.tts.SpeechSentence

/** One laid-out unit of a chapter: either a paragraph of prose or an illustration. */
internal sealed interface NovelBlock {
    data class Prose(val text: String) : NovelBlock
    data class Illustration(val url: String) : NovelBlock
}

/**
 * Splits chapter text into blocks.
 *
 * Chapter text is author-controlled, so this is the last gate before an illustration URL becomes a
 * network request: a sentinel line is kept only if it carries a trusted HTTPS image URL. Blank,
 * malformed and untrusted lines are dropped rather than shown, which also stops the sentinel itself
 * from leaking into the prose.
 */
internal fun String.toNovelBlocks(): List<NovelBlock> = split("\n").mapNotNull { line ->
    val trimmed = line.trim()
    when {
        trimmed.isEmpty() -> null
        trimmed.startsWith(NovelChapterHtml.IMAGE_SENTINEL) ->
            trimmed.removePrefix(NovelChapterHtml.IMAGE_SENTINEL).trim()
                .takeIf(NovelImagePolicy::isTrusted)
                ?.let(NovelBlock::Illustration)
        else -> NovelBlock.Prose(trimmed)
    }
}

/** What the reader should paint on top of the prose while a chapter is being read aloud. */
internal data class NovelHighlight(
    val sentence: SpeechSentence?,
    val word: Int,
    val emphasiseWord: Boolean,
    val seekEnabled: Boolean,
)

@Composable
internal fun NovelBody(
    blocks: List<NovelBlock>,
    script: SpeechScript,
    highlight: NovelHighlight,
    fontFamily: FontFamily,
    fontSize: Int,
    lineHeightMultiplier: Float,
    ink: Color,
    accent: Color,
    muted: Color,
    onSeek: (Int) -> Unit,
    onTapOutsideSeek: () -> Unit,
    onBlockPositioned: (Int, Int) -> Unit,
) {
    // A chapter can open on an illustration, so the drop cap follows the first prose block rather
    // than the first block; an illustration-only chapter simply never draws one.
    val dropCapIndex = remember(blocks) { blocks.indexOfFirst { it is NovelBlock.Prose } }
    blocks.forEachIndexed { index, block ->
        val positionModifier = Modifier.onGloballyPositioned {
            onBlockPositioned(index, it.positionInParent().y.toInt())
        }
        when (block) {
            is NovelBlock.Illustration -> NovelIllustration(
                url = block.url,
                muted = muted,
                modifier = positionModifier,
            )
            is NovelBlock.Prose -> NovelProse(
                block = block,
                sentences = script.sentencesIn(index),
                highlight = highlight,
                withDropCap = index == dropCapIndex,
                fontFamily = fontFamily,
                fontSize = fontSize,
                lineHeightMultiplier = lineHeightMultiplier,
                ink = ink,
                accent = accent,
                onSeek = onSeek,
                onTapOutsideSeek = onTapOutsideSeek,
                modifier = positionModifier,
            )
        }
    }
}

/**
 * A paragraph, optionally with the sentence being read aloud lit up inside it.
 *
 * The highlight is drawn as span styles on a single [Text] rather than by splitting the paragraph
 * into one composable per sentence, because splitting would break line wrapping: sentences would no
 * longer flow into each other and the paragraph would visibly reflow the moment playback started.
 *
 * The same single [Text] is what makes tap-to-seek exact — [TextLayoutResult.getOffsetForPosition]
 * turns the tap into a character offset in the paragraph, which the script maps back to a sentence,
 * so a listener can point at any line and hear it, the way a lyric line is tapped in a music player.
 */
@Composable
private fun NovelProse(
    block: NovelBlock.Prose,
    sentences: List<SpeechSentence>,
    highlight: NovelHighlight,
    withDropCap: Boolean,
    fontFamily: FontFamily,
    fontSize: Int,
    lineHeightMultiplier: Float,
    ink: Color,
    accent: Color,
    onSeek: (Int) -> Unit,
    onTapOutsideSeek: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var layout by remember(block.text) { mutableStateOf<TextLayoutResult?>(null) }
    // The drop cap is drawn as its own Text, so the body Text starts one character late and every
    // offset the script knows about has to be shifted to match.
    val offsetShift = if (withDropCap) 1 else 0
    val activeSentence = highlight.sentence?.takeIf { candidate ->
        sentences.any { it.index == candidate.index }
    }
    val glow by animateColorAsState(
        targetValue = if (activeSentence != null) accent.copy(alpha = 0.16f) else Color.Transparent,
        label = "Novel sentence highlight",
    )

    val body = buildAnnotatedString {
        val text = block.text.drop(offsetShift)
        append(text)
        val sentence = activeSentence ?: return@buildAnnotatedString
        val start = (sentence.range.first - offsetShift).coerceIn(0, text.length)
        val end = (sentence.range.last + 1 - offsetShift).coerceIn(start, text.length)
        addStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium), start, end)
        if (!highlight.emphasiseWord) return@buildAnnotatedString
        val word = sentence.words.getOrNull(highlight.word) ?: return@buildAnnotatedString
        val wordStart = (start + word.first).coerceIn(start, end)
        val wordEnd = (start + word.last + 1).coerceIn(wordStart, end)
        addStyle(SpanStyle(fontWeight = FontWeight.Bold), wordStart, wordEnd)
    }

    val tapModifier = Modifier.pointerInput(sentences, highlight.seekEnabled, offsetShift) {
        detectTapGestures(
            // Tapping the page normally toggles the reader chrome, and it must keep doing so when
            // nothing is playing — seeking only makes sense once there is audio to seek.
            onTap = { position ->
                val sentence = if (highlight.seekEnabled) sentenceAt(layout, position, sentences, offsetShift) else null
                if (sentence != null) onSeek(sentence.index) else onTapOutsideSeek()
            },
            // Long-press works whether or not anything is playing: it is how a listener starts the
            // chapter from a particular paragraph instead of from the top.
            onLongPress = { position ->
                sentenceAt(layout, position, sentences, offsetShift)?.let { onSeek(it.index) }
            },
        )
    }

    val highlightModifier = modifier
        .fillMaxWidth()
        .padding(top = 12.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(glow)

    if (withDropCap) {
        Row(modifier = highlightModifier) {
            Text(
                text = block.text.take(1).uppercase(),
                fontFamily = UnboundedFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 44.sp,
                color = accent,
                modifier = Modifier.padding(end = 8.dp),
            )
            // The gesture modifier goes on the body Text rather than the Row, so the tap position
            // is already in the same coordinate space as the layout it is resolved against — on the
            // Row it would be offset by the drop cap's width and every tap would land a few
            // characters late.
            Text(
                text = body,
                fontFamily = fontFamily,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * lineHeightMultiplier).sp,
                color = ink,
                onTextLayout = { layout = it },
                modifier = tapModifier,
            )
        }
    } else {
        Text(
            text = body,
            fontFamily = fontFamily,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * lineHeightMultiplier).sp,
            color = ink,
            onTextLayout = { layout = it },
            modifier = highlightModifier.then(tapModifier),
        )
    }
}

/** Which sentence a tap landed on, or null when the paragraph carries none. */
private fun sentenceAt(
    layout: TextLayoutResult?,
    position: Offset,
    sentences: List<SpeechSentence>,
    offsetShift: Int,
): SpeechSentence? {
    if (sentences.isEmpty()) return null
    val result = layout ?: return sentences.first()
    val offset = result.getOffsetForPosition(position) + offsetShift
    return sentences.firstOrNull { offset in it.range } ?: sentences.last()
}

/**
 * Inline illustration, sized to the column width with its aspect ratio kept. A load failure is
 * non-fatal: the chapter keeps rendering and only this block degrades to a compact placeholder.
 */
@Composable
private fun NovelIllustration(url: String, muted: Color, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    val context = LocalContext.current
    val request = remember(context, url) { novelImageRequest(context, url) }
    if (failed) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(muted.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.BrokenImage,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Ảnh không tải được",
                fontFamily = BeVietnamProFamily,
                fontSize = 11.sp,
                color = muted,
            )
        }
    } else {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            onState = { state -> if (state is AsyncImagePainter.State.Error) failed = true },
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
    }
}

/**
 * Coil request for an inline illustration.
 *
 * Some novel hosts serve pictures only to requests that look like they came from their own pages,
 * so the site's Referer is attached when [NovelImagePolicy] knows one; hosts that need no header are
 * left alone rather than being handed a Referer they never asked for.
 */
internal fun novelImageRequest(context: Context, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .apply {
            NovelImagePolicy.refererFor(url)?.let { referer ->
                httpHeaders(NetworkHeaders.Builder().set("Referer", referer).build())
            }
        }
        .build()
