package eu.kanade.presentation.category.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.glass
import sh.calvin.reorderable.ReorderableCollectionItemScope
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReorderableCollectionItemScope.CategoryListItem(
    category: Category,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onHide: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape = KotoriShapes.row)
            .clickable(onClick = onRename)
            .padding(vertical = MaterialTheme.padding.small)
            .padding(
                start = MaterialTheme.padding.small,
                end = MaterialTheme.padding.medium,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.DragHandle,
            contentDescription = null,
            tint = KotoriColors.textMuted,
            modifier = Modifier
                .padding(MaterialTheme.padding.medium)
                .draggableHandle(),
        )
        Text(
            text = category.name,
            modifier = Modifier.weight(1f),
            fontFamily = BeVietnamProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = KotoriColors.textPrimary,
        )
        IconButton(onClick = onRename) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = stringResource(MR.strings.action_rename_category),
                tint = KotoriColors.textSecondary,
            )
        }
        if (onHide != null) {
            IconButton(onClick = onHide) {
                Icon(
                    imageVector = if (category.hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = null,
                    tint = KotoriColors.textSecondary,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(MR.strings.action_delete),
                tint = KotoriColors.danger,
            )
        }
    }
}
