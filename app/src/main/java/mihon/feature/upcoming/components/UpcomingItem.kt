package mihon.feature.upcoming.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.glass
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover

/** Kotori season-calendar row: glass card, 74×46 thumb, title + author line. */
@Composable
fun UpcomingItem(
    upcoming: Manga,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 18.dp, vertical = 4.5.dp)
            .glass(shape = KotoriShapes.row)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = upcoming.asMangaCover(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(74.dp)
                .height(46.dp)
                .clip(KotoriShapes.thumbSmall),
        )
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(
                text = upcoming.title,
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.5.sp,
                color = KotoriColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val author = upcoming.author
            if (!author.isNullOrBlank()) {
                Text(
                    text = author,
                    fontFamily = BeVietnamProFamily,
                    fontSize = 10.5.sp,
                    color = KotoriColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
