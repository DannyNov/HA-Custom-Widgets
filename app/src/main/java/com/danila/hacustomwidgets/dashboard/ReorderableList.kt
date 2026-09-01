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
private const val MAX_EDGE_FRACTION = 0.20f
private const val EDGE_ARM_DELAY_NANOS = 150_000_000L
private const val MAX_EDGE_SPEED_DP_PER_SECOND = 920f

internal enum class ReorderEdgeZone(val direction: Int) {
    TOP(-1),
    NEUTRAL(0),
    BOTTOM(1),
}

internal enum class ReorderEdgePhase { NEUTRAL, CANDIDATE, ARMED }

internal data class ReorderEdgeIntent(
    val phase: ReorderEdgePhase = ReorderEdgePhase.NEUTRAL,
    val direction: Int = 0,
    val candidateSinceNanos: Long = 0L,
    val neutralSeen: Boolean = false,
    val previousZone: ReorderEdgeZone = ReorderEdgeZone.NEUTRAL,
)

internal data class ReorderItemGeometry(
    val index: Int,
    val top: Float,
    val size: Int,
    val draggable: Boolean = true,
) {
    val midpoint: Float get() = top + size / 2f
}

internal data class ReorderDragCoordinates(
    val logicalTop: Float,
    val logicalCenter: Float,
    val visualTop: Float,
)

/** Pure calculations kept outside Compose so density, scroll and midpoint behavior are testable. */
internal object ReorderDragPolicy {
    fun edgeZonePx(density: Float, edgeDp: Float = EDGE_ZONE_DP): Float = edgeDp * density

    fun effectiveEdgePx(
        viewportStart: Float,
        viewportEnd: Float,
        density: Float,
        edgeDp: Float = EDGE_ZONE_DP,
        maxFraction: Float = MAX_EDGE_FRACTION,
    ): Float {
        val viewportHeight = (viewportEnd - viewportStart).coerceAtLeast(0f)
        return minOf(edgeZonePx(density, edgeDp), viewportHeight * maxFraction.coerceIn(0f, 0.49f))
    }

    fun edgeZone(
        pointerY: Float,
        viewportStart: Float,
        viewportEnd: Float,
        effectiveEdgePx: Float,
    ): ReorderEdgeZone = when {
        pointerY < viewportStart + effectiveEdgePx -> ReorderEdgeZone.TOP
        pointerY > viewportEnd - effectiveEdgePx -> ReorderEdgeZone.BOTTOM
        else -> ReorderEdgeZone.NEUTRAL
    }

    fun initialEdgeIntent(zone: ReorderEdgeZone): ReorderEdgeIntent = ReorderEdgeIntent(
        neutralSeen = zone == ReorderEdgeZone.NEUTRAL,
        previousZone = zone,
    )

    fun updateEdgeIntent(
        current: ReorderEdgeIntent,
        zone: ReorderEdgeZone,
        pointerDirection: Int,
        nowNanos: Long,
    ): ReorderEdgeIntent {
        if (zone == ReorderEdgeZone.NEUTRAL) {
            return ReorderEdgeIntent(neutralSeen = true, previousZone = zone)
        }
        val zoneDirection = zone.direction
        if (current.phase == ReorderEdgePhase.ARMED) {
            return if (zoneDirection == current.direction &&
                (pointerDirection == 0 || pointerDirection == current.direction)
            ) {
                current.copy(previousZone = zone)
            } else {
                ReorderEdgeIntent(neutralSeen = false, previousZone = zone)
            }
        }
        if (current.phase == ReorderEdgePhase.CANDIDATE) {
            return if (zoneDirection == current.direction &&
                (pointerDirection == 0 || pointerDirection == current.direction)
            ) {
                current.copy(previousZone = zone)
            } else {
                ReorderEdgeIntent(neutralSeen = false, previousZone = zone)
            }
        }
        val intentionallyEntered = current.neutralSeen &&
            current.previousZone == ReorderEdgeZone.NEUTRAL &&
            pointerDirection == zoneDirection
        return if (intentionallyEntered) {
            ReorderEdgeIntent(
                phase = ReorderEdgePhase.CANDIDATE,
                direction = zoneDirection,
                candidateSinceNanos = nowNanos,
                neutralSeen = true,
                previousZone = zone,
            )
        } else {
            current.copy(previousZone = zone)
        }
    }

    fun advanceEdgeIntent(
        current: ReorderEdgeIntent,
        zone: ReorderEdgeZone,
        nowNanos: Long,
        armDelayNanos: Long = EDGE_ARM_DELAY_NANOS,
    ): ReorderEdgeIntent {
        if (current.phase == ReorderEdgePhase.ARMED) {
            return if (zone.direction == current.direction) current
            else ReorderEdgeIntent(neutralSeen = zone == ReorderEdgeZone.NEUTRAL, previousZone = zone)
        }
        if (current.phase != ReorderEdgePhase.CANDIDATE || zone.direction != current.direction) {
            return if (zone == ReorderEdgeZone.NEUTRAL) {
                ReorderEdgeIntent(neutralSeen = true, previousZone = zone)
            } else {
                current.copy(previousZone = zone)
            }
        }
        return if (nowNanos - current.candidateSinceNanos >= armDelayNanos) {
            current.copy(phase = ReorderEdgePhase.ARMED, previousZone = zone)
        } else {
            current.copy(previousZone = zone)
        }
    }

    fun armedDirection(intent: ReorderEdgeIntent): Int =
        if (intent.phase == ReorderEdgePhase.ARMED) intent.direction else 0

    fun armedVelocityPxPerSecond(
        intent: ReorderEdgeIntent,
        pointerY: Float,
        viewportStart: Float,
        viewportEnd: Float,
        effectiveEdgePx: Float,
        density: Float,
    ): Float {
        val direction = armedDirection(intent)
        if (direction == 0) return 0f
        val velocity = edgeVelocityPxPerSecond(
            pointerY, viewportStart, viewportEnd, effectiveEdgePx, density,
        )
        return if (velocity.sign.toInt() == direction) velocity else 0f
    }

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

    fun dragCoordinates(
        pointerY: Float,
        grabOffset: Float,
        itemHeight: Int,
        viewportStart: Float,
        viewportEnd: Float,
    ): ReorderDragCoordinates {
        val logicalTop = pointerY - grabOffset
        return ReorderDragCoordinates(
            logicalTop = logicalTop,
            logicalCenter = logicalTop + itemHeight / 2f,
            visualTop = logicalTop.coerceIn(
                viewportStart,
                (viewportEnd - itemHeight).coerceAtLeast(viewportStart),
            ),
        )
    }

    fun ghostTop(
        pointerY: Float,
        grabOffset: Float,
        itemHeight: Int,
        viewportStart: Float,
        viewportEnd: Float,
    ): Float = dragCoordinates(pointerY, grabOffset, itemHeight, viewportStart, viewportEnd).visualTop
}

internal data class ReorderDragSession(
    val id: String,
    val pointerY: Float,
    val grabOffset: Float,
    val itemHeight: Int,
    val lastKnownTop: Float,
    val direction: Int,
    val edgeIntent: ReorderEdgeIntent,
    val awaitingIndex: Int? = null,
)

/**
 * Stable-ID reorder component shared by spaces, favorites, cards and parameters.
 *
 * The pointer and Lazy item offsets use viewport coordinates. Logical drag geometry remains
 * unclamped for midpoint crossing, while only the overlay is clamped inside the viewport.
 * Scrolling therefore never changes pointerY; the consumed scroll only moves the saved fallback
 * item geometry. Each frame reads fresh LazyList geometry before crossing one adjacent midpoint,
 * so stale offsets cannot produce a multi-move burst or oscillation.
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

    fun currentEffectiveEdge(): Float {
        val layout = listState.layoutInfo
        return ReorderDragPolicy.effectiveEdgePx(
            layout.viewportStartOffset.toFloat(),
            layout.viewportEndOffset.toFloat(),
            density,
        )
    }

    fun currentEdgeZone(pointerY: Float): ReorderEdgeZone {
        val layout = listState.layoutInfo
        return ReorderDragPolicy.edgeZone(
            pointerY,
            layout.viewportStartOffset.toFloat(),
            layout.viewportEndOffset.toFloat(),
            currentEffectiveEdge(),
        )
    }

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
        val coordinates = ReorderDragPolicy.dragCoordinates(
            current.pointerY,
            current.grabOffset,
            current.itemHeight,
            layout.viewportStartOffset.toFloat(),
            layout.viewportEndOffset.toFloat(),
        )
        val target = ReorderDragPolicy.adjacentTarget(
            currentIndex,
            coordinates.logicalCenter,
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
            val zone = currentEdgeZone(current.pointerY)
            val intent = ReorderDragPolicy.advanceEdgeIntent(
                current.edgeIntent,
                zone,
                System.nanoTime(),
            )
            drag = current.copy(edgeIntent = intent)
            val armedDirection = ReorderDragPolicy.armedDirection(intent)
            if (armedDirection == 0) continue
            val effectiveEdge = currentEffectiveEdge()
            val velocity = ReorderDragPolicy.armedVelocityPxPerSecond(
                intent,
                current.pointerY,
                layout.viewportStartOffset.toFloat(),
                layout.viewportEndOffset.toFloat(),
                effectiveEdge,
                density,
            )
            if (velocity != 0f) {
                val requested = ReorderDragPolicy.requestedScroll(velocity, elapsed)
                val consumed = listState.scrollBy(requested)
                drag = (drag ?: break).copy(
                    lastKnownTop = ReorderDragPolicy.compensatedTop(current.lastKnownTop, consumed),
                    direction = armedDirection,
                )
                attemptMove(armedDirection)
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
                    val initialZone = currentEdgeZone(position.y)
                    drag = ReorderDragSession(
                        id = id,
                        pointerY = position.y,
                        grabOffset = position.y - info.offset,
                        itemHeight = info.size,
                        lastKnownTop = info.offset.toFloat(),
                        direction = 0,
                        edgeIntent = ReorderDragPolicy.initialEdgeIntent(initialZone),
                    )
                },
                onDragCancel = { drag = null },
                onDragEnd = { drag = null },
                onDrag = { change, amount ->
                    val current = drag ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    val direction = amount.y.sign.toInt()
                    val zone = currentEdgeZone(change.position.y)
                    val intent = ReorderDragPolicy.updateEdgeIntent(
                        current.edgeIntent,
                        zone,
                        direction,
                        System.nanoTime(),
                    )
                    drag = current.copy(
                        pointerY = change.position.y,
                        direction = direction,
                        edgeIntent = intent,
                    )
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
            val visualGhostTop = ReorderDragPolicy.dragCoordinates(
                current.pointerY,
                current.grabOffset,
                current.itemHeight,
                layout.viewportStartOffset.toFloat(),
                layout.viewportEndOffset.toFloat(),
            ).visualTop
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
                    .offset { IntOffset(0, visualGhostTop.roundToInt()) }
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
