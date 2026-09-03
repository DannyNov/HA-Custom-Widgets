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
}
