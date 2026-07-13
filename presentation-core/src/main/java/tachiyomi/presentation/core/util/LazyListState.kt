package tachiyomi.presentation.core.util

import androidx.compose.foundation.lazy.LazyListState

fun LazyListState.shouldExpandFAB(): Boolean = lastScrolledBackward || !canScrollForward || !canScrollBackward

fun androidx.compose.foundation.lazy.grid.LazyGridState.shouldExpandFAB(): Boolean =
    lastScrolledBackward || !canScrollForward || !canScrollBackward
