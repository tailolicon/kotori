package eu.kanade.presentation.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Dark glass badge `✓ THƯ VIỆN` on browse covers already in library. */
@Composable
internal fun InLibraryBadge(enabled: Boolean) {
    if (enabled) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xB314101F))
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = KotoriColors.success,
                modifier = Modifier
                    .padding(end = 3.dp)
                    .size(10.dp),
            )
            Text(
                text = stringResource(MR.strings.label_library).uppercase(),
                fontFamily = UnboundedFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 8.sp,
                letterSpacing = 0.08.em,
                color = KotoriColors.textPrimary,
            )
        }
    }
}
