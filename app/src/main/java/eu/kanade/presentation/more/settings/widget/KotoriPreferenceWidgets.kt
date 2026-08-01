package eu.kanade.presentation.more.settings.widget

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriTheme

/**
 * The 42×23 pill switch the tablet settings cards use (T8): the mode gradient with a
 * white knob when on, a flat track with a muted knob when off.
 */
@Composable
internal fun KotoriPillSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val accent = KotoriTheme.accent
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 19.dp else 2.5.dp,
        animationSpec = tween(180),
        label = "kotoriSwitchKnob",
    )
    Box(
        modifier = modifier
            .width(42.dp)
            .height(23.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) accent.gradient else SolidColor(Color(0x24FFFFFF)))
            .then(
                if (onCheckedChange != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onCheckedChange(!checked) }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobOffset)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (checked) Color.White else KotoriColors.textMuted),
        )
    }
}

/**
 * A short list preference rendered as the mock's segmented chips (`Auto · 1080p · 720p
 * · 480p`) instead of a dialog. Only used when the entries are few and short enough to
 * fit a card column; anything longer keeps the dialog.
 */
@Composable
internal fun KotoriSegmentedPreference(
    title: String,
    entries: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = KotoriTheme.accent
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            fontFamily = BeVietnamProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.5.sp,
            color = KotoriColors.textPrimary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            entries.forEach { (key, label) ->
                val selected = key == selectedKey
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(13.dp))
                        .then(
                            if (selected) {
                                Modifier.background(accent.gradient)
                            } else {
                                Modifier
                                    .background(Color(0x0FFFFFFF))
                                    .border(1.dp, Color(0x1CFFFFFF), RoundedCornerShape(13.dp))
                            },
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(key) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontFamily = BeVietnamProFamily,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 11.5.sp,
                        color = if (selected) accent.onAccent else KotoriColors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * T8's slider: the value sits on the title's baseline, the track carries the mode
 * gradient under a glowing white knob, and the range ends are labelled underneath.
 */
@Composable
internal fun KotoriSliderPreference(
    title: String,
    subtitle: String?,
    valueString: String,
    value: Int,
    valueRange: IntProgression,
    steps: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = KotoriTheme.accent
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.5.sp,
                color = KotoriColors.textPrimary,
            )
            Text(
                text = valueString,
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = accent.light,
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                fontFamily = BeVietnamProFamily,
                fontSize = 10.5.sp,
                color = KotoriColors.textMuted,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = steps.coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = accent.start,
                inactiveTrackColor = Color(0x21FFFFFF),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.height(24.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = valueRange.first.toString(),
                fontFamily = BeVietnamProFamily,
                fontSize = 10.sp,
                color = KotoriColors.textFaint,
            )
            Text(
                text = valueRange.last.toString(),
                fontFamily = BeVietnamProFamily,
                fontSize = 10.sp,
                color = KotoriColors.textFaint,
            )
        }
    }
}

/** True when a list preference is short enough to become segmented chips. */
internal fun canSegment(entries: Collection<String>): Boolean =
    entries.size in 2..4 && entries.all { it.length <= 8 }
