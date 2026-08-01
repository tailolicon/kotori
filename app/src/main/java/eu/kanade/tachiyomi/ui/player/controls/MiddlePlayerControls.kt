package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.kotori.AnimeAccent
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.player.controls.components.ControlsButton
import `is`.xyz.mpv.Utils
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.abs

@Composable
fun MiddlePlayerControls(
    // previous
    hasPrevious: Boolean,
    onSkipPrevious: () -> Unit,

    // middle
    isLoading: Boolean,
    isLoadingEpisode: Boolean,
    controlsShown: Boolean,
    areControlsLocked: Boolean,
    showLoadingCircle: Boolean,
    paused: Boolean,
    gestureSeekAmount: Pair<Int, Int>?,
    onPlayPauseClick: () -> Unit,

    // next
    hasNext: Boolean,
    onSkipNext: () -> Unit,

    enter: EnterTransition,
    exit: ExitTransition,
    modifier: Modifier = Modifier,
    // T3: on tablet the cluster is `replay_10 · play/pause · forward_10` in the mock's
    // sizes. Episode navigation lives in the docked drawer there, so the outer buttons
    // seek instead of skipping — on phone they stay episode skip.
    tabletSeek: ((Int) -> Unit)? = null,
) {
    if (tabletSeek != null) {
        KotoriTabletTransport(
            modifier = modifier,
            controlsShown = controlsShown,
            areControlsLocked = areControlsLocked,
            isLoading = isLoading,
            isLoadingEpisode = isLoadingEpisode,
            showLoadingCircle = showLoadingCircle,
            paused = paused,
            gestureSeekAmount = gestureSeekAmount,
            onPlayPauseClick = onPlayPauseClick,
            onSeek = tabletSeek,
            enter = enter,
            exit = exit,
        )
        return
    }
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.large),
    ) {
        AnimatedVisibility(
            visible = controlsShown && !areControlsLocked,
            enter = enter,
            exit = exit,
        ) {
            if (gestureSeekAmount == null) {
                ControlsButton(
                    Icons.Filled.SkipPrevious,
                    onClick = onSkipPrevious,
                    iconSize = 48.dp,
                    enabled = hasPrevious,
                )
            }
        }

        val icon = AnimatedImageVector.animatedVectorResource(R.drawable.anim_play_to_pause)
        val interaction = remember { MutableInteractionSource() }
        when {
            gestureSeekAmount != null -> {
                Text(
                    stringResource(
                        AYMR.strings.player_gesture_seek_indicator,
                        if (gestureSeekAmount.second >= 0) '+' else '-',
                        Utils.prettyTime(abs(gestureSeekAmount.second)),
                        Utils.prettyTime(gestureSeekAmount.first + gestureSeekAmount.second),
                    ),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        shadow = Shadow(Color.Black, blurRadius = 5f),
                    ),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }

            (isLoading || isLoadingEpisode) && showLoadingCircle -> CircularProgressIndicator(Modifier.size(96.dp))
            else -> {
                AnimatedVisibility(
                    visible = controlsShown && !areControlsLocked,
                    enter = enter,
                    exit = exit,
                ) {
                    Image(
                        painter = rememberAnimatedVectorPainter(icon, !paused),
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .clickable(
                                interaction,
                                ripple(),
                                onClick = onPlayPauseClick,
                            )
                            .padding(MaterialTheme.padding.medium),
                        contentDescription = null,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsShown && !areControlsLocked,
            enter = enter,
            exit = exit,
        ) {
            if (gestureSeekAmount == null) {
                ControlsButton(
                    Icons.Filled.SkipNext,
                    onClick = onSkipNext,
                    iconSize = 48.dp,
                    enabled = hasNext,
                )
            }
        }
    }
}

/**
 * T3 · the tablet transport cluster: 58 dp glass seek circles either side of an 88 dp
 * gradient play/pause, spaced 44 dp apart.
 */
@Composable
private fun KotoriTabletTransport(
    controlsShown: Boolean,
    areControlsLocked: Boolean,
    isLoading: Boolean,
    isLoadingEpisode: Boolean,
    showLoadingCircle: Boolean,
    paused: Boolean,
    gestureSeekAmount: Pair<Int, Int>?,
    onPlayPauseClick: () -> Unit,
    onSeek: (Int) -> Unit,
    enter: EnterTransition,
    exit: ExitTransition,
    modifier: Modifier = Modifier,
) {
    val accent = AnimeAccent
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(44.dp),
    ) {
        AnimatedVisibility(visible = controlsShown && !areControlsLocked, enter = enter, exit = exit) {
            if (gestureSeekAmount == null) {
                KotoriGlassSeekButton(icon = Icons.Filled.Replay10, onClick = { onSeek(-10) })
            }
        }

        when {
            gestureSeekAmount != null -> {
                Text(
                    stringResource(
                        AYMR.strings.player_gesture_seek_indicator,
                        if (gestureSeekAmount.second >= 0) '+' else '-',
                        Utils.prettyTime(abs(gestureSeekAmount.second)),
                        Utils.prettyTime(gestureSeekAmount.first + gestureSeekAmount.second),
                    ),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        shadow = Shadow(Color.Black, blurRadius = 5f),
                    ),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            (isLoading || isLoadingEpisode) && showLoadingCircle -> CircularProgressIndicator(Modifier.size(88.dp))
            else -> {
                AnimatedVisibility(visible = controlsShown && !areControlsLocked, enter = enter, exit = exit) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .shadow(
                                elevation = 16.dp,
                                shape = CircleShape,
                                ambientColor = accent.start.copy(alpha = 0.6f),
                                spotColor = accent.start.copy(alpha = 0.6f),
                            )
                            .clip(CircleShape)
                            .background(accent.ctaGradient)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(),
                                onClick = onPlayPauseClick,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(46.dp),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = controlsShown && !areControlsLocked, enter = enter, exit = exit) {
            if (gestureSeekAmount == null) {
                KotoriGlassSeekButton(icon = Icons.Filled.Forward10, onClick = { onSeek(10) })
            }
        }
    }
}

@Composable
private fun KotoriGlassSeekButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(Color(0x1AFFFFFF))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(30.dp),
        )
    }
}
