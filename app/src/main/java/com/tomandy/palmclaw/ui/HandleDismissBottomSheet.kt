package com.tomandy.palmclaw.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A bottom sheet that can only be dismissed by dragging the handle at the top
 * or tapping the scrim, not by swiping anywhere in the content area.
 * The sheet occupies at most 92% of the screen height.
 */
@Composable
fun HandleDismissBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val sheetOffset = remember { Animatable(1f) }
    var isDismissing by remember { mutableStateOf(false) }

    fun dismiss() {
        if (isDismissing) return
        isDismissing = true
        scope.launch {
            sheetOffset.animateTo(1f, tween(200))
            onDismissRequest()
        }
    }

    LaunchedEffect(Unit) {
        sheetOffset.animateTo(0f, tween(300))
    }

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val scrimAlpha = (1f - sheetOffset.value).coerceIn(0f, 1f) * 0.5f

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sheetMaxHeightPx = with(density) { (maxHeight * 0.92f).toPx() }

            // Scrim
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { dismiss() }
            )

            // Sheet
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .offset { IntOffset(0, (sheetOffset.value * sheetMaxHeightPx).roundToInt()) }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* consume taps so they don't reach the scrim */ },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                ) {
                    // Drag handle -- only this area triggers dismiss on swipe down
                    val dragState = rememberDraggableState { delta ->
                        val newOffset = (sheetOffset.value + delta / sheetMaxHeightPx)
                            .coerceAtLeast(0f)
                        scope.launch { sheetOffset.snapTo(newOffset) }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .draggable(
                                state = dragState,
                                orientation = Orientation.Vertical,
                                onDragStopped = {
                                    if (sheetOffset.value > 0.25f) {
                                        dismiss()
                                    } else {
                                        scope.launch {
                                            sheetOffset.animateTo(0f, spring())
                                        }
                                    }
                                }
                            )
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .width(32.dp)
                                .height(4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }

                    content()
                }
            }
        }
    }
}
