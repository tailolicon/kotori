package eu.kanade.presentation.theme.kotori

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Kotori screen frame: aurora night background + custom header, no Material
 * top app bar. Content receives no automatic padding besides the header.
 */
@Composable
fun KotoriScreenScaffold(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    AuroraBackground(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                header()
                Box(modifier = Modifier.weight(1f)) {
                    content(PaddingValues(0.dp))
                }
                bottomBar()
            }
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                snackbarHost()
            }
        }
    }
}

/**
 * Screen header per mock: 14/18 padding, screen title Unbounded 20 w700 (or a
 * custom slot like the wordmark) on the left, glass circle icon buttons right.
 */
@Composable
fun KotoriHeader(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleContent: (@Composable () -> Unit)? = null,
    onNavigateUp: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (onNavigateUp != null) {
                GlassIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    onClick = onNavigateUp,
                    size = 36.dp,
                    iconSize = 19.dp,
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                when {
                    titleContent != null -> titleContent()
                    title != null -> Text(
                        text = title,
                        fontFamily = UnboundedFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = KotoriColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                fontFamily = BeVietnamProFamily,
                fontSize = 11.sp,
                color = KotoriColors.textMuted,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp),
            )
        }
    }
}

/** Header action: 36dp glass circle icon button (mock spec). */
@Composable
fun KotoriHeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = KotoriColors.textPrimary.copy(alpha = 0.85f),
) {
    GlassIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        size = 36.dp,
        iconSize = 19.dp,
        tint = tint,
    )
}

/**
 * Glass search field (mock 06): rounded 18, glass fill, placeholder muted.
 * Renders as a live text field; pass null [value] handling at caller.
 */
@Composable
fun KotoriSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    gradientBorder: Boolean = false,
    onClear: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val accent = KotoriTheme.accent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape = KotoriShapes.pill, elevated = true)
            .then(
                if (gradientBorder) {
                    Modifier.border(
                        width = 1.5.dp,
                        brush = accent.gradient,
                        shape = KotoriShapes.pill,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = KotoriColors.textMuted,
            modifier = Modifier.size(18.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    fontFamily = BeVietnamProFamily,
                    fontSize = 12.sp,
                    color = KotoriColors.textMuted,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = BeVietnamProFamily,
                    fontSize = 12.sp,
                    color = KotoriColors.textPrimary,
                ),
                cursorBrush = SolidColor(KotoriTheme.accent.start),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
        if (value.isNotEmpty() && onClear != null) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = KotoriColors.textMuted,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onClear() },
            )
        }
    }
    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}

/**
 * Kotori empty state: centered aurora glow + Unbounded label + hint.
 */
@Composable
fun KotoriEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            fontFamily = UnboundedFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = KotoriColors.textPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        if (hint != null) {
            Text(
                text = hint,
                fontFamily = BeVietnamProFamily,
                fontSize = 12.sp,
                color = KotoriColors.textMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }
        actions()
    }
}

/**
 * Bottom multi-select action bar (mock 05): glass, accent border,
 * `n đã chọn` + action icons.
 */
@Composable
fun KotoriSelectionBar(
    count: Int,
    modifier: Modifier = Modifier,
    accent: KotoriAccent = KotoriTheme.accent,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .glass(shape = KotoriShapes.pill, elevated = true)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "$count đã chọn",
            fontFamily = BeVietnamProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.5.sp,
            color = accent.light,
        )
        Box(modifier = Modifier.weight(1f))
        actions()
    }
}
