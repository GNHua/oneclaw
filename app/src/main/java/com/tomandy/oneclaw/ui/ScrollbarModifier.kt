package com.tomandy.oneclaw.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Persistent cache of measured item heights for a LazyList.
 * Accumulates actual pixel heights as items become visible, producing
 * an increasingly accurate total-content-height estimate over time.
 */
class LazyListHeightCache {
    private val heights = mutableMapOf<Int, Int>()
    private var cachedAvg: Float = 0f

    fun update(visibleItems: List<androidx.compose.foundation.lazy.LazyListItemInfo>) {
        for (item in visibleItems) {
            heights[item.index] = item.size
        }
        cachedAvg = if (heights.isNotEmpty()) {
            heights.values.sum().toFloat() / heights.size
        } else {
            0f
        }
    }

    fun estimateTotalHeight(totalItems: Int): Float {
        if (heights.isEmpty()) return 0f
        val avg = cachedAvg.coerceAtLeast(1f)
        var total = 0f
        for (i in 0 until totalItems) {
            total += heights[i]?.toFloat() ?: avg
        }
        return total
    }

    fun estimateOffsetToIndex(index: Int): Float {
        if (heights.isEmpty()) return 0f
        val avg = cachedAvg.coerceAtLeast(1f)
        var offset = 0f
        for (i in 0 until index) {
            offset += heights[i]?.toFloat() ?: avg
        }
        return offset
    }
}

@Composable
fun rememberLazyListHeightCache(): LazyListHeightCache {
    return remember { LazyListHeightCache() }
}

/**
 * Draws a smooth scrollbar for a LazyColumn/LazyRow.
 *
 * Uses a [LazyListHeightCache] to accumulate actual measured item heights,
 * producing a stable scrollbar that doesn't jump as items scroll in/out.
 */
fun Modifier.drawScrollbar(
    state: LazyListState,
    color: Color,
    heightCache: LazyListHeightCache,
    width: Dp = 4.dp
): Modifier = drawWithContent {
    drawContent()
    val layoutInfo = state.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    if (totalItems == 0 || visibleItems.isEmpty()) return@drawWithContent

    heightCache.update(visibleItems)

    val viewportHeight = size.height
    val estimatedTotalHeight = heightCache.estimateTotalHeight(totalItems)

    if (estimatedTotalHeight <= viewportHeight) return@drawWithContent

    // Thumb size proportional to viewport vs content
    val scrollbarHeight = (viewportHeight / estimatedTotalHeight * viewportHeight)
        .coerceAtLeast(24.dp.toPx())
        .coerceAtMost(viewportHeight)
    val scrollRange = viewportHeight - scrollbarHeight

    // Scroll position: accumulated height to first visible + pixel offset within it
    val firstVisible = visibleItems.first()
    val scrolledPx = heightCache.estimateOffsetToIndex(firstVisible.index) +
        (-firstVisible.offset.toFloat())
    val maxScrollPx = (estimatedTotalHeight - viewportHeight).coerceAtLeast(1f)
    val scrollFraction = (scrolledPx / maxScrollPx).coerceIn(0f, 1f)

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
