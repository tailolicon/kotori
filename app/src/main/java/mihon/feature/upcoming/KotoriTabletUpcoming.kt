package mihon.feature.upcoming

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriCircleAction
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.KotoriTabletChip
import eu.kanade.presentation.theme.kotori.KotoriTabletShapes
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import java.time.LocalDate

/** One release card on the week board. */
@Immutable
data class KotoriUpcomingRelease(
    val key: String,
    /** Null when the source only knows the date, not the hour. */
    val time: String?,
    val title: String,
    val itemLabel: String,
    val coverData: Any?,
    val notify: Boolean,
    /** Owned series get the accent badge; the rest are just what is on air. */
    val inLibrary: Boolean = false,
    val onClick: () -> Unit,
    val onToggleNotify: () -> Unit,
)

/** One column of the week board. */
@Immutable
data class KotoriUpcomingDay(
    val date: LocalDate,
    val label: String,
    val releases: List<KotoriUpcomingRelease>,
)

/**
 * T7 · Lịch mùa — tablet composition: a seven-column week board with the day header
 * pill (today wearing the mode gradient), release cards carrying the air time and the
 * per-release bell, and the week arrows in the header.
 */
@Composable
fun KotoriTabletUpcomingBoard(
    title: String,
    subtitle: String,
    days: List<KotoriUpcomingDay>,
    libraryOnly: Boolean?,
    loading: Boolean = false,
    onToggleLibraryOnly: () -> Unit,
    onNotifyAll: () -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToday: () -> Unit = {},
    onHelp: (() -> Unit)? = null,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = KotoriTheme.accent
    val today = remember { LocalDate.now() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 22.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = KotoriColors.textPrimary,
                modifier = Modifier
                    .size(21.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onNavigateUp,
                    ),
            )
            Column {
                Text(
                    text = title,
                    fontFamily = UnboundedFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 21.sp,
                    color = KotoriColors.textPrimary,
                )
                Text(
                    text = subtitle,
                    fontFamily = BeVietnamProFamily,
                    fontSize = 12.sp,
                    color = KotoriColors.textMuted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Box(modifier = Modifier.weight(1f))
            if (libraryOnly != null) {
                KotoriTabletChip(
                    text = "Chỉ thư viện",
                    icon = Icons.Filled.Check,
                    onClick = onToggleLibraryOnly,
                    tinted = libraryOnly,
                )
            }
            KotoriTabletChip(
                text = "Nhắc tất cả",
                icon = Icons.Filled.Notifications,
                onClick = onNotifyAll,
            )
            if (onHelp != null) {
                KotoriCircleAction(
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "Trợ giúp",
                    onClick = onHelp,
                    size = 40.dp,
                    iconSize = 19.dp,
                )
            }
            // `Hôm nay` is week navigation, so it rides between the arrows instead of queueing
            // up as a third chip beside them.
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KotoriCircleAction(
                    icon = Icons.Filled.ChevronLeft,
                    contentDescription = "Tuần trước",
                    onClick = onPreviousWeek,
                    size = 40.dp,
                    iconSize = 19.dp,
                )
                Text(
                    text = "Hôm nay",
                    fontFamily = BeVietnamProFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = KotoriColors.textSecondary,
                    modifier = Modifier
                        .clip(KotoriShapes.pill)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onToday,
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                KotoriCircleAction(
                    icon = Icons.Filled.ChevronRight,
                    contentDescription = "Tuần sau",
                    onClick = onNextWeek,
                    size = 40.dp,
                    iconSize = 19.dp,
                )
            }
        }

        if (loading || days.all { it.releases.isEmpty() }) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (loading) "Đang tải lịch chiếu…" else "Tuần này không có tập nào lên sóng",
                    fontFamily = BeVietnamProFamily,
                    fontSize = 12.5.sp,
                    color = KotoriColors.textMuted,
                )
            }
            return@Column
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            days.forEach { day ->
                val isToday = day.date == today
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KotoriTabletShapes.listRow)
                            .then(
                                if (isToday) {
                                    Modifier.background(accent.gradient)
                                } else {
                                    Modifier
                                        .background(Color(0x0DFFFFFF))
                                        .border(1.dp, Color(0x17FFFFFF), KotoriTabletShapes.listRow)
                                },
                            )
                            .padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = day.label,
                            fontFamily = BeVietnamProFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 0.08.em,
                            color = if (isToday) accent.onAccent else KotoriColors.textMuted,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "${day.date.dayOfMonth}",
                            fontFamily = UnboundedFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = if (isToday) accent.onAccent else KotoriColors.textMuted,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        day.releases.forEach { release ->
                            KotoriUpcomingCard(release = release, highlighted = isToday)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KotoriUpcomingCard(
    release: KotoriUpcomingRelease,
    highlighted: Boolean,
) {
    val accent = KotoriTheme.accent
    val shape = KotoriTabletShapes.listRow
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0x0DFFFFFF))
            .border(
                1.dp,
                if (highlighted) accent.light.copy(alpha = 0.35f) else Color(0x17FFFFFF),
                shape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = release.onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(Color(0xFF1B1629)),
        ) {
            AsyncImage(
                model = release.coverData,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Only anime carries a real air time; a manga's next-update estimate is a date,
            // so the chip is dropped rather than stamping every card with 00:00.
            if (release.time != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 7.dp, top = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x990B0812))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = release.time,
                        fontFamily = BeVietnamProFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = Color.White,
                    )
                }
            }
            if (release.inLibrary) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 7.dp, bottom = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.gradient)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "✓ THƯ VIỆN",
                        fontFamily = BeVietnamProFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.sp,
                        color = accent.onAccent,
                    )
                }
            }
            Icon(
                imageVector = if (release.notify) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                contentDescription = null,
                tint = if (release.notify) accent.light else Color(0xA6FFFFFF),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 6.dp, top = 5.dp)
                    .size(15.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = release.onToggleNotify,
                    ),
            )
        }
        Column(modifier = Modifier.padding(start = 9.dp, end = 9.dp, top = 8.dp, bottom = 10.dp)) {
            Text(
                text = release.title,
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                lineHeight = 14.3.sp,
                color = KotoriColors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = release.itemLabel,
                fontFamily = BeVietnamProFamily,
                fontSize = 9.5.sp,
                color = KotoriColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
