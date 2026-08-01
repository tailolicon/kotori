package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.theme.kotori.glass
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Kotori reader bottom tools: glass tiles (icon + label) per design 09.
 */
@Composable
fun ReaderBottomBar(
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    modifier: Modifier = Modifier,
    // T4: the spread tile only exists where a spread does — a wide landscape window.
    spreadEnabled: Boolean? = null,
    onClickSpread: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spreadEnabled != null) {
            ReaderToolTile(
                painter = rememberVectorPainter(Icons.Outlined.AutoStories),
                label = "Trải đôi",
                onClick = onClickSpread,
                selected = spreadEnabled,
                modifier = Modifier.weight(1f),
            )
        }
        ReaderToolTile(
            painter = painterResource(readingMode.iconRes),
            label = stringResource(MR.strings.viewer),
            onClick = onClickReadingMode,
            modifier = Modifier.weight(1f),
        )
        ReaderToolTile(
            painter = rememberVectorPainter(orientation.icon),
            label = stringResource(MR.strings.rotation_type),
            onClick = onClickOrientation,
            modifier = Modifier.weight(1f),
        )
        ReaderToolTile(
            painter = painterResource(if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp),
            label = stringResource(MR.strings.pref_crop_borders),
            onClick = onClickCropBorder,
            modifier = Modifier.weight(1f),
        )
        ReaderToolTile(
            painter = rememberVectorPainter(Icons.Outlined.Settings),
            label = stringResource(MR.strings.action_settings),
            onClick = onClickSettings,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One glass tile (icon + label) of the reader's bottom tool row, shared by every reading mode's chrome. */
@Composable
fun ReaderToolTile(
    painter: Painter,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    surface: ReaderBarSurface? = null,
    /** An on/off tool wears the mode gradient while it is on, per the mock's active chip. */
    selected: Boolean = false,
) {
    val accent = KotoriTheme.accent
    // Glass and its pale teal are built for the app's dark chrome. Over a cream or khaki page they
    // read as a faint smudge, so a page that supplies its own colours gets a plain tinted tile with
    // the page's own ink instead.
    val tileModifier = if (selected) {
        modifier
            .clip(RoundedCornerShape(15.dp))
            .background(accent.gradient)
    } else if (surface == null) {
        modifier.glass(shape = RoundedCornerShape(15.dp), elevated = true)
    } else {
        modifier
            .clip(RoundedCornerShape(15.dp))
            .background(surface.content.copy(alpha = 0.07f))
            .border(1.dp, surface.content.copy(alpha = 0.12f), RoundedCornerShape(15.dp))
    }
    Column(
        modifier = tileModifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            painter = painter,
            contentDescription = label,
            tint = if (selected) accent.onAccent else surface?.accent ?: accent.light,
        )
        Text(
            text = label,
            fontFamily = BeVietnamProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 8.5.sp,
            color = if (selected) {
                accent.onAccent
            } else {
                surface?.content?.copy(alpha = 0.85f) ?: KotoriColors.textSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
