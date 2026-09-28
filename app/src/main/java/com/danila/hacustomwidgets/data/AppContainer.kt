package com.danila.hacustomwidgets.data

import android.content.Context
import com.danila.hacustomwidgets.dashboard.DashboardEventCoordinator
import com.danila.hacustomwidgets.dashboard.DashboardRepository
import com.danila.hacustomwidgets.dashboard.DashboardRenderCoordinator
import com.danila.hacustomwidgets.dashboard.DashboardStartupCoordinator
import com.danila.hacustomwidgets.data.remote.HomeAssistantClient
import com.danila.hacustomwidgets.data.security.SecureConnectionStore

class AppContainer(context: Context) {
    val connectionStore = SecureConnectionStore(context)
    val client = HomeAssistantClient()
    val dashboards = DashboardRepository(context)
    val dashboardRenders = DashboardRenderCoordinator(context, dashboards)
    init { dashboards.attachRenderRequester(dashboardRenders::request) }
    val dashboardEvents = DashboardEventCoordinator(
        context, connectionStore, client, dashboards,
    )
    init {
        dashboards.attachConfigurationChanged(dashboardEvents::subscriptionSetChanged)
    }
    val dashboardStartup = DashboardStartupCoordinator(
        context, connectionStore, client, dashboards,
    )
}
