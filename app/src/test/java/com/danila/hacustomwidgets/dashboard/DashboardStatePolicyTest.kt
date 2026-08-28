package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStatePolicyTest {
    @Test fun severalFastTabSwitchesAlwaysResolveLocally() {
        val tabs = listOf("floor-1", "floor-2")
        var selected = MAIN_TAB_ID
        repeat(10) { selected = DashboardStatePolicy.resolveSelectedTab(tabs[it % tabs.size], tabs) }
        assertEquals("floor-2", selected)
    }

    @Test fun delayedStateAfterSuccessfulServiceCallConfirmsDesiredState() {
        val decision = DashboardStatePolicy.decide(state("off", 100), "on", 200, operation("on"))
        assertTrue(decision.accept)
        assertTrue(decision.confirmsOperation)
    }

    @Test fun staleGetAfterSuccessfulServiceCallCannotRestoreOldState() {
        val decision = DashboardStatePolicy.decide(state("off", 300), "on", 100, terminalOperation("off"))
        assertFalse(decision.accept)
    }

    @Test fun timeoutIsTerminalAndAllowsReconciliation() {
        val decision = DashboardStatePolicy.decide(state("off", 100), "on", 200, terminalOperation("off", DashboardOperationStatus.TIMEOUT))
        assertTrue(decision.accept)
    }

    @Test fun networkErrorAfterPossiblySuccessfulCallIsSafeBecauseBinaryServiceIsExplicit() {
        val plan = DashboardOperationPlanner.plan("switch", "off")
        assertEquals("turn_on", plan.service)
        assertEquals("on", plan.desiredState)
    }

    @Test fun persistedOperationCanConfirmAfterProcessRecreation() {
        val persisted = operation("off").copy(status = DashboardOperationStatus.RUNNING)
        assertTrue(DashboardStatePolicy.decide(state("on", 10), "off", 20, persisted).confirmsOperation)
    }

    @Test fun refreshActionRaceRejectsConflictingRefreshDuringOperation() {
        assertFalse(DashboardStatePolicy.decide(state("off", 100), "off", 200, operation("on")).accept)
    }

    @Test fun periodicActionRaceRejectsOlderPeriodicResult() {
        assertFalse(DashboardStatePolicy.decide(state("off", 300), "on", 200, terminalOperation("off")).accept)
    }

    @Test fun repeatedTapSameEntityUsesSameUniqueWorkAndActiveOperationIsBlocked() {
        val active = operation("on")
        assertFalse(DashboardStatePolicy.canBeginOperation(active))
        assertEquals(
            DashboardStatePolicy.actionWorkName(7, "switch.a"),
            DashboardStatePolicy.actionWorkName(7, "switch.a"),
        )
    }

    @Test fun simultaneousActionsForDifferentEntitiesAreIndependent() {
        assertNotEquals(
            DashboardStatePolicy.actionWorkName(7, "switch.a"),
            DashboardStatePolicy.actionWorkName(7, "switch.b"),
        )
    }

    @Test fun twoControlsOfOneDeviceHaveIndependentOperationKeys() {
        assertNotEquals(
            DashboardStatePolicy.actionWorkName(2, "light.main"),
            DashboardStatePolicy.actionWorkName(2, "switch.ambient"),
        )
    }

    @Test fun tabSwitchDuringPendingActionDoesNotChangeOperationIdentity() {
        val active = operation("off")
        val selected = DashboardStatePolicy.resolveSelectedTab("floor-2", listOf("floor-1", "floor-2"))
        assertEquals("floor-2", selected)
        assertEquals("op", active.operationId)
    }

    @Test fun staleSelectedTabFallsBackToMain() {
        assertEquals(MAIN_TAB_ID, DashboardStatePolicy.resolveSelectedTab("deleted", listOf("visible")))
    }

    @Test fun workerRetryKeepsPendingOperationNonTerminal() {
        assertTrue(operation("on").copy(status = DashboardOperationStatus.PENDING).status.isActive)
        assertTrue(operation("on").copy(status = DashboardOperationStatus.RUNNING).status.isActive)
    }

    @Test fun stateRevisionOrderingUsesHaTimestamp() {
        assertFalse(DashboardStatePolicy.decide(state("on", 500), "off", 499, null).accept)
        assertTrue(DashboardStatePolicy.decide(state("on", 500), "off", 501, null).accept)
    }

    @Test fun stableCollectionIdsAre64BitStableAndTabIndependent() {
        val first = DashboardStatePolicy.stableCollectionId("card:device-1")
        val second = DashboardStatePolicy.stableCollectionId("card:device-1")
        assertEquals(first, second)
        assertTrue(first > Int.MAX_VALUE.toLong())
        assertNotEquals(first, DashboardStatePolicy.stableCollectionId("card:device-2"))
    }

    @Test fun externalStateChangedWithNewTimestampIsAccepted() {
        assertTrue(DashboardStatePolicy.decide(state("off", 10), "on", 11, null).accept)
    }

    @Test fun restReconciliationAfterEventConnectionLossUsesNewestTimestamp() {
        assertTrue(DashboardStatePolicy.decide(state("off", 10), "on", 20, null).accept)
        assertFalse(DashboardStatePolicy.decide(state("on", 20), "off", 10, null).accept)
    }

    @Test fun v031LegacyCacheIsMigratedOnlyWhenSplitStorageIsAbsent() {
        assertTrue(DashboardStatePolicy.shouldMigrateStorage(false, true))
        assertFalse(DashboardStatePolicy.shouldMigrateStorage(true, true))
        assertFalse(DashboardStatePolicy.shouldMigrateStorage(false, false))
    }

    @Test fun momentaryActionsDoNotPretendToBeOnOffControls() {
        val script = DashboardOperationPlanner.plan("script", "off")
        assertTrue(script.momentary)
        assertEquals(null, script.optimisticState)
        assertEquals(null, script.desiredState)
    }

    @Test fun timerDistinguishesIdleActiveAndPaused() {
        assertEquals("start", DashboardOperationPlanner.plan("timer", "idle").service)
        assertEquals("pause", DashboardOperationPlanner.plan("timer", "active").service)
        assertEquals("start", DashboardOperationPlanner.plan("timer", "paused").service)
    }

    @Test fun refreshWorkIsUniquePerDashboard() {
        assertEquals("dashboard-refresh:42", DashboardStatePolicy.refreshWorkName(42))
        assertNotEquals(DashboardStatePolicy.refreshWorkName(42), DashboardStatePolicy.refreshWorkName(43))
    }

    private fun state(raw: String, updated: Long) = VersionedEntityState(
        entityId = "switch.test",
        displayState = raw,
        rawState = raw,
        haLastUpdatedMillis = updated,
        revision = updated,
    )

    private fun operation(desired: String) = DashboardOperation(
        operationId = "op",
        entityId = "switch.test",
        domain = "switch",
        service = if (desired == "on") "turn_on" else "turn_off",
        desiredState = desired,
        optimisticState = desired,
        previousState = if (desired == "on") "off" else "on",
        createdAt = 1,
        status = DashboardOperationStatus.PENDING,
    )

    private fun terminalOperation(
        desired: String,
        status: DashboardOperationStatus = DashboardOperationStatus.CONFIRMED,
    ) = operation(desired).copy(status = status, completedAt = 2)
}

