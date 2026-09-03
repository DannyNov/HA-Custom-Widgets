package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardV060ScenarioPolicyTest {
    private val action = DashboardScenarioAction(
        "automation.arrival", "Arrival", "automation", "on", "home",
    )

    @Test fun hiddenScenarioIsNotVisibleOrRunnable() {
        assertFalse(ScenarioDisplayPolicy.visible(action, setOf("automation"), setOf(action.entityId)))
        assertFalse(ScenarioDisplayPolicy.runnable(action.entityId, setOf(action.entityId), setOf(action.entityId)))
    }

    @Test fun runPermissionIsIndependentFromVisibility() {
        assertTrue(ScenarioDisplayPolicy.visible(action, setOf("automation"), emptySet()))
        assertFalse(ScenarioDisplayPolicy.runnable(action.entityId, emptySet(), emptySet()))
        assertTrue(ScenarioDisplayPolicy.runnable(action.entityId, emptySet(), setOf(action.entityId)))
    }

    @Test fun automationKeepsItsOnOffToggleWhileRunButtonRemainsOptional() {
        assertTrue(ScenarioDisplayPolicy.showStateToggle("automation"))
        assertTrue(ScenarioDisplayPolicy.exposeControl("automation", runnable = false))
        assertFalse(ScenarioDisplayPolicy.showStateToggle("script"))
        assertFalse(ScenarioDisplayPolicy.exposeControl("script", runnable = false))
        assertTrue(ScenarioDisplayPolicy.exposeControl("script", runnable = true))
    }

    @Test fun runFeedbackDoesNotReuseAutomationToggleStatus() {
        val toggle = operation(domain = "automation", service = "turn_off", desiredState = "off")
        val run = operation(domain = "automation", service = "trigger", desiredState = null)
        assertFalse(ScenarioDisplayPolicy.isRunOperation(toggle))
        assertTrue(ScenarioDisplayPolicy.isRunOperation(run))
    }

    @Test fun manualRunRemainsAvailableForDisabledAutomation() {
        assertTrue(ScenarioDisplayPolicy.runnable(action.entityId, emptySet(), setOf(action.entityId)))
        assertTrue(ScenarioDisplayPolicy.isRunOperation(
            operation(domain = "automation", service = "trigger", desiredState = null),
        ))
    }

    private fun operation(domain: String, service: String, desiredState: String?) = DashboardOperation(
        operationId = "op", entityId = action.entityId, domain = domain, service = service,
        desiredState = desiredState, optimisticState = null, previousState = null,
        createdAt = 0L, deadlineAt = 1L, status = DashboardOperationStatus.CONFIRMED,
    )
}
