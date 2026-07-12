package eu.kanade.presentation.category.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.GradientButton
import eu.kanade.presentation.theme.kotori.KotoriTheme
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun CategoryFloatingActionButton(
    lazyListState: LazyListState,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = KotoriTheme.accent
    GradientButton(
        onClick = onCreate,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            tint = accent.onAccent,
        )
        Text(
            text = stringResource(MR.strings.action_add),
            color = accent.onAccent,
            fontFamily = BeVietnamProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}
