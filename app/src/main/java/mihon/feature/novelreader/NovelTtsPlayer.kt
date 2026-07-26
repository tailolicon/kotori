package mihon.feature.novelreader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import mihon.feature.novelreader.tts.NovelTtsController
import mihon.feature.novelreader.tts.NovelTtsEngineId
import mihon.feature.novelreader.tts.NovelTtsPreparation
import mihon.feature.novelreader.tts.NovelTtsState
import mihon.feature.novelreader.tts.NovelTtsStatus
import mihon.feature.novelreader.tts.SpeechScript
import kotlin.math.roundToInt

/**
 * The listening player: a compact bar that expands into voice settings.
 *
 * Modelled on a music player because that is the mental model the feature borrows — a scrubbable
 * position, skip either side of it, and the "track list" being the chapter's own sentences, which
 * are tapped in the page itself rather than listed here.
 */
@Composable
internal fun NovelTtsPlayer(
    state: NovelTtsState,
    script: SpeechScript,
    controller: NovelTtsController,
    paper: NovelPaperTheme,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (script.isEmpty) 0f else (state.sentence + 1f) / script.size,
        label = "Novel listening progress",
    )

    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(paper.background.copy(alpha = 0.98f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NovelTtsScrubber(
            progress = progress,
            enabled = !script.isEmpty,
            paper = paper,
            onSeekFraction = { fraction ->
                controller.seekTo(((script.size - 1) * fraction).roundToInt())
            },
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(
                onClick = { controller.skip(-1) },
                enabled = state.sentence > 0,
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Câu trước", tint = paper.ink)
            }
            IconButton(onClick = controller::toggle) {
                when (state.status) {
                    NovelTtsStatus.PREPARING -> CircularProgressIndicator(
                        color = paper.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                    NovelTtsStatus.PLAYING -> Icon(
                        Icons.Filled.Pause,
                        contentDescription = "Tạm dừng",
                        tint = paper.accent,
                    )
                    else -> Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Nghe",
                        tint = paper.accent,
                    )
                }
            }
            IconButton(
                onClick = { controller.skip(1) },
                enabled = state.sentence in 0 until script.size - 1,
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Câu sau", tint = paper.ink)
            }

            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = state.headline(script),
                    fontFamily = BeVietnamProFamily,
                    fontSize = 11.sp,
                    color = paper.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.subtitle(script),
                    fontFamily = BeVietnamProFamily,
                    fontSize = 9.5.sp,
                    color = paper.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            NovelTtsRateChip(
                rate = state.rate,
                paper = paper,
                onClick = { controller.setRate(state.rate.nextRate()) },
            )
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = "Chọn giọng đọc",
                    tint = if (expanded) paper.accent else paper.ink,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Ẩn trình phát", tint = paper.muted)
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            NovelTtsVoicePanel(state = state, controller = controller, paper = paper)
        }
    }
}

/**
 * Position bar, draggable to scrub.
 *
 * Dragging maps to a sentence rather than to a time, because sentences are what the engine can
 * actually start from — a time-based scrub would have to guess, and would land mid-word.
 */
@Composable
private fun NovelTtsScrubber(
    progress: Float,
    enabled: Boolean,
    paper: NovelPaperTheme,
    onSeekFraction: (Float) -> Unit,
) {
    var width by remember { mutableStateOf(1) }
    var dragged by remember { mutableStateOf<Float?>(null) }
    val shown = dragged ?: progress

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(enabled, width) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragged = (offset.x / width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragged = ((dragged ?: 0f) + amount / width).coerceIn(0f, 1f)
                    },
                    onDragCancel = { dragged = null },
                    onDragEnd = {
                        dragged?.let(onSeekFraction)
                        dragged = null
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(paper.ink.copy(alpha = 0.14f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(shown.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.horizontalGradient(listOf(Color(0xFF14B8A6), Color(0xFF5EEAD4))),
                    ),
            )
        }
    }
}

@Composable
private fun NovelTtsRateChip(rate: Float, paper: NovelPaperTheme, onClick: () -> Unit) {
    Text(
        text = "${rate.trimmed()}×",
        fontFamily = UnboundedFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        color = paper.accent,
        modifier = Modifier
            .clip(CircleShape)
            .background(paper.accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/** Engine switch plus the voice list, including voices that still need downloading. */
@Composable
private fun NovelTtsVoicePanel(
    state: NovelTtsState,
    controller: NovelTtsController,
    paper: NovelPaperTheme,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NovelTtsEngineId.entries.forEach { engine ->
                val selected = engine == state.engineId
                Text(
                    text = engine.label,
                    fontFamily = BeVietnamProFamily,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 11.sp,
                    color = if (selected) paper.background else paper.ink,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) paper.accent else paper.ink.copy(alpha = 0.08f))
                        .clickable { controller.setEngine(engine) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        state.preparation?.let { NovelTtsPreparationRow(it, paper) }
        state.message?.let { message ->
            Text(
                text = message,
                fontFamily = BeVietnamProFamily,
                fontSize = 10.sp,
                color = paper.muted,
            )
        }

        if (state.voices.isEmpty()) {
            Text(
                text = when (state.engineId) {
                    NovelTtsEngineId.NEURAL ->
                        "Nhấn phát để tải giọng AI tiếng Việt về máy (chỉ tải một lần)."
                    NovelTtsEngineId.EDGE ->
                        "Nhấn phát để nghe — giọng Microsoft, cần mạng, không phải tải gì."
                    NovelTtsEngineId.SYSTEM ->
                        "Đang lấy danh sách giọng của thiết bị…"
                },
                fontFamily = BeVietnamProFamily,
                fontSize = 10.sp,
                color = paper.muted,
            )
        } else {
            Column(
                modifier = Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                state.voices.forEach { voice ->
                    val selected = voice.id == state.voiceId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { controller.setVoice(voice.id) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = when {
                                selected -> Icons.Filled.Check
                                !voice.downloaded -> Icons.Filled.Download
                                else -> Icons.Filled.GraphicEq
                            },
                            contentDescription = null,
                            tint = if (selected) paper.accent else paper.muted,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = voice.label,
                            fontFamily = BeVietnamProFamily,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) paper.accent else paper.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        voice.sizeLabel?.let {
                            Text(
                                text = it,
                                fontFamily = BeVietnamProFamily,
                                fontSize = 9.sp,
                                color = paper.muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelTtsPreparationRow(progress: NovelTtsPreparation, paper: NovelPaperTheme) {
    val label = when (progress) {
        NovelTtsPreparation.Starting -> "Đang chuẩn bị giọng đọc…"
        is NovelTtsPreparation.Downloading -> buildString {
            append("Đang tải ${progress.file} (${progress.index}/${progress.total})")
            progress.fraction?.let { append(" · ${(it * 100).roundToInt()}%") }
        }
        NovelTtsPreparation.Ready -> "Sẵn sàng"
        is NovelTtsPreparation.Failed -> progress.message
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            color = paper.accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = label,
            fontFamily = BeVietnamProFamily,
            fontSize = 10.sp,
            color = paper.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun NovelTtsState.headline(script: SpeechScript): String = when (status) {
    NovelTtsStatus.PREPARING -> "Đang chuẩn bị…"
    NovelTtsStatus.ERROR -> message ?: "Không đọc được"
    else -> script[sentence]?.text ?: "Chạm vào một đoạn để nghe từ đó"
}

private fun NovelTtsState.subtitle(script: SpeechScript): String {
    val position = if (sentence >= 0 && !script.isEmpty) "Câu ${sentence + 1}/${script.size} · " else ""
    return position + engineId.label
}

/** Speeds a listener actually wants, cycled by tapping the chip. */
private fun Float.nextRate(): Float {
    val steps = floatArrayOf(0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
    val current = steps.indexOfFirst { it >= this - 0.01f }.takeIf { it >= 0 } ?: 0
    return steps[(current + 1) % steps.size]
}

private fun Float.trimmed(): String =
    if (this % 1f == 0f) toInt().toString() else toString().trimEnd('0').trimEnd('.')
