package com.danila.hacustomwidgets.dashboard

object DashboardTerminalStateMachine {
    fun finish(
        record: AtomicDashboardRecord,
        entityId: String,
        operationId: String,
        terminalStatus: DashboardOperationStatus,
        now: Long,
        reason: String?,
    ): AtomicDashboardRecord {
        require(!terminalStatus.isActive)
        val operation = record.operations[entityId]
        if (operation?.operationId != operationId || !operation.status.isActive) return record
        val revision = record.committedRevision + 1
        val entity = record.entities[entityId]
        return record.copy(
            entities = if (entity == null) record.entities else record.entities + (entityId to entity.copy(
                revision = revision,
                optimisticOverlay = null,
                optimisticOperationId = null,
            )),
            operations = record.operations + (entityId to operation.copy(
                status = terminalStatus,
                completedAt = now,
                error = reason,
            )),
            committedRevision = revision,
            requestedRenderRevision = maxOf(record.requestedRenderRevision, revision),
        )
    }
}

object DashboardRenderRevisionPolicy {
    fun commit(state: DashboardRevisionState): DashboardRevisionState {
        val revision = state.committedRevision + 1
        return state.copy(
            committedRevision = revision,
            requestedRenderRevision = maxOf(state.requestedRenderRevision, revision),
        )
    }

    fun rendered(state: DashboardRevisionState, revision: Long): DashboardRevisionState =
        state.copy(renderedRevision = maxOf(state.renderedRevision, revision))

    fun needsRender(state: DashboardRevisionState): Boolean =
        maxOf(state.committedRevision, state.requestedRenderRevision) > state.renderedRevision
}
