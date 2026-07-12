package eu.kanade.presentation.theme.kotori

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Aurora Glass background: base night color + two slowly drifting radial
 * light blobs behind the content. Colors follow the active mode accent.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    accent: KotoriAccent = KotoriTheme.accent,
    baseColor: Color = KotoriColors.bgBase,
    content: @Composable () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val drift1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "auroraDrift1",
    )
    val drift2 by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "auroraDrift2",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseColor)
            .drawBehind {
                val blobRadius = size.width * 0.68f
                val scale1 = 1f + drift1 * 0.15f
                val scale2 = 1f + drift2 * 0.15f

                // Top-right blob
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.start.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(
                            x = size.width * 0.92f + drift1 * 30f,
                            y = size.height * 0.06f - drift1 * 24f,
                        ),
                        radius = blobRadius * scale1,
                    ),
                    radius = blobRadius * scale1,
                    center = Offset(
                        x = size.width * 0.92f + drift1 * 30f,
                        y = size.height * 0.06f - drift1 * 24f,
                    ),
                )
                // Bottom-left blob
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.end.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(
                            x = size.width * 0.06f - drift2 * 30f,
                            y = size.height * 0.86f + drift2 * 24f,
                        ),
                        radius = blobRadius * scale2,
                    ),
                    radius = blobRadius * scale2,
                    center = Offset(
                        x = size.width * 0.06f - drift2 * 30f,
                        y = size.height * 0.86f + drift2 * 24f,
                    ),
                )
            },
    ) {
        content()
    }
}
