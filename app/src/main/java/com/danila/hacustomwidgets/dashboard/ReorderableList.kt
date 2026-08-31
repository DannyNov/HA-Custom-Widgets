package com.danila.hacustomwidgets.dashboard

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Stable-ID, long-press reorder pattern reusable by spaces, cards and other ordered settings. */
@Composable
fun <T> ReorderableList(
    items: List<T>,
    stableId: (T) -> String,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    canDrag: (T) -> Boolean = { true },
    itemContent: @Composable (item: T, dragging: Boolean, dragModifier: Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val latestItems by rememberUpdatedState(items)
    val latestCanDrag by rememberUpdatedState(canDrag)
    val latestOnMove by rememberUpdatedState(onMove)
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var pointerY by remember { mutableFloatStateOf(Float.NaN) }

    LaunchedEffect(draggingId, pointerY) {
        while (draggingId != null) {
            val layout = listState.layoutInfo
            val edge = 72f
            val speed = when {
                pointerY.isNaN() -> 0f
                pointerY < layout.viewportStartOffset + edge -> -14f
                pointerY > layout.viewportEndOffset - edge -> 14f
                else -> 0f
            }
            if (speed != 0f) listState.scrollBy(speed)
            delay(16L)
        }
    }

    LazyColumn(state = listState, modifier = modifier) {
        itemsIndexed(items, key = { _, item -> stableId(item) }) { index, item ->
            val id = stableId(item)
            val dragging = draggingId == id
            val visualModifier = Modifier
                .padding(vertical = 3.dp)
                .zIndex(if (dragging) 2f else 0f)
                .graphicsLayer {
                    translationY = if (dragging) dragOffset else 0f
                    scaleX = if (dragging) 1.015f else 1f
                    scaleY = if (dragging) 1.015f else 1f
                    shadowElevation = if (dragging) 14f else 0f
                }
            val dragModifier = if (!canDrag(item)) visualModifier else visualModifier.pointerInput(id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { local ->
                            draggingId = id
                            dragOffset = 0f
                            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                            pointerY = (info?.offset ?: 0) + local.y
                        },
                        onDragCancel = {
                            draggingId = null
                            dragOffset = 0f
                            pointerY = Float.NaN
                        },
                        onDragEnd = {
                            draggingId = null
                            dragOffset = 0f
                            pointerY = Float.NaN
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount.y
                            pointerY += amount.y
                            val currentIndex = latestItems.indexOfFirst { stableId(it) == id }
                            val current = listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.index == currentIndex }
                            if (current != null) {
                                val center = current.offset + current.size / 2f + dragOffset
                                val target = listState.layoutInfo.visibleItemsInfo
                                    .filter {
                                        it.index in latestItems.indices && latestCanDrag(latestItems[it.index])
                                    }
                                    .minByOrNull { abs((it.offset + it.size / 2f) - center) }
                                if (target != null && target.index != currentIndex) {
                                    dragOffset += current.offset - target.offset
                                    latestOnMove(currentIndex, target.index)
                                }
                            }
                        },
                    )
                }
            itemContent(item, dragging, dragModifier)
        }
    }
}

internal fun <T> moveStable(items: List<T>, fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) return items
    return items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}
