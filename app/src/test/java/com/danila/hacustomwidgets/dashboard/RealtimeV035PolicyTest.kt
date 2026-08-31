package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.remote.CompressedEntitySubscriptionParser
import com.danila.hacustomwidgets.data.remote.EntitySubscriptionMode
import com.danila.hacustomwidgets.data.remote.entitySubscriptionCommand
import com.danila.hacustomwidgets.data.remote.unsubscribeCommand
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeV035PolicyTest {
    @Test fun noWidgetsDoesNotOpenSocket() = assertFalse(open(0, DashboardSocketState.IDLE))
    @Test fun nlsConnectCanOpenSocket() = assertTrue(open(2, DashboardSocketState.IDLE))
    @Test fun duplicateNlsConnectDoesNotOpenSecondSocket() =
        assertFalse(open(2, DashboardSocketState.CONNECTING))

    @Test fun nlsBindingTransitionsAreIdempotent() {
        val state = RealtimeBindingState()
        assertTrue(state.connected("service-1"))
        assertFalse(state.connected("service-1"))
        assertTrue(state.isConnected)
        assertTrue(state.disconnected("service-1"))
        assertFalse(state.disconnected("service-1"))
        assertFalse(state.isConnected)
    }

    @Test fun staleDisconnectFromOldNlsInstanceCannotDropNewBinding() {
        val state = RealtimeBindingState()
        state.connected("old")
        state.connected("new")
        assertFalse(state.disconnected("old"))
        assertTrue(state.isConnected)
    }
    @Test fun healthyRebindDoesNotDuplicateSocket() =
        assertFalse(open(2, DashboardSocketState.SUBSCRIBED))
    @Test fun staleGenerationIsRejected() = assertFalse(DashboardEventPolicy.isCurrent(4, 5))
    @Test fun currentGenerationIsAccepted() = assertTrue(DashboardEventPolicy.isCurrent(5, 5))
    @Test fun processRecreationIdleCanRestore() = assertTrue(open(1, DashboardSocketState.IDLE))

    @Test fun dashboardAndDeviceEntitiesAreUnioned() {
        assertEquals(setOf("light.a", "switch.b"), RealtimeSubscriptionPolicy.union(
            listOf("light.a", "switch.b"), listOf("light.a"),
        ))
    }

    @Test fun duplicateEntityAcrossWidgetsSubscribesOnce() {
        assertEquals(1, RealtimeSubscriptionPolicy.union(listOf("light.a"), listOf("light.a")).size)
    }

    @Test fun widgetRemovalChangesSubscriptionSet() {
        assertEquals(setOf("light.a"), RealtimeSubscriptionPolicy.union(listOf("light.a"), emptyList()))
    }

    @Test fun widgetAdditionChangesSubscriptionSet() {
        assertEquals(2, RealtimeSubscriptionPolicy.union(listOf("light.a"), listOf("switch.b")).size)
    }

    @Test fun blankEntityIdsAreIgnored() =
        assertEquals(setOf("light.a"), RealtimeSubscriptionPolicy.union(listOf("", "light.a")))

    @Test fun modernServerSupportsTargetedSubscription() =
        assertTrue(DashboardEventPolicy.supportsSubscribeEntities("2026.8.0"))
    @Test fun minimumTargetedVersionIsSupported() =
        assertTrue(DashboardEventPolicy.supportsSubscribeEntities("2022.4.0"))
    @Test fun oldServerFallsBack() =
        assertFalse(DashboardEventPolicy.supportsSubscribeEntities("2021.12.10"))
    @Test fun malformedVersionFallsBack() =
        assertFalse(DashboardEventPolicy.supportsSubscribeEntities("unknown"))

    @Test fun targetedCommandContainsOnlyStableUniqueEntityIds() {
        val command = JSONObject(entitySubscriptionCommand(
            42, setOf("switch.b", "light.a"), EntitySubscriptionMode.SUBSCRIBE_ENTITIES,
        ))
        assertEquals("subscribe_entities", command.getString("type"))
        assertEquals(listOf("light.a", "switch.b"), listOf(
            command.getJSONArray("entity_ids").getString(0),
            command.getJSONArray("entity_ids").getString(1),
        ))
    }

    @Test fun legacyCommandSubscribesToStateChanged() {
        val command = JSONObject(entitySubscriptionCommand(
            43, setOf("light.a"), EntitySubscriptionMode.STATE_CHANGED,
        ))
        assertEquals("subscribe_events", command.getString("type"))
        assertEquals("state_changed", command.getString("event_type"))
    }

    @Test fun replacementUnsubscribesExactPreviousSubscription() {
        val command = JSONObject(unsubscribeCommand(44, 42))
        assertEquals("unsubscribe_events", command.getString("type"))
        assertEquals(42, command.getInt("subscription"))
    }

    @Test fun initialCompressedStatesAreAuthoritative() {
        val update = CompressedEntitySubscriptionParser().apply(JSONObject(
            """{"a":{"light.kitchen":{"s":"on","a":{"friendly_name":"Kitchen"},"lu":100.0,"lc":99.0}}}""",
        ))
        assertTrue(update.initial)
        assertEquals("on", update.entities.single().state)
        assertEquals("Kitchen", update.entities.single().friendlyName)
    }

    @Test fun incrementalCompressedStateChangesEntity() {
        val parser = CompressedEntitySubscriptionParser()
        parser.apply(JSONObject("""{"a":{"light.kitchen":{"s":"on","a":{},"lu":100.0}}}"""))
        val update = parser.apply(JSONObject("""{"c":{"light.kitchen":{"+":{"s":"off","lu":101.0}}}}"""))
        assertFalse(update.initial)
        assertEquals("off", update.entities.single().state)
    }

    @Test fun removedEntityBecomesUnavailableTruth() {
        val parser = CompressedEntitySubscriptionParser()
        parser.apply(JSONObject("""{"a":{"light.kitchen":{"s":"on","a":{},"lu":100.0}}}"""))
        val update = parser.apply(JSONObject("""{"r":["light.kitchen"]}"""))
        assertEquals("unavailable", update.entities.single().state)
    }

    @Test fun reconnectBackoffIsBoundedForHundredFailures() {
        assertTrue((0 until 100).map(DashboardEventPolicy::reconnectDelayMs).all { it in 1_000L..30_000L })
    }

    @Test fun screenOffDoesNotOpenTransport() =
        assertFalse(RealtimeSubscriptionPolicy.shouldOpenSocket(1, true, false, DashboardSocketState.IDLE))
    @Test fun missingConnectionDoesNotOpenTransport() =
        assertFalse(RealtimeSubscriptionPolicy.shouldOpenSocket(1, false, true, DashboardSocketState.IDLE))
    @Test fun manualRefreshRepairsUnsubscribedBoundSession() =
        assertTrue(RealtimeSubscriptionPolicy.reconnectAfterManualRefresh(true, DashboardSocketState.BACKOFF))
    @Test fun manualRefreshDoesNotReconnectHealthySession() =
        assertFalse(RealtimeSubscriptionPolicy.reconnectAfterManualRefresh(true, DashboardSocketState.SUBSCRIBED))
    @Test fun manualRefreshWithoutNlsStillUsesRestWithoutForcedSocketReplacement() =
        assertFalse(RealtimeSubscriptionPolicy.reconnectAfterManualRefresh(false, DashboardSocketState.BACKOFF))
    @Test fun navigationPolicyNeverRequiresStructureOrNetworkReload() {
        assertFalse(DashboardNavigationPolicy.plan("a", "b", listOf("a", "b")).requiresStructureReload)
    }

    @Test fun resourceCountersAreMonotonic() {
        val counters = RealtimeResourceCounters()
        counters.wsConnectionsOpened.incrementAndGet()
        counters.wsReconnects.addAndGet(2)
        counters.workerStarts.incrementAndGet()
        assertEquals(1L, counters.wsConnectionsOpened.get())
        assertEquals(2L, counters.wsReconnects.get())
        assertEquals(1L, counters.workerStarts.get())
    }

    private fun open(entityCount: Int, state: DashboardSocketState) =
        RealtimeSubscriptionPolicy.shouldOpenSocket(entityCount, true, true, state)
}
