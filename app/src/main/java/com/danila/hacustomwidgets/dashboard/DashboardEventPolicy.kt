package com.danila.hacustomwidgets.dashboard

import java.util.concurrent.atomic.AtomicReference

enum class DashboardSocketState {
    IDLE, CONNECTING, AUTHENTICATING, SUBSCRIBING, SUBSCRIBED, BACKOFF, STOPPED,
}

data class DashboardSocketSnapshot(
    val state: DashboardSocketState,
    val generation: Long,
    val connectionId: String,
    val lastMessageAt: Long,
    val lastEventAt: Long,
)

object DashboardEventPolicy {
    const val CONNECT_TIMEOUT_MS = 12_000L
    const val AUTH_REQUIRED_TIMEOUT_MS = 8_000L
    const val AUTH_OK_TIMEOUT_MS = 10_000L
    const val SUBSCRIBE_TIMEOUT_MS = 10_000L
    const val FRESHNESS_THRESHOLD_MS = 120_000L

    fun reconnectDelayMs(attempt: Int): Long = when (attempt.coerceAtLeast(0)) {
        0 -> 1_000L
        1 -> 2_000L
        2 -> 4_000L
        3 -> 8_000L
        4 -> 16_000L
        else -> 30_000L
    }

    fun isCurrent(expectedGeneration: Long, actualGeneration: Long): Boolean =
        expectedGeneration == actualGeneration

    fun supportsSubscribeEntities(version: String): Boolean {
        val parts = version.substringBefore('-').split('.').mapNotNull(String::toIntOrNull)
        val year = parts.getOrNull(0) ?: return false
        val month = parts.getOrNull(1) ?: return false
        return year > 2022 || (year == 2022 && month >= 4)
    }

    fun isSnapshotFresh(lastConfirmedSyncAt: Long, now: Long): Boolean =
        lastConfirmedSyncAt > 0L && now - lastConfirmedSyncAt < FRESHNESS_THRESHOLD_MS
}

object DashboardOrderPolicy {
    fun merge(userOrder: List<String>, catalogIds: List<String>): List<String> {
        val existing = catalogIds.toSet()
        return userOrder.filter { it in existing }.distinct() +
            catalogIds.filter { it !in userOrder }
    }

    fun move(order: List<String>, fromIndex: Int, toIndex: Int): List<String> {
        if (fromIndex !in order.indices || toIndex !in order.indices || fromIndex == toIndex) return order
        return order.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }
}

data class DashboardNavigationMutation(
    val targetTabId: String,
    val publicationCount: Int,
    val requiresStructureReload: Boolean,
    val renderRequestCount: Int,
)

object DashboardNavigationPolicy {
    fun plan(currentTabId: String, requestedTabId: String, visibleSpaceIds: List<String>): DashboardNavigationMutation {
        val target = DashboardStatePolicy.resolveSelectedTab(requestedTabId, visibleSpaceIds)
        val changed = target != currentTabId
        return DashboardNavigationMutation(
            targetTabId = target,
            publicationCount = if (changed) 1 else 0,
            requiresStructureReload = false,
            renderRequestCount = if (changed) 1 else 0,
        )
    }
}

object RealtimeSubscriptionPolicy {
    fun union(vararg consumers: Collection<String>): Set<String> =
        consumers.flatMap { it }.filter { it.isNotBlank() }.toSet()

    fun shouldOpenSocket(
        entityCount: Int,
        hasConnection: Boolean,
        screenInteractive: Boolean,
        state: DashboardSocketState,
    ): Boolean = entityCount > 0 && hasConnection && screenInteractive && state !in setOf(
        DashboardSocketState.CONNECTING,
        DashboardSocketState.AUTHENTICATING,
        DashboardSocketState.SUBSCRIBING,
        DashboardSocketState.SUBSCRIBED,
    )

    fun reconnectAfterManualRefresh(systemBound: Boolean, state: DashboardSocketState): Boolean =
        systemBound && state != DashboardSocketState.SUBSCRIBED
}

internal class RealtimeBindingState {
    private val currentId = AtomicReference<String?>(null)
    fun connected(bindingId: String): Boolean = currentId.getAndSet(bindingId) != bindingId
    fun disconnected(bindingId: String): Boolean = currentId.compareAndSet(bindingId, null)
    val isConnected: Boolean get() = currentId.get() != null
}
