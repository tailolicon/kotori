package eu.kanade.presentation.theme.kotori

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private const val KOTORI_TABLET_MIN_SMALLEST_WIDTH_DP = 600

/**
 * Window-width tokens used inside Kotori layouts. The tablet composition itself is enabled only
 * for a physical >= 600 dp device in landscape; portrait always uses the phone composition.
 */
enum class KotoriWindowWidth {
    Compact,
    Medium,
    Expanded,
}

@Composable
@ReadOnlyComposable
fun kotoriWindowWidth(): KotoriWindowWidth {
    val width = LocalConfiguration.current.screenWidthDp
    return when {
        width >= 840 -> KotoriWindowWidth.Expanded
        width >= 600 -> KotoriWindowWidth.Medium
        else -> KotoriWindowWidth.Compact
    }
}

/** True when the re-composed tablet layouts (T1–T8) should be used. */
@Composable
@ReadOnlyComposable
fun isKotoriTablet(): Boolean {
    val configuration = LocalConfiguration.current
    val deviceConfiguration = LocalContext.current.applicationContext.resources.configuration
    return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        deviceConfiguration.smallestScreenWidthDp >= KOTORI_TABLET_MIN_SMALLEST_WIDTH_DP
}

/** True when the vertical navigation rail replaces the floating bottom nav. */
@Composable
@ReadOnlyComposable
fun isKotoriRail(): Boolean = isKotoriTablet()

/**
 * Tablet-only tokens. Values mirror `Kotori Tablet.dc.html` 1:1 (1 px ≈ 1 dp).
 */
object KotoriTabletTokens {
    /** Navigation rail width. */
    val railWidth = 92.dp

    /** Screen horizontal padding inside the content pane. */
    val screenPadding = 26.dp

    /** Pull-to-refresh spinner inset: clears the persistent top bar of a tablet screen. */
    val pullIndicatorInset = 74.dp

    /** T1 right column. */
    val libraryAsideWidth = 316.dp

    /** T2 left key-visual pane. */
    val detailArtWidth = 520.dp

    /** T3 docked episode drawer. */
    val playerDrawerWidth = 322.dp

    /** T4 page-thumbnail rail. */
    val readerThumbRail = 126.dp

    /** T5 chapter list / settings sidebar. */
    val novelChapterPane = 268.dp
    val novelSettingsPane = 216.dp

    /** T6 source column. */
    val browseSourcePane = 300.dp

    /** T8 settings list column. */
    val settingsListPane = 330.dp

    /** Rail surface fill and hairline, per mock. */
    val railBackground = Color(0x9E181326) // rgba(24,19,38,.62)
    val railBorder = Color(0x14FFFFFF) // rgba(255,255,255,.08)

    /** 42 dp circular glass action used across every tablet top bar. */
    val circleAction = 42.dp
}

/** Tablet-specific shapes that only appear in the 1280×800 compositions. */
object KotoriTabletShapes {
    /** Rail destination pill: 20 20 20 7 */
    val railItem = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomEnd = 20.dp,
        bottomStart = 7.dp,
    )

    /** Rail wordmark tile: 17 17 17 5 */
    val railTile = RoundedCornerShape(
        topStart = 17.dp,
        topEnd = 17.dp,
        bottomEnd = 17.dp,
        bottomStart = 5.dp,
    )

    /** Side panels (T1 `TIẾP TỤC`): 24 24 24 8 */
    val panel = RoundedCornerShape(
        topStart = 24.dp,
        topEnd = 24.dp,
        bottomEnd = 24.dp,
        bottomStart = 8.dp,
    )

    /** Player episode drawer: 26 26 26 9 */
    val playerDrawer = RoundedCornerShape(
        topStart = 26.dp,
        topEnd = 26.dp,
        bottomEnd = 26.dp,
        bottomStart = 9.dp,
    )

    /** Settings detail cards: 22 22 22 7 */
    val settingsCard = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 22.dp,
        bottomEnd = 22.dp,
        bottomStart = 7.dp,
    )

    /** Rows in side panels, calendar day cards, source rows: 16 16 16 5 */
    val listRow = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomEnd = 16.dp,
        bottomStart = 5.dp,
    )

    /** Compact source tiles in the T6 column: 13 13 13 4 */
    val sourceTile = RoundedCornerShape(
        topStart = 13.dp,
        topEnd = 13.dp,
        bottomEnd = 13.dp,
        bottomStart = 4.dp,
    )

    /** Settings row icon tiles: 12 12 12 4 */
    val settingsTile = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
        bottomEnd = 12.dp,
        bottomStart = 4.dp,
    )

    /** Novel chapter rows: 13 13 13 4 */
    val chapterRow = RoundedCornerShape(
        topStart = 13.dp,
        topEnd = 13.dp,
        bottomEnd = 13.dp,
        bottomStart = 4.dp,
    )

    /** Thumbnails inside drawer/panel rows: 11 11 11 4 */
    val panelThumb = RoundedCornerShape(
        topStart = 11.dp,
        topEnd = 11.dp,
        bottomEnd = 11.dp,
        bottomStart = 4.dp,
    )

    /** Episode grid thumbs on T2: 13 13 13 4 */
    val episodeThumb = RoundedCornerShape(
        topStart = 13.dp,
        topEnd = 13.dp,
        bottomEnd = 13.dp,
        bottomStart = 4.dp,
    )

    /** Inline search field on tablet top bars. */
    val inlineSearch = RoundedCornerShape(20.dp)

    /** Detail CTA on T2: 22 8 22 22 */
    val detailCta = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 8.dp,
        bottomEnd = 22.dp,
        bottomStart = 22.dp,
    )

    /** Browse segmented control active thumb on T6: 15 5 15 15 */
    val segmentActiveSmall = RoundedCornerShape(
        topStart = 15.dp,
        topEnd = 5.dp,
        bottomEnd = 15.dp,
        bottomStart = 15.dp,
    )
}

/**
 * The 92 dp vertical navigation rail: gradient `K` tile, hairline divider, the five
 * destinations, then the downloaded-only and settings circles pinned to the bottom.
 */
@Composable
fun KotoriTabletRail(
    modifier: Modifier = Modifier,
    accent: KotoriAccent = KotoriTheme.accent,
    onClickLogo: (() -> Unit)? = null,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable KotoriTabletRailScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(KotoriTabletTokens.railWidth)
            .background(KotoriTabletTokens.railBackground)
            .drawBehind {
                drawRect(
                    color = KotoriTabletTokens.railBorder,
                    topLeft = Offset(size.width - 1f, 0f),
                    size = Size(1f, size.height),
                )
            }
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(top = 18.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = KotoriTabletShapes.railTile,
                    ambientColor = accent.start.copy(alpha = 0.5f),
                    spotColor = accent.start.copy(alpha = 0.5f),
                )
                .clip(KotoriTabletShapes.railTile)
                .background(accent.ctaGradient)
                .then(
                    if (onClickLogo != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClickLogo,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "K",
                fontFamily = UnboundedFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 21.sp,
                color = Color.White,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .width(34.dp)
                .height(1.dp)
                .background(Color(0x1AFFFFFF)),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KotoriTabletRailScope(accent).content()
        }
        Spacer(modifier = Modifier.weight(1f))
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = footer,
        )
    }
}

class KotoriTabletRailScope internal constructor(
    private val accent: KotoriAccent,
) {
    @Composable
    fun Item(
        title: String,
        selected: Boolean,
        onClick: () -> Unit,
        icon: @Composable () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .width(64.dp)
                .clip(KotoriTabletShapes.railItem)
                .then(
                    if (selected) {
                        Modifier.background(accent.gradient)
                    } else {
                        Modifier
                    },
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(vertical = 10.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            icon()
            Text(
                text = title,
                color = if (selected) Color.White else KotoriColors.textMuted,
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 9.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 42 dp circular glass action used in every tablet top bar and in the rail footer.
 */
@Composable
fun KotoriCircleAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = KotoriTabletTokens.circleAction,
    iconSize: Dp = 20.dp,
    tint: Color = Color(0xFFCFC7E8),
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0x12FFFFFF))
            .border(1.dp, Color(0x1FFFFFFF), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
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
 * Persistent inline search field that replaces the phone's push-to-search screen.
 * Renders read-only text when [onValueChange] is null (mock behaviour: tap to focus).
 */
@Composable
fun KotoriInlineSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector = Icons.Filled.Search,
    gradientBorder: Boolean = false,
    onClear: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    KotoriSearchField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        gradientBorder = gradientBorder,
        onClear = onClear,
        onSearch = onSearch,
        leadingIcon = leadingIcon,
        shape = KotoriTabletShapes.inlineSearch,
        contentPadding = 16.dp,
        verticalPadding = 11.dp,
        textSize = 13.sp,
    )
}

/** Glass panel used for the tablet side columns. */
@Composable
fun KotoriGlassPanel(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = KotoriTabletShapes.panel,
    fill: Color = Color(0x0AFFFFFF),
    border: Color = Color(0x1AFFFFFF),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape),
        content = content,
    )
}

/** Tablet section label — Unbounded 10 sp, tracked out, accent-light. */
@Composable
fun KotoriPanelLabel(
    text: String,
    modifier: Modifier = Modifier,
    accent: KotoriAccent = KotoriTheme.accent,
    fontSize: androidx.compose.ui.unit.TextUnit = 10.sp,
) {
    Text(
        text = text,
        fontFamily = UnboundedFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = fontSize,
        letterSpacing = 0.16.em,
        color = accent.light,
        modifier = modifier,
    )
}

/** Thin gradient progress bar reused by hero cards, drawers and thumbs. */
@Composable
fun KotoriProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    track: Color = Color(0x1FFFFFFF),
    accent: KotoriAccent = KotoriTheme.accent,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(track),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(height / 2))
                .background(accent.gradient),
        )
    }
}

/** Chip used in the tablet top bars (`Tải 5 tập kế`, `Chỉ thư viện`, quick settings). */
@Composable
fun KotoriTabletChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    accent: KotoriAccent = KotoriTheme.accent,
    tinted: Boolean = false,
) {
    val shape = RoundedCornerShape(15.dp)
    val contentColor = when {
        selected -> accent.onAccent
        tinted -> accent.light
        else -> KotoriColors.textSecondary
    }
    Row(
        modifier = modifier
            .clip(shape)
            .then(
                when {
                    selected -> Modifier.background(accent.gradient)
                    tinted ->
                        Modifier
                            .background(accent.start.copy(alpha = 0.14f))
                            .border(1.dp, accent.light.copy(alpha = 0.45f), shape)
                    else ->
                        Modifier
                            .background(Color(0x0FFFFFFF))
                            .border(1.dp, Color(0x1CFFFFFF), shape)
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    accent.onAccent
                } else if (tinted) {
                    accent.light
                } else {
                    Color(0xFFCFC7E8)
                },
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = text,
            fontFamily = BeVietnamProFamily,
            fontWeight = if (selected || tinted) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 11.5.sp,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** Vertical hairline used between tablet panes. */
@Composable
fun KotoriPaneDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(Color(0x12FFFFFF)),
    )
}

/** Horizontal hairline used inside tablet panels (rows). */
@Composable
fun KotoriPanelDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x14FFFFFF)),
    )
}
