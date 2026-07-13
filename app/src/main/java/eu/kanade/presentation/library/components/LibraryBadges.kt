package eu.kanade.presentation.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriTheme
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.ui.text.font.FontWeight

/**
 * Downloaded chip: dark glass circle with teal `download_done` icon (top-left of covers).
 */
@Composable
internal fun DownloadsBadge(count: Int) {
    if (count > 0) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color(0x9914101F)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.DownloadDone,
                contentDescription = null,
                tint = KotoriColors.success,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/**
 * Unread badge: mode-gradient pill `n mới` with accent glow (top-right of covers).
 */
@Composable
internal fun UnviewedBadge(count: Long) {
    UnreadBadge(count)
}

@Composable
internal fun UnreadBadge(count: Long) {
    if (count > 0) {
        val accent = KotoriTheme.accent
        Box(
            modifier = Modifier
                .shadow(
                    elevation = 5.dp,
                    shape = RoundedCornerShape(10.dp),
                    ambientColor = accent.start.copy(alpha = 0.5f),
                    spotColor = accent.start.copy(alpha = 0.5f),
                )
                .clip(RoundedCornerShape(10.dp))
                .background(accent.gradient),
        ) {
            Text(
                text = "$count mới",
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = accent.onAccent,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
internal fun LanguageBadge(
    isLocal: Boolean,
    sourceLanguage: String,
) {
    if (isLocal) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color(0x9914101F)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = KotoriColors.success,
                modifier = Modifier.size(13.dp),
            )
        }
    } else if (sourceLanguage.isNotEmpty()) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x9914101F)),
        ) {
            Text(
                text = sourceLanguage.uppercase(),
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                color = KotoriColors.textSecondary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun BadgePreview() {
    TachiyomiPreviewTheme {
        Column {
            DownloadsBadge(count = 10)
            UnreadBadge(count = 10)
            LanguageBadge(isLocal = true, sourceLanguage = "EN")
            LanguageBadge(isLocal = false, sourceLanguage = "EN")
        }
    }
}
