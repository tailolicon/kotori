package eu.kanade.presentation.theme.kotori

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Kotori floating glass bottom navigation pill.
 * Active tab: mode-gradient pill, white filled icon. Inactive: muted #8D84AC.
 */
@Composable
fun KotoriNavBar(
    modifier: Modifier = Modifier,
    accent: KotoriAccent = KotoriTheme.accent,
    content: @Composable KotoriNavBarScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 14.dp)
            .fillMaxWidth()
            .shadow(
                elevation = 17.dp,
                shape = KotoriShapes.nav,
                ambientColor = Color.Black.copy(alpha = 0.45f),
                spotColor = Color.Black.copy(alpha = 0.45f),
            )
            .clip(KotoriShapes.nav)
            .background(KotoriColors.bgNavbar)
            .border(1.dp, Color(0x1AFFFFFF), KotoriShapes.nav)
            .padding(horizontal = 10.dp, vertical = 9.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KotoriNavBarScope(this, accent).content()
    }
}

class KotoriNavBarScope(
    private val rowScope: androidx.compose.foundation.layout.RowScope,
    private val accent: KotoriAccent,
) {
    @Composable
    fun Item(
        title: String,
        selected: Boolean,
        onClick: () -> Unit,
        icon: @Composable () -> Unit,
    ) {
        val itemShape = RoundedCornerShape(20.dp)
        val textColor by animateColorAsState(
            targetValue = if (selected) Color.White else KotoriColors.textMuted,
            animationSpec = tween(250),
            label = "navItemColor",
        )
        val background: Brush = if (selected) accent.gradient else SolidColor(Color.Transparent)
        with(rowScope) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(itemShape)
                    .background(background)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                    .padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                icon()
                Text(
                    text = title,
                    color = textColor,
                    fontFamily = BeVietnamProFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
