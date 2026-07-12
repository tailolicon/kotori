package eu.kanade.presentation.theme.kotori

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Frosted-glass surface: translucent white fill + 1dp hairline border.
 */
fun Modifier.glass(
    shape: Shape,
    elevated: Boolean = false,
    pressed: Boolean = false,
): Modifier {
    val bg = when {
        pressed -> KotoriColors.glassBgPressed
        elevated -> KotoriColors.glassBgElevated
        else -> KotoriColors.glassBg
    }
    val border = if (elevated) KotoriColors.glassBorderElevated else KotoriColors.glassBorder
    return this
        .clip(shape)
        .background(bg)
        .border(width = 1.dp, color = border, shape = shape)
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = KotoriShapes.row,
    elevated: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .glass(shape = shape, elevated = elevated, pressed = isPressed)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        content()
    }
}

/**
 * Small circular glass icon button (36–44dp visual, 48dp touch target handled by caller padding).
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    tint: Color = KotoriColors.textPrimary,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(size)
            .glass(shape = CircleShape, elevated = true, pressed = isPressed)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Chip: gradient fill when selected, glass otherwise.
 */
@Composable
fun KotoriChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: KotoriAccent = KotoriTheme.accent,
) {
    val shape = KotoriShapes.chip
    Box(
        modifier = modifier
            .then(
                if (selected) {
                    Modifier
                        .clip(shape)
                        .background(accent.gradient)
                } else {
                    Modifier.glass(shape = shape, elevated = true)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) accent.onAccent else KotoriColors.textSecondary,
            fontFamily = BeVietnamProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Primary CTA: mode gradient, kotori corner 20/8/20/20, accent glow shadow.
 */
@Composable
fun GradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: KotoriAccent = KotoriTheme.accent,
    shape: Shape = KotoriShapes.cta,
    useCtaGradient: Boolean = true,
    contentPadding: Dp = 14.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = accent.start.copy(alpha = 0.45f),
                spotColor = accent.start.copy(alpha = 0.45f),
            )
            .clip(shape)
            .background(if (useCtaGradient) accent.ctaGradient else accent.gradient)
            .clickable(onClick = onClick)
            .padding(horizontal = contentPadding + 4.dp, vertical = contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Gradient circular action (play FAB on hero card, resume buttons). */
@Composable
fun GradientCircleButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: KotoriAccent = KotoriTheme.accent,
    size: Dp = 46.dp,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .shadow(
                elevation = 8.dp,
                shape = CircleShape,
                ambientColor = accent.start.copy(alpha = 0.45f),
                spotColor = accent.start.copy(alpha = 0.45f),
            )
            .clip(CircleShape)
            .background(accent.gradient)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = accent.onAccent,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Generic 2–4 segment glass switcher (Browse tabs, feed pills…).
 * Active segment: accent gradient + glow, kotori corner.
 */
@Composable
fun KotoriSegmentRow(
    labels: List<String>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badges: List<Int?> = emptyList(),
    accent: KotoriAccent = KotoriTheme.accent,
) {
    Row(
        modifier = modifier
            .glass(shape = KotoriShapes.segment, elevated = true)
            .padding(5.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == activeIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (selected) {
                            Modifier
                                .shadow(
                                    elevation = 6.dp,
                                    shape = KotoriShapes.segmentActive,
                                    ambientColor = accent.start.copy(alpha = 0.45f),
                                    spotColor = accent.start.copy(alpha = 0.45f),
                                )
                                .clip(KotoriShapes.segmentActive)
                                .background(accent.gradient)
                        } else {
                            Modifier.clip(KotoriShapes.segment)
                        },
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = label,
                        fontFamily = BeVietnamProFamily,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = if (selected) accent.onAccent else KotoriColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val badge = badges.getOrNull(index)
                    if (badge != null && badge > 0) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (selected) {
                                        Color(0x33FFFFFF)
                                    } else {
                                        KotoriColors.glassBgPressed
                                    },
                                )
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = "$badge",
                                fontFamily = BeVietnamProFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = if (selected) accent.onAccent else KotoriColors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Section label: `HÔM NAY`, `ĐANG TẢI`… Unbounded, uppercase, tracked out, accent-tinted. */
@Composable
fun KotoriSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    accent: KotoriAccent = KotoriTheme.accent,
) {
    Text(
        text = text.uppercase(),
        style = KotoriSectionLabelStyle,
        color = accent.light,
        modifier = modifier,
    )
}
