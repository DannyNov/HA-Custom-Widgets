package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSynchronizationPolicyTest {
    @Test fun optimisticOffHaRemainsOnRollsBackToConfirmedTruth() {
        val finished = finish(record("on", "off", "off"), DashboardOperationStatus.TIMEOUT)
        assertEquals("on", finished.entities.getValue(ENTITY).rawState)
        assertEquals(null, finished.entities.getValue(ENTITY).optimisticOverlay)
    }

    @Test fun optimisticOnHaRemainsOffRollsBackToConfirmedTruth() {
        val finished = finish(record("off", "on", "on"), DashboardOperationStatus.TIMEOUT)
        assertEquals("off", finished.entities.getValue(ENTITY).rawState)
    }

    @Test fun http2xxWithoutStateChangeIsNotConfirmation() {
        assertTrue(record("on", "off", "off").operations.getValue(ENTITY).status.isActive)
    }

    @Test fun delayedDesiredStateConfirmsOnlyFromTruth() {
        val op = record("on", "off", "off").operations.getValue(ENTITY)
        assertTrue(DashboardStatePolicy.decide(state("on", 1), "off", 2, op).confirmsOperation)
    }

    @Test fun desiredThenImmediateHaReversalAcceptsNewestTruth() {
        val terminal = operation("off").copy(status = DashboardOperationStatus.CONFIRMED, completedAt = 2)
        assertTrue(DashboardStatePolicy.decide(state("off", 2), "on", 3, terminal).accept)
    }

    @Test fun retryBackoffBeyondDeadlineCannotExtendOperation() {
        assertTrue(DashboardStatePolicy.operationExpired(operation("off"), 12_001))
    }

    @Test fun processDeathAfterBeginOperationPreservesConfirmedAndOverlaySeparately() {
        val persisted = record("on", "off", "off")
        assertEquals("on", persisted.entities.getValue(ENTITY).confirmedRawState)
        assertEquals("off", persisted.entities.getValue(ENTITY).optimisticOverlay)
    }

    @Test fun processDeathAfterServiceCallDoesNotImplicitlyConfirm() {
        val restored = record("on", "off", "off")
        assertEquals(DashboardOperationStatus.PENDING, restored.operations.getValue(ENTITY).status)
    }

    @Test fun processDeathBeforeTerminalRenderLeavesDeliveryPending() {
        val terminal = finish(record("on", "off", "off"), DashboardOperationStatus.TIMEOUT)
        assertTrue(DashboardRenderRevisionPolicy.needsRender(terminal.revisions()))
    }

    @Test fun startupSweepMakesExpiredOperationTerminal() {
        val op = operation("off")
        assertTrue(DashboardStatePolicy.operationExpired(op, op.deadlineAt))
        assertFalse(finish(record("on", "off", "off"), DashboardOperationStatus.TIMEOUT)
            .operations.getValue(ENTITY).status.isActive)
    }

    @Test fun renderFailureAfterCommitRemainsPending() {
        val state = DashboardRenderRevisionPolicy.commit(DashboardRevisionState(4, 4, 4))
        assertTrue(DashboardRenderRevisionPolicy.needsRender(state))
    }

    @Test fun renderRetryCatchesCommittedRevision() {
        val pending = DashboardRenderRevisionPolicy.commit(DashboardRevisionState(4, 4, 4))
        assertFalse(DashboardRenderRevisionPolicy.needsRender(DashboardRenderRevisionPolicy.rendered(pending, 5)))
    }

    @Test fun hundredCommitsKeepMonotonicRevisions() {
        var state = DashboardRevisionState(0, 0, 0)
        repeat(100) { state = DashboardRenderRevisionPolicy.commit(state) }
        assertEquals(100, state.committedRevision)
        assertEquals(100, state.requestedRenderRevision)
    }

    @Test fun twoWidgetsWithOneEntityHaveIndependentDelivery() {
        val first = DashboardRenderRevisionPolicy.commit(DashboardRevisionState(1, 1, 1))
        val second = DashboardRenderRevisionPolicy.commit(DashboardRevisionState(7, 7, 7))
        assertEquals(2, first.committedRevision)
        assertEquals(8, second.committedRevision)
    }

    @Test fun oneWidgetRenderFailureDoesNotCancelOtherSuccess() {
        val failed = DashboardRenderRevisionPolicy.commit(DashboardRevisionState(1, 1, 1))
        val succeeded = DashboardRenderRevisionPolicy.rendered(failed, 2)
        assertTrue(DashboardRenderRevisionPolicy.needsRender(failed))
        assertFalse(DashboardRenderRevisionPolicy.needsRender(succeeded))
    }

    @Test fun newOperationHasOwnWorkIdentityWhileOldFinishes() {
        assertNotEquals(
            DashboardStatePolicy.actionWorkName(1, ENTITY, "old"),
            DashboardStatePolicy.actionWorkName(1, ENTITY, "new"),
        )
    }

    @Test fun webSocketEventWhileAliveCommitsConflictingTruth() {
        assertTrue(DashboardStatePolicy.decide(state("on", 1), "on", 2, operation("off")).accept)
    }

    @Test fun reconciliationAfterProcessStartAcceptsNewestSnapshot() {
        assertTrue(DashboardStatePolicy.decide(state("on", 1), "off", 2, null).accept)
    }

    @Test fun reconciliationAfterReconnectRejectsOlderSnapshot() {
        assertFalse(DashboardStatePolicy.decide(state("off", 20), "on", 19, null).accept)
    }

    @Test fun twentyRapidNavigationTapsEndAtLastTab() {
        val tabs = listOf("one", "two", "three")
        var selected = MAIN_TAB_ID
        repeat(20) { selected = DashboardStatePolicy.resolveSelectedTab(tabs[it % 3], tabs) }
        assertEquals("two", selected)
    }

    @Test fun navigationConcurrentWithActionCannotDecreaseRevision() {
        val action = DashboardRenderRevisionPolicy.commit(DashboardRevisionState(10, 10, 9))
        val navigation = DashboardRenderRevisionPolicy.commit(action)
        assertTrue(navigation.committedRevision > action.committedRevision)
    }

    @Test fun navigationConcurrentWithRefreshCoalescesToMaximumRevision() {
        var state = DashboardRevisionState(1, 1, 1)
        state = DashboardRenderRevisionPolicy.commit(state)
        state = DashboardRenderRevisionPolicy.commit(state)
        assertEquals(3, state.requestedRenderRevision)
    }

    @Test fun migrationV032KeepsOriginalAbsoluteDeadline() {
        val created = 100L
        assertEquals(created + DashboardStatePolicy.OPERATION_WINDOW_MS, 12_100L)
        assertTrue(DashboardStatePolicy.shouldMigrateStorage(false, true))
    }

    private fun finish(record: AtomicDashboardRecord, status: DashboardOperationStatus) =
        DashboardTerminalStateMachine.finish(record, ENTITY, "op", status, 20_000, "test")

    private fun record(confirmed: String, overlay: String, desired: String): AtomicDashboardRecord {
        val entity = state(confirmed, 1).copy(optimisticOverlay = overlay, optimisticOperationId = "op")
        return AtomicDashboardRecord(
            entities = mapOf(ENTITY to entity),
            operations = mapOf(ENTITY to operation(desired)),
            committedRevision = 1,
            requestedRenderRevision = 1,
            renderedRevision = 0,
        )
    }

    private fun state(raw: String, updated: Long) = VersionedEntityState(ENTITY, raw, raw, updated, updated)

    private fun operation(desired: String) = DashboardOperation(
        operationId = "op", entityId = ENTITY, domain = "switch",
        service = if (desired == "on") "turn_on" else "turn_off",
        desiredState = desired, optimisticState = desired,
        previousState = if (desired == "on") "off" else "on",
        createdAt = 1, deadlineAt = 12_001, status = DashboardOperationStatus.PENDING,
    )

    private fun AtomicDashboardRecord.revisions() = DashboardRevisionState(
        committedRevision, requestedRenderRevision, renderedRevision,
    )

    companion object { private const val ENTITY = "switch.test" }
}
