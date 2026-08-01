package eu.kanade.presentation.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.LocalKotoriAccent

/**
 * Screen 18 · the feed pills above a source's grid: `Phổ biến` and `Mới nhất`.
 *
 * The mock draws these as its own control rather than Material filter chips — the active one is
 * a filled mode-gradient pill with an accent glow, the inactive one is glass. Kept here so the
 * manga and anime browse screens stay identical, since they are the same screen in the design.
 */
@Composable
fun KotoriFeedPill(
    label: String,
    selected: Boolean,
    latest: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalKotoriAccent.current
    Row(
        modifier = modifier
            .clip(KotoriShapes.pill)
            .then(
                if (selected) {
                    Modifier.background(accent.gradient)
                } else {
                    Modifier
                        .background(Color(0x0FFFFFFF))
                        .border(1.dp, Color(0x1CFFFFFF), KotoriShapes.pill)
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (latest) Icons.Filled.Schedule else Icons.Filled.LocalFireDepartment,
            contentDescription = null,
            tint = if (selected) accent.onAccent else KotoriColors.textSecondary,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = label,
            fontFamily = BeVietnamProFamily,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 11.5.sp,
            color = if (selected) accent.onAccent else KotoriColors.textSecondary,
        )
    }
}

/**
 * Screen 18 · the `Bộ lọc` button, bottom-right over the grid.
 *
 * The mock promotes the source's filters from a chip among the feed pills to a CTA of its own,
 * on the CTA gradient and the clipped-corner CTA shape.
 */
@Composable
fun KotoriFilterFab(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalKotoriAccent.current
    Row(
        modifier = modifier
            .clip(KotoriShapes.cta)
            .background(accent.ctaGradient)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.FilterList,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            fontFamily = BeVietnamProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.5.sp,
            color = Color.White,
        )
    }
}

/**
 * Screen 18 · the line under a source's name: `Anime · Tiếng Việt · v2.1.0`.
 *
 * Which content type, which language, which build — three things a reader needs when a source
 * behaves oddly, and none of which the name alone tells them. Built-in sources have no extension
 * version, so that segment is dropped rather than shown empty.
 */
@Composable
fun kotoriSourceSubtitle(
    sourceId: Long,
    lang: String?,
    typeLabel: String,
    version: String?,
): String {
    val context = LocalContext.current
    return remember(sourceId, lang, typeLabel, version) {
        listOfNotNull(
            typeLabel,
            lang?.takeIf { it.isNotBlank() }?.let { LocaleHelper.getSourceDisplayName(it, context) },
            version?.takeIf { it.isNotBlank() }?.let { "v$it" },
        ).joinToString(" · ")
    }
}
