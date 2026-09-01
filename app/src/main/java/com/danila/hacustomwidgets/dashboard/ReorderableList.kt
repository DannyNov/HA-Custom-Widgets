package com.danila.hacustomwidgets.dashboard

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import kotlin.math.sign

private const val EDGE_ZONE_DP = 72f
private const val MAX_EDGE_SPEED_DP_PER_SECOND = 920f

internal data class ReorderItemGeometry(
    val index: Int,
    val top: Float,
    val size: Int,
    val draggable: Boolean = true,
) {
    val midpoint: Float get() = top + size / 2f
}

/** Pure calculations kept outside Compose so density, scroll and midpoint behavior are testable. */
internal object ReorderDragPolicy {
    fun edgeZonePx(density: Float, edgeDp: Float = EDGE_ZONE_DP): Float = edgeDp * density

    fun edgeVelocityPxPerSecond(
        pointerY: Float,
        viewportStart: Float,
        viewportEnd: Float,
        edgePx: Float,
        density: Float,
    ): Float {
        if (edgePx <= 0f || viewportEnd <= viewportStart) return 0f
        val topPenetration = ((viewportStart + edgePx - pointerY) / edgePx).coerceIn(0f, 1f)
        val bottomPenetration = ((pointerY - (viewportEnd - edgePx)) / edgePx).coerceIn(0f, 1f)
        val signedPenetration = when {
            topPenetration > 0f -> -topPenetration
            bottomPenetration > 0f -> bottomPenetration
            else -> return 0f
        }
        val penetration = kotlin.math.abs(signedPenetration)
        val maxSpeed = MAX_EDGE_SPEED_DP_PER_SECOND * density
        // Starts continuously at zero at the inner boundary and accelerates toward the edge.
        val speed = maxSpeed * (0.15f * penetration + 0.85f * penetration * penetration)
        return speed * signedPenetration.sign
    }

    fun requestedScroll(velocityPxPerSecond: Float, elapsedNanos: Long): Float =
        velocityPxPerSecond * (elapsedNanos.coerceAtLeast(0L) / 1_000_000_000f)

    /** Returns only an adjacent target. A later frame must observe the new layout before another move. */
    fun adjacentTarget(
        draggedIndex: Int,
        draggedCenter: Float,
        direction: Int,
        geometry: List<ReorderItemGeometry>,
    ): Int? {
        if (direction == 0) return null
        val targetIndex = draggedIndex + if (direction > 0) 1 else -1
        val target = geometry.firstOrNull { it.index == targetIndex && it.draggable } ?: return null
        return when {
            direction > 0 && draggedCenter > target.midpoint -> targetIndex
            direction < 0 && draggedCenter < target.midpoint -> targetIndex
            else -> null
        }
    }

    fun compensatedTop(lastKnownTop: Float, consumedScroll: Float): Float =
        lastKnownTop - consumedScroll

    fun startsOnHandle(pointerX: Float, pointerY: Float, bounds: Rect): Boolean =
        pointerX >= bounds.left && pointerX <= bounds.right &&
            pointerY >= bounds.top && pointerY <= bounds.bottom

    fun ghostTop(
        pointerY: Float,
        grabOffset: Float,
        itemHeight: Int,
        viewportStart: Float,
        viewportEnd: Float,
    ): Float = (pointerY - grabOffset).coerceIn(
        viewportStart,
        (viewportEnd - itemHeight).coerceAtLeast(viewportStart),
    )
}

internal data class ReorderDragSession(
    val id: String,
    val pointerY: Float,
    val grabOffset: Float,
    val itemHeight: Int,
    val lastKnownTop: Float,
    val direction: Int,
    val awaitingIndex: Int? = null,
)

/**
 * Stable-ID reorder component shared by spaces, favorites, cards and parameters.
 *
 * The pointer and ghost use viewport coordinates. Lazy item offsets are already in the same
 * coordinate space. Scrolling therefore never changes pointerY; the consumed scroll only moves
 * the saved fallback item geometry. Each frame reads fresh LazyList geometry before crossing one
 * adjacent midpoint, so stale offsets cannot produce a multi-move burst or oscillation.
 */
@Composable
fun <T> ReorderableList(
    items: List<T>,
    stableId: (T) -> String,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    canDrag: (T) -> Boolean = { true },
    itemContent: @Composable (
        item: T,
        dragging: Boolean,
        itemModifier: Modifier,
        dragHandle: @Composable () -> Unit,
    ) -> Unit,
) {
    val listState = rememberLazyListState()
    val latestItems by rememberUpdatedState(items)
    val latestCanDrag by rememberUpdatedState(canDrag)
    val latestOnMove by rememberUpdatedState(onMove)
    val handleBounds = remember { mutableMapOf<String, Rect>() }
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var drag by remember { mutableStateOf<ReorderDragSession?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val edgePx = ReorderDragPolicy.edgeZonePx(density)

    fun geometry() = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
        val item = latestItems.getOrNull(info.index) ?: return@mapNotNull null
        ReorderItemGeometry(info.index, info.offset.toFloat(), info.size, latestCanDrag(item))
    }

    fun attemptMove(direction: Int) {
        val current = drag ?: return
        val currentIndex = latestItems.indexOfFirst { stableId(it) == current.id }
        if (currentIndex < 0) {
            drag = null
            return
        }
        if (current.awaitingIndex != null) {
            if (currentIndex != current.awaitingIndex) return
            drag = current.copy(awaitingIndex = null)
        }
        val layout = listState.layoutInfo
        val top = ReorderDragPolicy.ghostTop(
            current.pointerY,
            current.grabOffset,
            current.itemHeight,
            layout.viewportStartOffset.toFloat(),
            layout.viewportEndOffset.toFloat(),
        )
        val target = ReorderDragPolicy.adjacentTarget(
            currentIndex,
            top + current.itemHeight / 2f,
            direction,
            geometry(),
        ) ?: return
        drag = current.copy(awaitingIndex = target)
        latestOnMove(currentIndex, target)
    }

    LaunchedEffect(items.map(stableId)) {
        val ids = items.mapTo(hashSetOf(), stableId)
        handleBounds.keys.retainAll(ids)
        if (drag?.id !in ids) drag = null
    }

    // Exactly one frame-driven job exists for the active drag. It scrolls and re-evaluates reorder
    // even while the user's finger is stationary at an edge.
    LaunchedEffect(drag?.id) {
        if (drag == null) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (isActive && drag != null) {
            val frame = withFrameNanos { it }
            val elapsed = (frame - previousFrame).coerceAtMost(100_000_000L)
            previousFrame = frame
            val current = drag ?: break
            val layout = listState.layoutInfo
            val velocity = ReorderDragPolicy.edgeVelocityPxPerSecond(
                current.pointerY,
                layout.viewportStartOffset.toFloat(),
                layout.viewportEndOffset.toFloat(),
                edgePx,
                density,
            )
            if (velocity != 0f) {
                val requested = ReorderDragPolicy.requestedScroll(velocity, elapsed)
                val consumed = listState.scrollBy(requested)
                val direction = velocity.sign.toInt()
                drag = (drag ?: break).copy(
                    lastKnownTop = ReorderDragPolicy.compensatedTop(current.lastKnownTop, consumed),
                    direction = direction,
                )
                attemptMove(direction)
            }
        }
    }

    val rootModifier = modifier
        .onGloballyPositioned { rootCoordinates = it }
        .pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    val hit = handleBounds.entries.lastOrNull {
                        ReorderDragPolicy.startsOnHandle(position.x, position.y, it.value)
                    }
                        ?: return@detectDragGesturesAfterLongPress
                    val id = hit.key
                    val index = latestItems.indexOfFirst { stableId(it) == id }
                    val item = latestItems.getOrNull(index) ?: return@detectDragGesturesAfterLongPress
                    if (!latestCanDrag(item)) return@detectDragGesturesAfterLongPress
                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                        ?: return@detectDragGesturesAfterLongPress
                    drag = ReorderDragSession(
                        id = id,
                        pointerY = position.y,
                        grabOffset = position.y - info.offset,
                        itemHeight = info.size,
                        lastKnownTop = info.offset.toFloat(),
                        direction = 0,
                    )
                },
                onDragCancel = { drag = null },
                onDragEnd = { drag = null },
                onDrag = { change, amount ->
                    val current = drag ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    val direction = amount.y.sign.toInt()
                    drag = current.copy(pointerY = change.position.y, direction = direction)
                    attemptMove(direction)
                },
            )
        }

    Box(rootModifier) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(items, key = { _, item -> stableId(item) }) { _, item ->
                val id = stableId(item)
                val dragging = drag?.id == id
                val itemModifier = Modifier
                    .padding(vertical = 3.dp)
                    .graphicsLayer { alpha = if (dragging) 0f else 1f }
                val handle: @Composable () -> Unit = {
                    DisposableEffect(id) {
                        onDispose { handleBounds.remove(id) }
                    }
                    Box(
                        Modifier
                            .size(48.dp)
                            .onGloballyPositioned { coordinates ->
                                val root = rootCoordinates
                                if (root != null && coordinates.isAttached && root.isAttached) {
                                    val topLeft = root.localPositionOf(coordinates, Offset.Zero)
                                    handleBounds[id] = Rect(
                                        topLeft,
                                        topLeft + Offset(
                                            coordinates.size.width.toFloat(),
                                            coordinates.size.height.toFloat(),
                                        ),
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (canDrag(item)) Text("⠿", style = MaterialTheme.typography.titleLarge)
                    }
                }
                itemContent(item, dragging, itemModifier, handle)
            }
        }

        val current = drag
        val draggedItem = current?.let { session -> items.firstOrNull { stableId(it) == session.id } }
        if (current != null && draggedItem != null) {
            val layout = listState.layoutInfo
            val ghostTop = ReorderDragPolicy.ghostTop(
                current.pointerY,
                current.grabOffset,
                current.itemHeight,
                layout.viewportStartOffset.toFloat(),
                layout.viewportEndOffset.toFloat(),
            )
            val inertHandle: @Composable () -> Unit = {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Text("⠿", style = MaterialTheme.typography.titleLarge)
                }
            }
            itemContent(
                draggedItem,
                true,
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, ghostTop.roundToInt()) }
                    .padding(vertical = 3.dp)
                    .zIndex(3f)
                    .graphicsLayer {
                        scaleX = 1.015f
                        scaleY = 1.015f
                        shadowElevation = 14f
                    },
                inertHandle,
            )
        }
    }
}

internal fun <T> moveStable(items: List<T>, fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) return items
    return items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}
