package com.tomandy.palmclaw.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a smooth scrollbar for a LazyColumn/LazyRow.
 *
 * Uses pixel-level offset interpolation so the thumb moves continuously
 * instead of jumping between item indices.
 */
fun Modifier.drawScrollbar(
    state: LazyListState,
    color: Color,
    width: Dp = 4.dp
): Modifier = drawWithContent {
    drawContent()
    val layoutInfo = state.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    if (totalItems == 0 || visibleItems.size >= totalItems) return@drawWithContent

    val viewportHeight = size.height
    val firstVisible = visibleItems.firstOrNull() ?: return@drawWithContent
    val lastVisible = visibleItems.last()

    // Use median visible item height to estimate total content height.
    // Median is more stable than mean when items have varied sizes.
    val sortedHeights = visibleItems.map { it.size }.sorted()
    val medianItemHeight = sortedHeights[sortedHeights.size / 2].coerceAtLeast(1)
    val estimatedTotalHeight = medianItemHeight.toFloat() * totalItems

    // Scrollbar thumb size proportional to viewport vs content
    val scrollbarHeight = (viewportHeight / estimatedTotalHeight * viewportHeight)
        .coerceAtLeast(24.dp.toPx())
        .coerceAtMost(viewportHeight)
    val scrollRange = viewportHeight - scrollbarHeight

    // Smooth scroll fraction using pixel offset within the first visible item
    val scrollFraction = when {
        lastVisible.index >= totalItems - 1 -> {
            // At the bottom: compute exact fraction from last item's bottom edge
            val lastItemBottom = lastVisible.offset + lastVisible.size
            val overshoot = lastItemBottom - viewportHeight
            if (overshoot <= 0) 1f
            else 1f // fully scrolled if last item is visible
        }
        firstVisible.index == 0 && firstVisible.offset >= 0 -> 0f
        else -> {
            val itemFraction = if (firstVisible.size > 0) {
                (-firstVisible.offset.toFloat() / firstVisible.size)
            } else {
                0f
            }
            val maxFirstIndex = (totalItems - visibleItems.size).coerceAtLeast(1)
            ((firstVisible.index + itemFraction) / maxFirstIndex).coerceIn(0f, 1f)
        }
    }

    val scrollbarY = scrollFraction * scrollRange

    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - width.toPx(), scrollbarY),
        size = Size(width.toPx(), scrollbarHeight),
        cornerRadius = CornerRadius(width.toPx() / 2)
    )
}

/**
 * Draws a smooth scrollbar for a regular scrollable Column/Row using ScrollState.
 *
 * ScrollState already provides continuous pixel values, so this is inherently smooth.
 */
fun Modifier.drawColumnScrollbar(
    state: ScrollState,
    color: Color,
    width: Dp = 4.dp
): Modifier = drawWithContent {
    drawContent()
    if (state.maxValue <= 0) return@drawWithContent

    val viewportHeight = size.height
    val totalHeight = (state.maxValue + viewportHeight).coerceAtLeast(1f)
    val scrollbarHeight = (viewportHeight / totalHeight * viewportHeight)
        .coerceAtLeast(24.dp.toPx())
    val scrollRange = viewportHeight - scrollbarHeight
    val scrollFraction = state.value.toFloat() / state.maxValue.coerceAtLeast(1)
    val scrollbarY = scrollFraction * scrollRange

    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - width.toPx(), scrollbarY),
        size = Size(width.toPx(), scrollbarHeight),
        cornerRadius = CornerRadius(width.toPx() / 2)
    )
}
