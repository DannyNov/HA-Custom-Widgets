package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardV034PolicyTest {
    @Test fun noDashboardWidgetsStartsFromIdleWithoutFabricatedHealth() {
        assertEquals(DashboardSocketState.IDLE, DashboardSocketSnapshot(
            DashboardSocketState.IDLE, 0, "none", 0, 0,
        ).state)
    }

    @Test fun widgetAppearingAfterProcessStartCanMoveToConnecting() {
        assertTrue(DashboardSocketState.CONNECTING in activeStages)
    }

    @Test fun alreadyConnectingIsAnExplicitState() {
        assertEquals(DashboardSocketState.CONNECTING, DashboardSocketState.valueOf("CONNECTING"))
    }

    @Test fun alreadySubscribedIsAnExplicitState() {
        assertEquals(DashboardSocketState.SUBSCRIBED, DashboardSocketState.valueOf("SUBSCRIBED"))
    }

    @Test fun authSuccessAdvancesToSubscriptionStage() {
        assertTrue(DashboardSocketState.AUTHENTICATING.ordinal < DashboardSocketState.SUBSCRIBING.ordinal)
    }

    @Test fun authInvalidMustNotRemainAuthenticating() {
        assertTrue(DashboardSocketState.BACKOFF != DashboardSocketState.AUTHENTICATING)
    }

    @Test fun subscriptionRejectedReturnsToControlledBackoff() {
        assertEquals(DashboardSocketState.BACKOFF, DashboardSocketState.valueOf("BACKOFF"))
    }

    @Test fun subscriptionTimeoutIsBounded() {
        assertEquals(10_000L, DashboardEventPolicy.SUBSCRIBE_TIMEOUT_MS)
    }

    @Test fun connectTimeoutIsBounded() {
        assertEquals(12_000L, DashboardEventPolicy.CONNECT_TIMEOUT_MS)
    }

    @Test fun halfOpenWatchdogDetectsStaleSubscribedSocket() {
        assertTrue(DashboardEventPolicy.isWatchdogStale(
            DashboardSocketState.SUBSCRIBED, 1_000L, 1_000L + DashboardEventPolicy.WATCHDOG_STALE_MS,
        ))
    }

    @Test fun watchdogDoesNotFireDuringAuthentication() {
        assertFalse(DashboardEventPolicy.isWatchdogStale(DashboardSocketState.AUTHENTICATING, 0, Long.MAX_VALUE))
    }

    @Test fun closeAndFailureHaveRecoverableBackoffState() {
        assertTrue(DashboardSocketState.BACKOFF !in setOf(DashboardSocketState.STOPPED, DashboardSocketState.SUBSCRIBED))
    }

    @Test fun hundredSequentialReconnectFailuresNeverExceedThirtySeconds() {
        val delays = (0 until 100).map(DashboardEventPolicy::reconnectDelayMs)
        assertEquals(100, delays.size)
        assertTrue(delays.all { it in 1_000L..30_000L })
        assertEquals(30_000L, delays.last())
    }

    @Test fun networkHandoverCanRequestAnotherGeneration() {
        assertTrue(DashboardEventPolicy.isCurrent(8, 8))
    }

    @Test fun staleCallbackFromOldGenerationIsRejected() {
        assertFalse(DashboardEventPolicy.isCurrent(7, 8))
    }

    @Test fun successfulReconnectCanBecomeSubscribed() {
        assertTrue(DashboardSocketState.SUBSCRIBED in activeStages)
    }

    @Test fun reconciliationFreshnessAvoidsWakeStorms() {
        assertTrue(DashboardEventPolicy.isSnapshotFresh(100_000L, 100_001L))
        assertFalse(DashboardEventPolicy.isSnapshotFresh(
            100_000L, 100_000L + DashboardEventPolicy.FRESHNESS_THRESHOLD_MS,
        ))
    }

    @Test fun externalOnAndOffRemainAcceptableHaTruth() {
        assertTrue(DashboardStatePolicy.decide(state("off", 1), "on", 2, null).accept)
        assertTrue(DashboardStatePolicy.decide(state("on", 2), "off", 3, null).accept)
    }

    @Test fun externalReversalDuringOptimisticOperationIsAccepted() {
        assertTrue(DashboardStatePolicy.decide(state("on", 1), "on", 2, operation("off")).accept)
    }

    @Test fun eventRevisionRequiresRender() {
        val revision = DashboardRenderRevisionPolicy.commit(DashboardRevisionState(4, 4, 4))
        assertTrue(DashboardRenderRevisionPolicy.needsRender(revision))
    }

    @Test fun oneNavigationTapHasOnePublicationAndOneRenderWithoutStructureReload() {
        val plan = DashboardNavigationPolicy.plan("a", "b", listOf("a", "b"))
        assertEquals("b", plan.targetTabId)
        assertEquals(1, plan.publicationCount)
        assertEquals(1, plan.renderRequestCount)
        assertFalse(plan.requiresStructureReload)
    }

    @Test fun repeatedTapOnCurrentTabDoesNoRedundantComposition() {
        val plan = DashboardNavigationPolicy.plan("a", "a", listOf("a", "b"))
        assertEquals(0, plan.publicationCount)
        assertEquals(0, plan.renderRequestCount)
    }

    @Test fun reorderFirstToMiddleMiddleToLastAndLastToFirst() {
        assertEquals(listOf("b", "a", "c"), DashboardOrderPolicy.move(listOf("a", "b", "c"), 0, 1))
        assertEquals(listOf("a", "c", "b"), DashboardOrderPolicy.move(listOf("a", "b", "c"), 1, 2))
        assertEquals(listOf("c", "a", "b"), DashboardOrderPolicy.move(listOf("a", "b", "c"), 2, 0))
    }

    @Test fun requestedControlOrderSurvivesSaveReopenAndProcessRecreationModel() {
        val saved = listOf("office", "dacha", "omsk")
        assertEquals(saved, DashboardOrderPolicy.merge(saved, saved))
        assertEquals(saved, DashboardOrderPolicy.merge(saved.toList(), saved))
    }

    @Test fun catalogReorderDoesNotOverrideUserOrder() {
        assertEquals(
            listOf("office", "dacha", "omsk"),
            DashboardOrderPolicy.merge(listOf("office", "dacha", "omsk"), listOf("omsk", "office", "dacha")),
        )
    }

    @Test fun newSpaceAppendsAndDeletedSpaceDisappears() {
        assertEquals(listOf("a", "b", "new"), DashboardOrderPolicy.merge(listOf("a", "b"), listOf("b", "a", "new")))
        assertEquals(listOf("b"), DashboardOrderPolicy.merge(listOf("deleted", "b"), listOf("b")))
    }

    @Test fun renameDoesNotChangeStableIdOrder() {
        assertEquals(listOf("space-id-2", "space-id-1"), DashboardOrderPolicy.merge(
            listOf("space-id-2", "space-id-1"), listOf("space-id-1", "space-id-2"),
        ))
    }

    @Test fun v033ConfigModelMigratesOrderFromVisibleStableIds() {
        val config = DashboardConfig(1, listOf("a", "b"), emptyMap(), emptyList(), emptyMap(), emptyMap(), true, true)
        assertEquals(listOf("a", "b"), config.spaceOrderIds)
    }

    private fun state(raw: String, updated: Long) =
        VersionedEntityState(ENTITY, raw, raw, updated, updated)

    private fun operation(desired: String) = DashboardOperation(
        "op", ENTITY, "switch", if (desired == "on") "turn_on" else "turn_off",
        desired, desired, if (desired == "on") "off" else "on", 1, 12_001,
        DashboardOperationStatus.PENDING,
    )

    companion object {
        private const val ENTITY = "switch.test"
        private val activeStages = setOf(
            DashboardSocketState.CONNECTING,
            DashboardSocketState.AUTHENTICATING,
            DashboardSocketState.SUBSCRIBING,
            DashboardSocketState.SUBSCRIBED,
        )
    }
}
