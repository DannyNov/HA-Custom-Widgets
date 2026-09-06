package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.HaEntity

/** Transport metadata may advance without changing anything the widget displays. */
object DashboardRefreshPolicy {
    const val COALESCE_MS = 180L

    fun samePayload(existing: VersionedEntityState?, incoming: HaEntity): Boolean = existing != null &&
        existing.confirmedRawState == incoming.state &&
        existing.confirmedDisplayState == incoming.displayState &&
        existing.timerDuration == incoming.timerDuration &&
        existing.timerRemaining == incoming.timerRemaining &&
        existing.timerFinishesAt == incoming.timerFinishesAt

    fun presentation(state: DashboardState): DashboardState = state.copy(
        stateRevision = 0,
        refreshInProgress = false,
        inFlightDeviceKeys = emptySet(),
        lastUpdatedMillis = if (state.config.showLastUpdated) state.lastUpdatedMillis / 60_000 * 60_000 else 0,
        operationStatusByEntity = state.operationStatusByEntity
            .filterValues { it != DashboardOperationStatus.CONFIRMED }
            .mapValues { if (it.value.isActive) DashboardOperationStatus.PENDING else it.value },
        scenarioRunStatusByEntity = state.scenarioRunStatusByEntity
            .mapValues { if (it.value.isActive) DashboardOperationStatus.PENDING else it.value },
    )
}
