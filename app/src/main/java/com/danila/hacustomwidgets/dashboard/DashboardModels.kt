package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.HaDeviceGroup
import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.data.model.HaCatalog
import java.time.Duration
import java.time.Instant
import java.util.UUID

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
        "light", "switch", "input_boolean", "automation" ->
            ServiceAction(entity.domain, if (entity.state == "on") "turn_off" else "turn_on")
        "button", "input_button" -> ServiceAction(entity.domain, "press")
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
    val timerDuration: String? = null,
    val timerRemaining: String? = null,
    val timerFinishesAt: String? = null,
)

data class TimerDurationPreset(val id: String, val minutes: Int) {
    companion object {
        fun create(minutes: Int) = TimerDurationPreset(UUID.randomUUID().toString(), minutes)
    }
}

data class AutoOffTimerConfig(
    val enabled: Boolean = false,
    val timerEntityId: String? = null,
    val durations: List<TimerDurationPreset> = DEFAULT_TIMER_PRESETS,
    val selectedDurationIndex: Int = -1,
) {
    companion object {
        val DEFAULT_TIMER_PRESETS = listOf(30, 60, 90, 120).mapIndexed { index, minutes ->
            TimerDurationPreset("default-$index", minutes)
        }
    }
}

object AutoOffTimerPolicy {
    fun eligible(domain: String): Boolean = domain in setOf("switch", "input_boolean", "light")
    fun validMinutes(value: Int): Boolean = value in 1..1440
    fun validate(values: List<TimerDurationPreset>): Boolean = values.isNotEmpty() &&
        values.all { validMinutes(it.minutes) } && values.map { it.minutes }.distinct().size == values.size

    fun nextIndex(config: AutoOffTimerConfig, actualDurationMinutes: Int?): Int {
        val values = config.durations
        if (values.isEmpty()) return -1
        if (actualDurationMinutes != null) {
            return values.indexOfFirst { it.minutes > actualDurationMinutes }.takeIf { it >= 0 } ?: 0
        }
        return 0
    }

    fun durationPayload(minutes: Int): String {
        val seconds = minutes * 60
        return "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }
}

data class TimerServiceCall(val domain: String, val service: String, val entityId: String, val data: Map<String, String> = emptyMap())

object CompositeTimerActionPolicy {
    fun start(primary: DashboardControl, primaryIsOn: Boolean, timerId: String, minutes: Int): List<TimerServiceCall> =
        buildList {
            if (!primaryIsOn) add(TimerServiceCall(primary.domain, "turn_on", primary.entityId))
            add(TimerServiceCall("timer", "start", timerId,
                mapOf("duration" to AutoOffTimerPolicy.durationPayload(minutes))))
        }

    fun powerOff(primary: DashboardControl, timerId: String?, timerState: String?): List<TimerServiceCall> =
        listOf(TimerServiceCall(primary.domain, "turn_off", primary.entityId)) +
            listOfNotNull(TimerServiceCall("timer", "cancel", timerId.orEmpty()).takeIf {
                timerId != null && timerState in setOf("active", "paused")
            })
}

object CompositeTimerPresentationPolicy {
    fun assignedTimerIds(configs: Map<String, AutoOffTimerConfig>): Set<String> = configs.values
        .filter { it.enabled }.mapNotNull { it.timerEntityId }.toSet()
}

enum class HaTimerStatus { IDLE, ACTIVE, PAUSED, UNAVAILABLE, UNKNOWN }

data class HaTimerPresentation(
    val status: HaTimerStatus,
    val remainingMillis: Long? = null,
    val actualDurationMinutes: Int? = null,
) {
    val formattedRemaining: String? get() = remainingMillis?.let(HaTimerPresentationPolicy::formatRemaining)
}

object HaTimerPresentationPolicy {
    fun resolve(metric: DashboardMetric, now: Instant): HaTimerPresentation {
        val status = when (metric.rawState) {
            "idle" -> HaTimerStatus.IDLE
            "active" -> HaTimerStatus.ACTIVE
            "paused" -> HaTimerStatus.PAUSED
            "unavailable" -> HaTimerStatus.UNAVAILABLE
            else -> HaTimerStatus.UNKNOWN
        }
        val durationMs = parseDuration(metric.timerDuration)
        val remaining = when {
            status == HaTimerStatus.ACTIVE && metric.timerFinishesAt != null -> runCatching {
                Duration.between(now, Instant.parse(metric.timerFinishesAt)).toMillis().coerceAtLeast(0)
            }.getOrNull() ?: parseDuration(metric.timerRemaining)
            status == HaTimerStatus.ACTIVE || status == HaTimerStatus.PAUSED -> parseDuration(metric.timerRemaining)
            else -> null
        }
        return HaTimerPresentation(status, remaining, durationMs?.let { (it / 60_000L).toInt() })
    }

    fun parseDuration(value: String?): Long? {
        val parts = value?.trim()?.split(':') ?: return null
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.toLongOrNull() ?: return null }
        val seconds = if (numbers.size == 3) numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
        else numbers[0] * 60 + numbers[1]
        return seconds.takeIf { it >= 0 }?.times(1_000)
    }

    fun formatRemaining(millis: Long): String {
        val total = (millis.coerceAtLeast(0) / 1_000)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }
}

object MetricPresentationPolicy {
    fun showLabel(metric: DashboardMetric): Boolean = HaEntityIconPolicy.resolve(
        metric.domain, metric.deviceClass,
    ) in setOf(HaSemanticIcon.GENERIC, HaSemanticIcon.SENSOR)
}

data class DashboardControl(
    val entityId: String,
    val label: String,
    val domain: String,
    val state: String,
)

enum class ScenarioActionKind { TOGGLE, RUN, ACTIVATE, PRESS, UNSUPPORTED }

data class DashboardScenarioAction(
    val entityId: String,
    val title: String,
    val domain: String,
    val state: String,
    val spaceId: String,
)

fun scenarioActionKind(domain: String): ScenarioActionKind = when (domain) {
    "automation" -> ScenarioActionKind.TOGGLE
    "script" -> ScenarioActionKind.RUN
    "scene" -> ScenarioActionKind.ACTIVATE
    "button", "input_button" -> ScenarioActionKind.PRESS
    else -> ScenarioActionKind.UNSUPPORTED
}

object ScenarioPolicy {
    fun resolveSpaceId(entityAreaId: String?, deviceAreaId: String?, catalog: HaCatalog): String =
        resolveSpaceId(entityAreaId ?: deviceAreaId, catalog)

    fun resolveSpaceId(areaId: String?, catalog: HaCatalog): String = areaId?.let { id ->
        catalog.spaces().firstOrNull { id in it.areaIds }?.id
    } ?: HaCatalog.UNASSIGNED_SPACE_ID

    fun orderedIds(saved: List<String>, current: List<String>): List<String> =
        DashboardOrderPolicy.merge(saved.distinct(), current.distinct())
}

sealed interface DashboardSettingsDestination {
    data object Empty : DashboardSettingsDestination
    data class Direct(val appWidgetId: Int) : DashboardSettingsDestination
    data class Choose(val appWidgetIds: List<Int>) : DashboardSettingsDestination
}

object DashboardSettingsLaunchPolicy {
    fun resolve(ids: List<Int>): DashboardSettingsDestination {
        val unique = ids.distinct()
        return when {
            unique.isEmpty() -> DashboardSettingsDestination.Empty
            unique.size == 1 -> DashboardSettingsDestination.Direct(unique.first())
            else -> DashboardSettingsDestination.Choose(unique)
        }
    }
}

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
    val timerDuration: String? = null,
    val timerRemaining: String? = null,
    val timerFinishesAt: String? = null,
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
    val autoOffTimer: AutoOffTimerConfig? = null,
    val timerState: DashboardMetric? = null,
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
    val scenariosEnabled: Boolean = true,
    val scenarioAutomationVisible: Boolean = true,
    val scenarioScriptVisible: Boolean = true,
    val scenarioOrderBySpaceAndDomain: Map<String, List<String>> = emptyMap(),
    val autoOffTimersByDevice: Map<String, AutoOffTimerConfig> = emptyMap(),
)

data class DashboardState(
    val config: DashboardConfig,
    val spaces: List<DashboardSpace>,
    val cards: List<DashboardCard>,
    val scenarioActions: List<DashboardScenarioAction>,
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
            .mapNotNull { id -> spaces.firstOrNull { it.id == id } } +
        listOfNotNull(DashboardSpace(SCENARIOS_TAB_ID, "Сценарии", emptyList()).takeIf { config.scenariosEnabled })
    val selectedTab: DashboardSpace get() = tabs.firstOrNull { it.id == selectedTabId } ?: tabs.first()
}

const val MAIN_TAB_ID = "__main__"
const val SCENARIOS_TAB_ID = "__scenarios__"

enum class HaSemanticIcon {
    LIGHT, SWITCH, SENSOR, BINARY_SENSOR, BUTTON, TOGGLE, THERMOSTAT, FAN, COVER, LOCK,
    MEDIA, CAMERA, AUTOMATION, SCRIPT, SCENE, TIMER, TEMPERATURE, HUMIDITY, BATTERY,
    DOOR, WINDOW, MOTION, OCCUPANCY, MOISTURE, SMOKE, CONNECTIVITY, SPACE, GENERIC,
}

object HaEntityIconPolicy {
    fun resolve(domain: String, deviceClass: String?): HaSemanticIcon = when (deviceClass) {
        "temperature" -> HaSemanticIcon.TEMPERATURE
        "humidity" -> HaSemanticIcon.HUMIDITY
        "battery" -> HaSemanticIcon.BATTERY
        "door", "garage_door", "opening" -> HaSemanticIcon.DOOR
        "window" -> HaSemanticIcon.WINDOW
        "motion" -> HaSemanticIcon.MOTION
        "occupancy", "presence" -> HaSemanticIcon.OCCUPANCY
        "moisture" -> HaSemanticIcon.MOISTURE
        "smoke", "gas" -> HaSemanticIcon.SMOKE
        "connectivity", "problem" -> HaSemanticIcon.CONNECTIVITY
        else -> when (domain) {
            "light" -> HaSemanticIcon.LIGHT
            "switch" -> HaSemanticIcon.SWITCH
            "sensor" -> HaSemanticIcon.SENSOR
            "binary_sensor" -> HaSemanticIcon.BINARY_SENSOR
            "button", "input_button" -> HaSemanticIcon.BUTTON
            "input_boolean" -> HaSemanticIcon.TOGGLE
            "climate" -> HaSemanticIcon.THERMOSTAT
            "fan" -> HaSemanticIcon.FAN
            "cover" -> HaSemanticIcon.COVER
            "lock" -> HaSemanticIcon.LOCK
            "media_player" -> HaSemanticIcon.MEDIA
            "camera" -> HaSemanticIcon.CAMERA
            "automation" -> HaSemanticIcon.AUTOMATION
            "script" -> HaSemanticIcon.SCRIPT
            "scene" -> HaSemanticIcon.SCENE
            "timer" -> HaSemanticIcon.TIMER
            else -> HaSemanticIcon.GENERIC
        }
    }

    fun primary(entities: List<HaEntity>): HaSemanticIcon = entities
        .filterNot { it.entityCategory == "diagnostic" }
        .minByOrNull { primaryRank(it) }
        ?.let { resolve(it.domain, it.deviceClass) }
        ?: entities.minByOrNull { primaryRank(it) }?.let { resolve(it.domain, it.deviceClass) }
        ?: HaSemanticIcon.GENERIC

    fun label(icon: HaSemanticIcon): String = when (icon) {
        HaSemanticIcon.LIGHT -> "Свет"
        HaSemanticIcon.SWITCH -> "Выключатель"
        HaSemanticIcon.SENSOR -> "Датчик"
        HaSemanticIcon.BINARY_SENSOR -> "Бинарный датчик"
        HaSemanticIcon.BUTTON -> "Кнопка"
        HaSemanticIcon.TOGGLE -> "Переключатель"
        HaSemanticIcon.THERMOSTAT -> "Климат"
        HaSemanticIcon.FAN -> "Вентилятор"
        HaSemanticIcon.COVER -> "Шторы и ворота"
        HaSemanticIcon.LOCK -> "Замок"
        HaSemanticIcon.MEDIA -> "Мультимедиа"
        HaSemanticIcon.CAMERA -> "Камера"
        HaSemanticIcon.AUTOMATION -> "Автоматизация"
        HaSemanticIcon.SCRIPT -> "Скрипт"
        HaSemanticIcon.SCENE -> "Сцена"
        HaSemanticIcon.TIMER -> "Таймер"
        HaSemanticIcon.TEMPERATURE -> "Температура"
        HaSemanticIcon.HUMIDITY -> "Влажность"
        HaSemanticIcon.BATTERY -> "Батарея"
        HaSemanticIcon.DOOR -> "Дверь"
        HaSemanticIcon.WINDOW -> "Окно"
        HaSemanticIcon.MOTION -> "Движение"
        HaSemanticIcon.OCCUPANCY -> "Присутствие"
        HaSemanticIcon.MOISTURE -> "Протечка"
        HaSemanticIcon.SMOKE -> "Дым"
        HaSemanticIcon.CONNECTIVITY -> "Связь"
        HaSemanticIcon.SPACE -> "Пространство"
        HaSemanticIcon.GENERIC -> "Сущность"
    }

    private fun primaryRank(entity: HaEntity): Int = when {
        entity.domain in setOf("light", "switch", "input_boolean", "button", "input_button", "climate", "fan", "cover", "lock", "media_player", "camera") -> 10
        entity.deviceClass == "temperature" -> 20
        entity.deviceClass == "humidity" -> 21
        entity.domain == "binary_sensor" && entity.deviceClass !in setOf("battery", "connectivity", "problem") -> 30
        entity.domain == "sensor" && entity.deviceClass !in setOf("battery", "connectivity") -> 40
        entity.entityCategory == "diagnostic" -> 90
        entity.deviceClass in setOf("battery", "connectivity") -> 100
        else -> 50
    }
}
