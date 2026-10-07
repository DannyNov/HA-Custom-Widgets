package com.danila.hacustomwidgets.data

import android.content.Context
import com.danila.hacustomwidgets.dashboard.DashboardEventCoordinator
import com.danila.hacustomwidgets.dashboard.DashboardRepository
import com.danila.hacustomwidgets.dashboard.DashboardRenderCoordinator
import com.danila.hacustomwidgets.dashboard.DashboardStartupCoordinator
import com.danila.hacustomwidgets.data.remote.HomeAssistantClient
import com.danila.hacustomwidgets.data.security.SecureConnectionStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val connectionScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val discovery = com.danila.hacustomwidgets.data.security.HomeAssistantDiscovery(context)
    private var rediscoveryJob: kotlinx.coroutines.Job? = null
    val connectionStore = SecureConnectionStore(context)
    private val oauthTransport = com.danila.hacustomwidgets.data.security.OAuthTransport()
    val connections = com.danila.hacustomwidgets.data.security.HAConnectionManager(connectionStore)
    val nativeServer = com.danila.hacustomwidgets.data.security.NativeServerApi()
    val oauth = com.danila.hacustomwidgets.data.security.HomeAssistantOAuth(connectionStore, oauthTransport, connections, nativeServer::inspect)
    val accessTokens = com.danila.hacustomwidgets.data.security.AccessTokenManager(connectionStore,
        { connection -> connections.refresh(connection, oauthTransport) })
    val client = HomeAssistantClient(accessTokens = accessTokens, connections = connections)
    val dashboards = DashboardRepository(context)
    val brightness = com.danila.hacustomwidgets.dashboard.BrightnessCoordinator(connectionStore, client, dashboards)
    init { dashboards.brightnessOverlay = brightness::overlay }
    val dashboardRenders = DashboardRenderCoordinator(context, dashboards)
    init { dashboards.attachRenderRequester(dashboardRenders::request) }
    val dashboardEvents = DashboardEventCoordinator(
        context, connectionStore, client, dashboards,
    )
    init {
        dashboardEvents.onTransportInvalidated = brightness::invalidate
        dashboards.attachConfigurationChanged(dashboardEvents::subscriptionSetChanged)
    }
    val dashboardStartup = DashboardStartupCoordinator(
        context, connectionStore, client, dashboards,
    )

    fun networkChanged(network: android.net.Network) {
        connections.networkChanged()
        dashboardEvents.connectivityChanged()
        rediscoveryJob?.cancel()
        rediscoveryJob = connectionScope.launch {
            val current = connectionStore.load() ?: return@launch
            if (current.isOAuth) runCatching {
                val endpoint = connections.resolve(current)
                val inspected = nativeServer.inspect(current.copy(token = accessTokens.token(current)), endpoint)
                connectionStore.updateMetadata(current, inspected.server)
            }
            val manager = appContext.getSystemService(android.net.ConnectivityManager::class.java)
            if (manager.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) != true) return@launch
            val checked = mutableSetOf<String>()
            kotlinx.coroutines.withTimeoutOrNull(12_000) {
                discovery.discover().collect { servers ->
                    val saved = connectionStore.load() ?: return@collect
                    val instance = saved.server.instanceId ?: return@collect
                    for (server in servers.filter { it.discoveryId == instance && checked.add(it.url) }) {
                        if (runCatching { nativeServer.verifyRediscovered(saved, server.url) }.getOrDefault(false)) {
                            synchronized(com.danila.hacustomwidgets.data.security.sessionLock) {
                                val latest = connectionStore.load()?.takeIf { it == saved } ?: return@synchronized
                                val route = com.danila.hacustomwidgets.data.security.ServerRoute(server.url, com.danila.hacustomwidgets.data.security.RouteKind.INTERNAL)
                                connectionStore.updateMetadata(latest, latest.server.copy(routes =
                                    (listOf(route) + latest.server.routes).distinctBy { it.url }.take(10)))
                            }
                            connections.networkChanged()
                        }
                    }
                }
            }
        }
    }
}
