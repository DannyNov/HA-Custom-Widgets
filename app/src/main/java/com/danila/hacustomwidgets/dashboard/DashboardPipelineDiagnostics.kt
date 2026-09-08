package com.danila.hacustomwidgets.dashboard

/** Observation only. No connection objects, raw entity states or credentials are accepted. */
internal object DashboardPipelineDiagnostics {
    fun event(id: Int, revision: Long?, stage: String, detail: String = "") {
        android.util.Log.i("HAWidgetPipeline", "widgetId=$id revision=$revision stage=$stage $detail")
    }

    fun config(stage: String, config: DashboardConfig?) {
        if (config == null) return
        val id = config.appWidgetId
        event(id, null, stage, "exists=true spaces=${config.visibleSpaceIds} groups=${config.groupingBySpace} favorites=${config.favoriteDeviceKeys}")
        config.cardOrderBySpace.forEach { (space, keys) -> event(id, null, stage, "space=$space cardOrder=$keys") }
        config.entityOrderByDevice.forEach { (key, ids) -> event(id, null, stage, "card=$key entityOrder=$ids") }
        event(id, null, stage, "hiddenCards=${config.hiddenDeviceIdsByContext} hiddenEntities=${config.hiddenEntityIdsByContext}")
    }

    fun model(state: DashboardState) {
        val sections = dashboardSections(state)
        event(state.config.appWidgetId, state.stateRevision, "MODEL_BUILT",
            "spaces=${state.spaces.size} tabs=${state.tabs.map { it.id }} selected=${state.selectedTab.id} totalCards=${state.cards.size} groups=${sections.size} logicalCards=${sections.sumOf { it.cards.size }} collapsed=${state.collapsedSections}")
        sections.forEach { event(state.config.appWidgetId, state.stateRevision, "MODEL_ORDER", "section=${it.key} cards=${it.cards.map { card -> card.key }}") }
    }
}
