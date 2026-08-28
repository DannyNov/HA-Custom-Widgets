package com.danila.hacustomwidgets.dashboard

data class StateMergeDecision(
    val accept: Boolean,
    val confirmsOperation: Boolean = false,
    val reason: String,
)

object DashboardStatePolicy {
    fun decide(
        existing: VersionedEntityState?,
        incomingRawState: String,
        incomingHaLastUpdatedMillis: Long?,
        operation: DashboardOperation?,
    ): StateMergeDecision {
        if (operation?.status?.isActive == true && operation.desiredState != null) {
            return if (incomingRawState == operation.desiredState) {
                StateMergeDecision(true, true, "matches desired state")
            } else {
                StateMergeDecision(false, false, "active optimistic operation rejects conflicting state")
            }
        }
        val existingHa = existing?.haLastUpdatedMillis
        if (existingHa != null && incomingHaLastUpdatedMillis != null && incomingHaLastUpdatedMillis < existingHa) {
            return StateMergeDecision(false, false, "older Home Assistant timestamp")
        }
        return StateMergeDecision(true, false, "newest available state")
    }

    fun stableCollectionId(value: String): Long {
        var hash = -3750763034362895579L
        value.encodeToByteArray().forEach { byte ->
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 1099511628211L
        }
        return hash and Long.MAX_VALUE
    }

    fun resolveSelectedTab(stored: String?, visibleSpaceIds: List<String>): String {
        val valid = setOf(MAIN_TAB_ID) + visibleSpaceIds
        return stored?.takeIf { it in valid } ?: MAIN_TAB_ID
    }

    fun canBeginOperation(existing: DashboardOperation?): Boolean =
        existing?.status?.isActive != true

    fun actionWorkName(appWidgetId: Int, entityId: String): String =
        "dashboard-action:$appWidgetId:$entityId"

    fun refreshWorkName(appWidgetId: Int): String = "dashboard-refresh:$appWidgetId"

    fun shouldMigrateStorage(hasSplitStructure: Boolean, hasLegacyCache: Boolean): Boolean =
        !hasSplitStructure && hasLegacyCache
}

data class DashboardOperationPlan(
    val service: String,
    val desiredState: String?,
    val optimisticState: String?,
    val momentary: Boolean,
)

object DashboardOperationPlanner {
    fun plan(domain: String, currentState: String?): DashboardOperationPlan = when (domain) {
        "light", "switch", "input_boolean" -> {
            val desired = if (currentState == "on") "off" else "on"
            DashboardOperationPlan(
                service = if (desired == "on") "turn_on" else "turn_off",
                desiredState = desired,
                optimisticState = desired,
                momentary = false,
            )
        }
        "button" -> DashboardOperationPlan("press", null, null, true)
        "script", "scene" -> DashboardOperationPlan("turn_on", null, null, true)
        "timer" -> when (currentState) {
            "active" -> DashboardOperationPlan("pause", "paused", "paused", false)
            "paused" -> DashboardOperationPlan("start", "active", "active", false)
            else -> DashboardOperationPlan("start", "active", "active", false)
        }
        else -> error("Управление $domain пока не поддерживается")
    }
}
