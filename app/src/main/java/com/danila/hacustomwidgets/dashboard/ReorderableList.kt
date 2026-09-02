package com.danila.hacustomwidgets.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
private const val MOVEMENT_DEADBAND_DP = 3f
private const val MAX_EDGE_SPEED_DP_PER_SECOND = 920f
private const val INSERTION_GAP_FRACTION = 0.70f
private const val INSERTION_GAP_ANIMATION_MS = 120
private const val POST_DROP_SETTLING_MS = 150
internal const val REORDER_HANDLE_WIDTH_DP = 64
internal const val REORDER_HANDLE_HEIGHT_DP = 56

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
    val movementAnchorY: Float = Float.NaN,
    val lastMeaningfulMovementNanos: Long = 0L,
    val edgeEligible: Boolean = false,
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

/**
 * Presentation-only insertion gap. It maps unchanged underlying keys into the virtual order and
 * returns a graphics-layer translation. Because translation does not participate in measurement,
 * LazyListItemInfo offsets used by midpoint crossing remain stable while the gap animates.
 */
internal object ReorderGapPolicy {
    fun gapHeight(draggedItemHeight: Float, fraction: Float = INSERTION_GAP_FRACTION): Float =
        draggedItemHeight.coerceAtLeast(0f) * fraction.coerceAtLeast(0f)

    fun targetGapHeight(
        sourceIndex: Int,
        insertionIndex: Int,
        draggedItemHeight: Float,
    ): Float = if (sourceIndex == insertionIndex) 0f else gapHeight(draggedItemHeight)

    fun underlyingToVirtualIndex(
        sourceIndex: Int,
        insertionIndex: Int,
        underlyingIndex: Int,
    ): Int = when {
        insertionIndex > sourceIndex && underlyingIndex == sourceIndex -> insertionIndex
        insertionIndex > sourceIndex && underlyingIndex in (sourceIndex + 1)..insertionIndex ->
            underlyingIndex - 1
        insertionIndex < sourceIndex && underlyingIndex == sourceIndex -> insertionIndex
        insertionIndex < sourceIndex && underlyingIndex in insertionIndex until sourceIndex ->
            underlyingIndex + 1
        else -> underlyingIndex
    }

    /** Continuous counterpart used only while the visual insertion slot animates. */
    fun underlyingToAnimatedVirtualIndex(
        sourceIndex: Int,
        animatedInsertionIndex: Float,
        underlyingIndex: Int,
    ): Float = when {
        underlyingIndex > sourceIndex -> underlyingIndex -
            (animatedInsertionIndex - (underlyingIndex - 1f)).coerceIn(0f, 1f)
        underlyingIndex < sourceIndex -> underlyingIndex +
            ((underlyingIndex + 1f) - animatedInsertionIndex).coerceIn(0f, 1f)
        else -> animatedInsertionIndex
    }

    /**
     * Full virtual presentation: close the measured source slot, then open a 70% destination gap.
     * [presentationProgress] makes return-to-noop continuous without changing Lazy layout geometry.
     */
    fun activeTranslation(
        sourceIndex: Int,
        underlyingIndex: Int,
        animatedInsertionIndex: Float,
        itemCount: Int,
        sourceHeight: Float,
        presentationProgress: Float,
    ): Float {
        if (itemCount <= 1 || underlyingIndex == sourceIndex || sourceHeight <= 0f) return 0f
        val clampedInsertion = animatedInsertionIndex.coerceIn(0f, itemCount - 1f)
        val removedRank = underlyingIndex - if (underlyingIndex > sourceIndex) 1 else 0
        val sourceCompensation = if (underlyingIndex > sourceIndex) -sourceHeight else 0f
        // At integer t this is exactly [removedRank >= t], while adjacent retargets interpolate.
        val gapIndicator = (removedRank - clampedInsertion + 1f).coerceIn(0f, 1f)
        val target = sourceCompensation + gapHeight(sourceHeight) * gapIndicator
        return target * presentationProgress.coerceIn(0f, 1f)
    }
}

internal enum class ReorderSettlingPhase { WAITING_FOR_ORDER, ANIMATING_RESIDUAL }

internal data class ReorderSettlingPresentation(
    val pending: PendingViewportRestore,
    val preCommitVisualTops: Map<String, Float>,
    val ghostTop: Float,
    val itemHeight: Int,
    val phase: ReorderSettlingPhase = ReorderSettlingPhase.WAITING_FOR_ORDER,
    val residualTranslations: Map<String, Float> = emptyMap(),
)

internal data class ReorderReturningPresentation(
    val session: ReorderDragSession,
    val animatedInsertionIndex: Float,
    val animatedPresentationProgress: Float,
    val ghostStartTop: Float,
    val sourceTop: Float,
)

internal object ReorderCommitPresentationPolicy {
    fun screenTop(layoutTop: Float, translation: Float): Float = layoutTop + translation

    fun residualTranslation(preCommitScreenTop: Float, postCommitLayoutTop: Float): Float =
        preCommitScreenTop - postCommitLayoutTop

    fun displayedTop(postCommitLayoutTop: Float, residual: Float, progress: Float): Float =
        postCommitLayoutTop + residual * progress.coerceIn(0f, 1f)

    fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress.coerceIn(0f, 1f)
}

internal data class ReorderViewportSlot(val index: Int, val scrollOffset: Int)

internal data class PendingViewportRestore(
    val draggedId: String,
    val sourceIndex: Int,
    val insertionIndex: Int,
    val beforeOrder: List<String>,
    val expectedOrder: List<String>,
    val slot: ReorderViewportSlot,
)

internal enum class ViewportRestoreDecision { WAIT, APPLY, CANCEL }

internal object ReorderViewportPolicy {
    fun plan(
        commit: Boolean,
        draggedId: String,
        sourceIndex: Int,
        insertionIndex: Int,
        beforeOrder: List<String>,
        slot: ReorderViewportSlot,
    ): PendingViewportRestore? {
        if (!commit || sourceIndex !in beforeOrder.indices || insertionIndex !in beforeOrder.indices ||
            sourceIndex == insertionIndex || beforeOrder[sourceIndex] != draggedId
        ) return null
        return PendingViewportRestore(
            draggedId = draggedId,
            sourceIndex = sourceIndex,
            insertionIndex = insertionIndex,
            beforeOrder = beforeOrder,
            expectedOrder = expectedOrder(beforeOrder, sourceIndex, insertionIndex),
            slot = slot,
        )
    }

    fun expectedOrder(order: List<String>, sourceIndex: Int, insertionIndex: Int): List<String> {
        if (sourceIndex !in order.indices || insertionIndex !in order.indices || sourceIndex == insertionIndex) {
            return order
        }
        return order.toMutableList().apply { add(insertionIndex, removeAt(sourceIndex)) }
    }

    fun decision(pending: PendingViewportRestore, currentOrder: List<String>): ViewportRestoreDecision = when {
        currentOrder == pending.expectedOrder -> ViewportRestoreDecision.APPLY
        currentOrder == pending.beforeOrder -> ViewportRestoreDecision.WAIT
        currentOrder.size == pending.beforeOrder.size &&
            currentOrder.getOrNull(pending.insertionIndex) == pending.draggedId -> ViewportRestoreDecision.APPLY
        else -> ViewportRestoreDecision.CANCEL
    }

    fun target(slot: ReorderViewportSlot, itemCount: Int): ReorderViewportSlot? {
        if (itemCount <= 0) return null
        return ReorderViewportSlot(
            index = slot.index.coerceIn(0, itemCount - 1),
            scrollOffset = slot.scrollOffset.coerceAtLeast(0),
        )
    }
}

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

    fun initialEdgeIntent(zone: ReorderEdgeZone, pointerY: Float = Float.NaN): ReorderEdgeIntent = ReorderEdgeIntent(
        movementAnchorY = pointerY,
        edgeEligible = zone == ReorderEdgeZone.NEUTRAL,
        previousZone = zone,
    )

    fun updateEdgeIntent(
        current: ReorderEdgeIntent,
        zone: ReorderEdgeZone,
        pointerY: Float,
        movementDeadbandPx: Float,
        nowNanos: Long,
    ): ReorderEdgeIntent {
        if (zone == ReorderEdgeZone.NEUTRAL) {
            return ReorderEdgeIntent(
                movementAnchorY = pointerY,
                lastMeaningfulMovementNanos = nowNanos,
                edgeEligible = true,
                previousZone = zone,
            )
        }
        val zoneDirection = zone.direction
        if (current.phase == ReorderEdgePhase.ARMED) {
            if (zoneDirection != current.direction) {
                return current.copy(
                    phase = ReorderEdgePhase.NEUTRAL,
                    direction = 0,
                    movementAnchorY = pointerY,
                    lastMeaningfulMovementNanos = nowNanos,
                    previousZone = zone,
                )
            }
            val movement = pointerY - current.movementAnchorY
            val meaningful = kotlin.math.abs(movement) >= movementDeadbandPx
            return when {
                meaningful && movement.sign.toInt() != current.direction ->
                    current.copy(
                        phase = ReorderEdgePhase.NEUTRAL,
                        direction = 0,
                        movementAnchorY = pointerY,
                        lastMeaningfulMovementNanos = nowNanos,
                        previousZone = zone,
                    )
                meaningful -> current.copy(
                    movementAnchorY = pointerY,
                    lastMeaningfulMovementNanos = nowNanos,
                    previousZone = zone,
                )
                else -> current.copy(previousZone = zone)
            }
        }
        if (current.phase == ReorderEdgePhase.CANDIDATE) {
            if (zoneDirection != current.direction) {
                return current.copy(
                    phase = ReorderEdgePhase.NEUTRAL,
                    direction = 0,
                    movementAnchorY = pointerY,
                    lastMeaningfulMovementNanos = nowNanos,
                    previousZone = zone,
                )
            }
            val movement = pointerY - current.movementAnchorY
            val meaningful = kotlin.math.abs(movement) >= movementDeadbandPx
            return when {
                meaningful && movement.sign.toInt() != current.direction ->
                    current.copy(
                        phase = ReorderEdgePhase.NEUTRAL,
                        direction = 0,
                        movementAnchorY = pointerY,
                        lastMeaningfulMovementNanos = nowNanos,
                        previousZone = zone,
                    )
                meaningful -> current.copy(
                    movementAnchorY = pointerY,
                    lastMeaningfulMovementNanos = nowNanos,
                    previousZone = zone,
                )
                else -> current.copy(previousZone = zone)
            }
        }
        val anchor = current.movementAnchorY
        val displacement = if (anchor.isNaN()) 0f else pointerY - anchor
        val meaningful = kotlin.math.abs(displacement) >= movementDeadbandPx
        val outward = meaningful && displacement.sign.toInt() == zoneDirection
        return if (current.edgeEligible && outward) {
            ReorderEdgeIntent(
                phase = ReorderEdgePhase.CANDIDATE,
                direction = zoneDirection,
                candidateSinceNanos = nowNanos,
                movementAnchorY = pointerY,
                lastMeaningfulMovementNanos = nowNanos,
                edgeEligible = true,
                previousZone = zone,
            )
        } else if (meaningful) {
            current.copy(
                movementAnchorY = pointerY,
                lastMeaningfulMovementNanos = nowNanos,
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
            else current.copy(
                phase = ReorderEdgePhase.NEUTRAL,
                direction = 0,
                edgeEligible = current.edgeEligible || zone == ReorderEdgeZone.NEUTRAL,
                previousZone = zone,
            )
        }
        if (current.phase == ReorderEdgePhase.CANDIDATE && zone.direction != current.direction) {
            return current.copy(
                phase = ReorderEdgePhase.NEUTRAL,
                direction = 0,
                edgeEligible = current.edgeEligible || zone == ReorderEdgeZone.NEUTRAL,
                previousZone = zone,
            )
        }
        if (current.phase != ReorderEdgePhase.CANDIDATE || zone.direction != current.direction) {
            return if (zone == ReorderEdgeZone.NEUTRAL) {
                current.copy(
                    phase = ReorderEdgePhase.NEUTRAL,
                    direction = 0,
                    edgeEligible = true,
                    previousZone = zone,
                )
            } else {
                current.copy(previousZone = zone)
            }
        }
        return if (nowNanos - current.lastMeaningfulMovementNanos >= armDelayNanos) {
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

    /** Maps a position in the would-be reordered list back to the unchanged keyed data set. */
    fun virtualToUnderlyingIndex(
        sourceIndex: Int,
        insertionIndex: Int,
        virtualIndex: Int,
    ): Int = when {
        insertionIndex > sourceIndex && virtualIndex == insertionIndex -> sourceIndex
        insertionIndex > sourceIndex && virtualIndex in sourceIndex until insertionIndex -> virtualIndex + 1
        insertionIndex < sourceIndex && virtualIndex == insertionIndex -> sourceIndex
        insertionIndex < sourceIndex && virtualIndex in (insertionIndex + 1)..sourceIndex -> virtualIndex - 1
        else -> virtualIndex
    }

    /**
     * Advances only the virtual insertion cursor. The geometry still belongs to the unchanged
     * underlying LazyColumn, so the dragged stable key never changes index before drop.
     */
    fun adjacentInsertionTarget(
        sourceIndex: Int,
        insertionIndex: Int,
        draggedCenter: Float,
        direction: Int,
        itemCount: Int,
        geometry: List<ReorderItemGeometry>,
    ): Int? {
        if (direction == 0 || sourceIndex !in 0 until itemCount || insertionIndex !in 0 until itemCount) {
            return null
        }
        val step = if (direction > 0) 1 else -1
        var targetVirtualIndex = insertionIndex + step
        var target: ReorderItemGeometry? = null
        while (targetVirtualIndex in 0 until itemCount) {
            val targetUnderlyingIndex = virtualToUnderlyingIndex(
                sourceIndex,
                insertionIndex,
                targetVirtualIndex,
            )
            val candidate = geometry.firstOrNull { it.index == targetUnderlyingIndex } ?: return null
            if (candidate.draggable) {
                target = candidate
                break
            }
            targetVirtualIndex += step
        }
        val draggableTarget = target ?: return null
        return when {
            direction > 0 && draggedCenter > draggableTarget.midpoint -> targetVirtualIndex
            direction < 0 && draggedCenter < draggableTarget.midpoint -> targetVirtualIndex
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
    val sourceIndex: Int,
    val insertionIndex: Int,
    val pointerY: Float,
    val grabOffset: Float,
    val itemHeight: Int,
    val edgeIntent: ReorderEdgeIntent,
)

/**
 * Stable-ID reorder component shared by spaces, favorites, cards and parameters.
 *
 * The pointer and Lazy item offsets use viewport coordinates. Logical drag geometry remains
 * unclamped for midpoint crossing, while only the overlay is clamped inside the viewport.
 * The keyed [items] order remains immutable for the whole gesture. Midpoint crossings only move a
 * virtual insertion cursor; [onMove] is invoked once on drop. This prevents LazyColumn's stable-key
 * anchor correction from turning one adjacent move into a self-sustaining viewport/reorder loop.
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
    var pendingViewportRestore by remember { mutableStateOf<PendingViewportRestore?>(null) }
    var settling by remember { mutableStateOf<ReorderSettlingPresentation?>(null) }
    var returning by remember { mutableStateOf<ReorderReturningPresentation?>(null) }
    val settlingProgress = remember { Animatable(0f) }
    var returnProgressValue by remember { mutableStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val movementDeadbandPx = MOVEMENT_DEADBAND_DP * density
    val gapTargetIndex = drag?.insertionIndex?.toFloat() ?: 0f
    val animatedGapIndex by animateFloatAsState(
        targetValue = gapTargetIndex,
        animationSpec = tween(durationMillis = INSERTION_GAP_ANIMATION_MS),
        label = "reorder-gap-slot",
    )
    val presentationTargetProgress =
        if (drag != null && drag!!.insertionIndex != drag!!.sourceIndex) 1f else 0f
    val animatedPresentationProgress by animateFloatAsState(
        targetValue = presentationTargetProgress,
        animationSpec = tween(durationMillis = INSERTION_GAP_ANIMATION_MS),
        label = "reorder-presentation-progress",
    )

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

    fun attemptInsertionMove(direction: Int) {
        val current = drag ?: return
        if (latestItems.getOrNull(current.sourceIndex)?.let { stableId(it) } != current.id) {
            drag = null
            return
        }
        val layout = listState.layoutInfo
        val coordinates = ReorderDragPolicy.dragCoordinates(
            current.pointerY,
            current.grabOffset,
            current.itemHeight,
            layout.viewportStartOffset.toFloat(),
            layout.viewportEndOffset.toFloat(),
        )
        val target = ReorderDragPolicy.adjacentInsertionTarget(
            current.sourceIndex,
            current.insertionIndex,
            coordinates.logicalCenter,
            direction,
            latestItems.size,
            geometry(),
        ) ?: return
        drag = current.copy(insertionIndex = target)
    }

    fun activeTranslation(session: ReorderDragSession, index: Int): Float =
        ReorderGapPolicy.activeTranslation(
            sourceIndex = session.sourceIndex,
            underlyingIndex = index,
            animatedInsertionIndex = animatedGapIndex,
            itemCount = latestItems.size,
            sourceHeight = session.itemHeight.toFloat(),
            presentationProgress = animatedPresentationProgress,
        )

    fun ghostTop(session: ReorderDragSession): Float {
        val layout = listState.layoutInfo
        return ReorderDragPolicy.dragCoordinates(
            session.pointerY,
            session.grabOffset,
            session.itemHeight,
            layout.viewportStartOffset.toFloat(),
            layout.viewportEndOffset.toFloat(),
        ).visualTop
    }

    fun finishDrag(commit: Boolean) {
        val completed = drag ?: return
        val completedGhostTop = ghostTop(completed)
        if (!commit || completed.insertionIndex == completed.sourceIndex) {
            val sourceTop = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == completed.sourceIndex }?.offset?.toFloat()
                ?: completedGhostTop
            returning = ReorderReturningPresentation(
                session = completed,
                animatedInsertionIndex = animatedGapIndex,
                animatedPresentationProgress = animatedPresentationProgress,
                ghostStartTop = completedGhostTop,
                sourceTop = sourceTop,
            )
            returnProgressValue = 1f
            drag = null
            return
        }
        if (latestItems.getOrNull(completed.sourceIndex)?.let { stableId(it) } != completed.id ||
            completed.insertionIndex !in latestItems.indices
        ) {
            drag = null
            return
        }
        val beforeOrder = latestItems.map(stableId)
        val pending = ReorderViewportPolicy.plan(
            commit = true,
            draggedId = completed.id,
            sourceIndex = completed.sourceIndex,
            insertionIndex = completed.insertionIndex,
            beforeOrder = beforeOrder,
            slot = ReorderViewportSlot(
                index = listState.firstVisibleItemIndex,
                scrollOffset = listState.firstVisibleItemScrollOffset,
            ),
        ) ?: run {
            drag = null
            return
        }
        val preCommitTops = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val item = latestItems.getOrNull(info.index) ?: return@mapNotNull null
            val id = stableId(item)
            id to ReorderCommitPresentationPolicy.screenTop(
                info.offset.toFloat(),
                activeTranslation(completed, info.index),
            )
        }.toMap().toMutableMap().apply { put(completed.id, completedGhostTop) }
        settling = ReorderSettlingPresentation(
            pending = pending,
            preCommitVisualTops = preCommitTops,
            ghostTop = completedGhostTop,
            itemHeight = completed.itemHeight,
        )
        pendingViewportRestore = pending
        drag = null
        latestOnMove(completed.sourceIndex, completed.insertionIndex)
    }

    val itemIds = items.map(stableId)

    LaunchedEffect(itemIds) {
        val ids = itemIds.toHashSet()
        handleBounds.keys.retainAll(ids)
        val active = drag
        if (active != null && (
                active.id !in ids ||
                    items.getOrNull(active.sourceIndex)?.let { stableId(it) } != active.id
                )
        ) drag = null
    }

    LaunchedEffect(itemIds, pendingViewportRestore) {
        val pending = pendingViewportRestore ?: return@LaunchedEffect
        when (ReorderViewportPolicy.decision(pending, itemIds)) {
            ViewportRestoreDecision.WAIT -> Unit
            ViewportRestoreDecision.CANCEL -> {
                pendingViewportRestore = null
                settling = null
            }
            ViewportRestoreDecision.APPLY -> {
                val target = ReorderViewportPolicy.target(pending.slot, itemIds.size)
                if (target != null) {
                    listState.scrollToItem(target.index, target.scrollOffset)
                }
                val activeSettling = settling
                if (activeSettling != null && activeSettling.pending == pending) {
                    val residual = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                        val item = items.getOrNull(info.index) ?: return@mapNotNull null
                        val id = stableId(item)
                        val preTop = activeSettling.preCommitVisualTops[id] ?: return@mapNotNull null
                        id to ReorderCommitPresentationPolicy.residualTranslation(
                            preTop,
                            info.offset.toFloat(),
                        )
                    }.toMap()
                    settlingProgress.snapTo(1f)
                    settling = activeSettling.copy(
                        phase = ReorderSettlingPhase.ANIMATING_RESIDUAL,
                        residualTranslations = residual,
                    )
                }
                if (pendingViewportRestore == pending) pendingViewportRestore = null
            }
        }
    }

    LaunchedEffect(settling?.phase) {
        val active = settling ?: return@LaunchedEffect
        if (active.phase != ReorderSettlingPhase.ANIMATING_RESIDUAL) return@LaunchedEffect
        settlingProgress.animateTo(0f, tween(POST_DROP_SETTLING_MS))
        if (settling === active || settling?.pending == active.pending) settling = null
    }

    LaunchedEffect(returning?.session?.id) {
        val active = returning ?: return@LaunchedEffect
        Animatable(1f).animateTo(0f, tween(INSERTION_GAP_ANIMATION_MS)) {
            returnProgressValue = value
        }
        if (returning === active || returning?.session == active.session) returning = null
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
                listState.scrollBy(requested)
                attemptInsertionMove(armedDirection)
            }
        }
    }

    val rootModifier = modifier
        .onGloballyPositioned { rootCoordinates = it }
        .pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    if (drag != null || settling != null || returning != null) {
                        return@detectDragGesturesAfterLongPress
                    }
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
                        sourceIndex = index,
                        insertionIndex = index,
                        pointerY = position.y,
                        grabOffset = position.y - info.offset,
                        itemHeight = info.size,
                        edgeIntent = ReorderDragPolicy.initialEdgeIntent(initialZone, position.y),
                    )
                },
                onDragCancel = { finishDrag(commit = false) },
                onDragEnd = { finishDrag(commit = true) },
                onDrag = { change, amount ->
                    val current = drag ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    val direction = amount.y.sign.toInt()
                    val zone = currentEdgeZone(change.position.y)
                    val intent = ReorderDragPolicy.updateEdgeIntent(
                        current.edgeIntent,
                        zone,
                        change.position.y,
                        movementDeadbandPx,
                        System.nanoTime(),
                    )
                    drag = current.copy(
                        pointerY = change.position.y,
                        edgeIntent = intent,
                    )
                    attemptInsertionMove(direction)
                },
            )
        }

    Box(rootModifier) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(items, key = { _, item -> stableId(item) }) { index, item ->
                val id = stableId(item)
                val dragging = drag?.id == id
                val insertion = drag
                val returningNow = returning
                val settlingNow = settling
                val layoutTop = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == index }?.offset?.toFloat() ?: 0f
                val visualTranslation = when {
                    insertion != null -> activeTranslation(insertion, index)
                    returningNow != null -> ReorderGapPolicy.activeTranslation(
                        sourceIndex = returningNow.session.sourceIndex,
                        underlyingIndex = index,
                        animatedInsertionIndex = returningNow.animatedInsertionIndex,
                        itemCount = items.size,
                        sourceHeight = returningNow.session.itemHeight.toFloat(),
                        presentationProgress = returningNow.animatedPresentationProgress *
                            returnProgressValue,
                    )
                    settlingNow?.phase == ReorderSettlingPhase.WAITING_FOR_ORDER ->
                        settlingNow.preCommitVisualTops[id]?.minus(layoutTop) ?: 0f
                    settlingNow?.phase == ReorderSettlingPhase.ANIMATING_RESIDUAL ->
                        (settlingNow.residualTranslations[id] ?: 0f) * settlingProgress.value
                    else -> 0f
                }
                val itemAlpha = when {
                    dragging -> 0f
                    returningNow?.session?.id == id -> 1f - returnProgressValue
                    settlingNow?.phase == ReorderSettlingPhase.WAITING_FOR_ORDER &&
                        settlingNow.pending.draggedId == id -> 0f
                    else -> 1f
                }
                val itemModifier = Modifier
                    .padding(vertical = 3.dp)
                    .graphicsLayer {
                        alpha = itemAlpha
                        translationY = visualTranslation
                    }
                val handle: @Composable () -> Unit = {
                    DisposableEffect(id) {
                        onDispose { handleBounds.remove(id) }
                    }
                    Box(
                        Modifier
                            .width(REORDER_HANDLE_WIDTH_DP.dp)
                            .height(REORDER_HANDLE_HEIGHT_DP.dp)
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
        val returnNow = returning
        val settleNow = settling
        val presentationId = current?.id ?: returnNow?.session?.id
            ?: settleNow?.takeIf { it.phase == ReorderSettlingPhase.WAITING_FOR_ORDER }
                ?.pending?.draggedId
        val draggedItem = presentationId?.let { id -> items.firstOrNull { stableId(it) == id } }
        if (draggedItem != null && presentationId != null) {
            val layout = listState.layoutInfo
            val visualGhostTop = when {
                current != null -> ReorderDragPolicy.dragCoordinates(
                    current.pointerY,
                    current.grabOffset,
                    current.itemHeight,
                    layout.viewportStartOffset.toFloat(),
                    layout.viewportEndOffset.toFloat(),
                ).visualTop
                returnNow != null -> ReorderCommitPresentationPolicy.lerp(
                    returnNow.sourceTop,
                    returnNow.ghostStartTop,
                    returnProgressValue,
                )
                else -> settleNow!!.ghostTop
            }
            val ghostAlpha = if (returnNow != null) returnProgressValue else 1f
            val inertHandle: @Composable () -> Unit = {
                Box(
                    Modifier.width(REORDER_HANDLE_WIDTH_DP.dp).height(REORDER_HANDLE_HEIGHT_DP.dp),
                    contentAlignment = Alignment.Center,
                ) {
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
                        alpha = ghostAlpha
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
