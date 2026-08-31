package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.HaDeviceGroup
import com.danila.hacustomwidgets.data.model.HaEntity

enum class DashboardGrouping { ROOMS, TYPES, NONE }

enum class DeviceCategory(val title: String, val icon: String, val rank: Int) {
    CLIMATE_SENSORS("Климат и датчики", "🌡", 10),
    LIGHTING("Освещение", "💡", 20),
    SWITCHES("Розетки и выключатели", "🔌", 30),
    OPENINGS("Двери и окна", "🚪", 40),
    HVAC("Климатическое оборудование", "🌬", 50),
    MEDIA("Мультимедиа", "📺", 60),
    SECURITY("Безопасность", "🛡", 70),
    TIMERS("Таймеры", "⏱", 80),
    OTHER("Другое", "⚙", 90),
}

data class ServiceAction(val domain: String, val service: String)

enum class BatteryHealth { NORMAL, LOW, CRITICAL, NOT_BATTERY }

fun serviceAction(entity: HaEntity): ServiceAction? {
    if (entity.hiddenBy != null || entity.disabledBy != null) return null
    return when (entity.domain) {
        "light", "switch", "input_boolean" ->
            ServiceAction(entity.domain, if (entity.state == "on") "turn_off" else "turn_on")
        "button" -> ServiceAction("button", "press")
        "script" -> ServiceAction("script", "turn_on")
        "scene" -> ServiceAction("scene", "turn_on")
        "timer" -> ServiceAction("timer", if (entity.state == "active") "pause" else "start")
        else -> null
    }
}

fun deviceCategory(group: HaDeviceGroup): DeviceCategory {
    val entities = group.entities
    fun hasDomain(vararg domains: String) = entities.any { it.domain in domains }
    fun hasClass(vararg classes: String) = entities.any { it.deviceClass in classes }
    return when {
        hasDomain("light") -> DeviceCategory.LIGHTING
        hasClass("door", "window", "garage_door", "opening") -> DeviceCategory.OPENINGS
        hasDomain("climate", "fan", "humidifier", "water_heater") -> DeviceCategory.HVAC
        hasDomain("media_player", "remote") -> DeviceCategory.MEDIA
        hasDomain("alarm_control_panel", "lock", "siren") ||
            hasClass("smoke", "gas", "moisture", "safety") -> DeviceCategory.SECURITY
        hasDomain("timer") -> DeviceCategory.TIMERS
        hasDomain("switch", "input_boolean", "button", "script", "scene") -> DeviceCategory.SWITCHES
        hasDomain("sensor", "binary_sensor", "weather") -> DeviceCategory.CLIMATE_SENSORS
        else -> DeviceCategory.OTHER
    }
}

fun semanticMetricRank(entity: HaEntity): Int {
    val value = "${entity.deviceClass.orEmpty()} ${entity.entityId} ${entity.friendlyName}".lowercase()
    return when {
        entity.deviceClass == "temperature" || "temperature" in value || "температур" in value -> 10
        entity.deviceClass == "humidity" || "humidity" in value || "влажност" in value -> 20
        entity.domain in setOf("light", "switch", "input_boolean", "binary_sensor") -> 30
        entity.deviceClass in setOf("pressure", "illuminance", "power", "energy", "current", "voltage") -> 40
        entity.entityCategory == "diagnostic" -> 800
        entity.deviceClass == "battery" || "battery" in value || "батар" in value || "заряд" in value -> 1000
        else -> 100
    }
}

fun defaultMetricOrder(entities: List<HaEntity>): List<HaEntity> = entities
    .filterNot { it.entityCategory == "config" || it.hiddenBy != null || it.disabledBy != null }
    .sortedWith(compareBy<HaEntity>(::semanticMetricRank).thenBy { it.friendlyName.lowercase() })

fun metricIcon(metric: DashboardMetric): String {
    val value = "${metric.deviceClass.orEmpty()} ${metric.entityId} ${metric.label}".lowercase()
    return when {
        metric.deviceClass == "temperature" || "temperature" in value || "температур" in value -> "🌡"
        metric.deviceClass == "humidity" || "humidity" in value || "влажност" in value -> "💧"
        metric.deviceClass == "battery" || "battery" in value || "батар" in value || "заряд" in value -> "🔋"
        metric.deviceClass in setOf("door", "window", "garage_door", "opening") -> "🚪"
        metric.domain == "light" -> "💡"
        metric.domain in setOf("switch", "input_boolean") -> "🔌"
        metric.domain == "timer" -> "⏱"
        else -> "•"
    }
}

fun batteryHealth(metric: DashboardMetric): BatteryHealth {
    val value = "${metric.deviceClass.orEmpty()} ${metric.entityId} ${metric.label}".lowercase()
    if (metric.deviceClass != "battery" && "battery" !in value && "батар" !in value && "заряд" !in value) {
        return BatteryHealth.NOT_BATTERY
    }
    val percent = metric.rawState.replace(',', '.').toDoubleOrNull() ?: return BatteryHealth.NORMAL
    return when {
        percent <= 10.0 -> BatteryHealth.CRITICAL
        percent <= 20.0 -> BatteryHealth.LOW
        else -> BatteryHealth.NORMAL
    }
}

data class DashboardMetric(
    val entityId: String,
    val label: String,
    val state: String,
    val rawState: String,
    val domain: String,
    val deviceClass: String?,
)

data class DashboardControl(
    val entityId: String,
    val label: String,
    val domain: String,
    val state: String,
)

enum class DashboardOperationStatus {
    PENDING, RUNNING, CONFIRMED, FAILED, TIMEOUT, CANCELLED;

    val isActive: Boolean get() = this == PENDING || this == RUNNING
}

data class DashboardOperation(
    val operationId: String,
    val entityId: String,
    val domain: String,
    val service: String,
    val desiredState: String?,
    val optimisticState: String?,
    val previousState: String?,
    val createdAt: Long,
    val deadlineAt: Long,
    val status: DashboardOperationStatus,
    val completedAt: Long? = null,
    val error: String? = null,
)

data class VersionedEntityState(
    val entityId: String,
    val confirmedDisplayState: String,
    val confirmedRawState: String,
    val confirmedHaLastUpdatedMillis: Long?,
    val revision: Long,
    val optimisticOverlay: String? = null,
    val optimisticOperationId: String? = null,
) {
    val displayState: String get() = optimisticOverlay ?: confirmedDisplayState
    val rawState: String get() = optimisticOverlay ?: confirmedRawState
}

data class DashboardRevisionState(
    val committedRevision: Long,
    val requestedRenderRevision: Long,
    val renderedRevision: Long,
)

enum class DashboardStateSource { CATALOG, MANUAL_REFRESH, PERIODIC_REFRESH, ACTION, EVENT, RECONCILIATION, MIGRATION }

data class DashboardCard(
    val key: String,
    val title: String,
    val areaId: String?,
    val roomName: String?,
    val category: DeviceCategory,
    val metrics: List<DashboardMetric>,
    val controls: List<DashboardControl>,
)

data class DashboardSpace(
    val id: String,
    val name: String,
    val roomAreaIds: List<String>,
)

data class DashboardConfig(
    val appWidgetId: Int,
    val visibleSpaceIds: List<String>,
    val groupingBySpace: Map<String, DashboardGrouping>,
    val favoriteDeviceKeys: List<String>,
    val entityOrderByDevice: Map<String, List<String>>,
    val cardOrderBySpace: Map<String, List<String>>,
    val showLastUpdated: Boolean,
    val compactDensity: Boolean,
    val spaceOrderIds: List<String> = visibleSpaceIds,
)

data class DashboardState(
    val config: DashboardConfig,
    val spaces: List<DashboardSpace>,
    val cards: List<DashboardCard>,
    val selectedTabId: String,
    val collapsedSections: Set<String>,
    val inFlightDeviceKeys: Set<String>,
    val operationStatusByEntity: Map<String, DashboardOperationStatus>,
    val stateRevision: Long,
    val refreshInProgress: Boolean,
    val lastUpdatedMillis: Long,
    val error: String?,
) {
    val tabs: List<DashboardSpace> = listOf(DashboardSpace(MAIN_TAB_ID, "Главное", emptyList())) +
        DashboardOrderPolicy.merge(config.spaceOrderIds, spaces.map { it.id })
            .filter { it in config.visibleSpaceIds }
            .mapNotNull { id -> spaces.firstOrNull { it.id == id } }
    val selectedTab: DashboardSpace get() = tabs.firstOrNull { it.id == selectedTabId } ?: tabs.first()
}

const val MAIN_TAB_ID = "__main__"
