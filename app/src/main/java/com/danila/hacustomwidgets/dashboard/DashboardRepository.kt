package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.util.Log
import com.danila.hacustomwidgets.data.MetricLabels
import com.danila.hacustomwidgets.data.model.HaCatalog
import com.danila.hacustomwidgets.data.model.HaDeviceGroup
import com.danila.hacustomwidgets.data.model.HaEntity
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

private data class DashboardStructureSnapshot(
    val spaces: List<DashboardSpace>,
    val cards: List<DashboardCard>,
    val scenarioActions: List<DashboardScenarioAction>,
    val entityIds: List<String>,
    val cardKeysBySpace: Map<String, List<String>>,
    val areaCardKeys: Map<String, List<String>>,
    val structureBytes: Int,
)

class DashboardRepository(context: Context) {
    private val timerResets = TimerResetStore(context)
    private val timerConnection = com.danila.hacustomwidgets.data.security.SecureConnectionStore(context)
    private val configPrefs = context.getSharedPreferences("dashboard_widgets", Context.MODE_PRIVATE)
    private val structurePrefs = context.getSharedPreferences("dashboard_structure", Context.MODE_PRIVATE)
    private val statePrefs = context.getSharedPreferences("dashboard_entity_states", Context.MODE_PRIVATE)
    private val operationPrefs = context.getSharedPreferences("dashboard_operations", Context.MODE_PRIVATE)
    private val atomicStore = DashboardAtomicStateStore(context)
    private val flows = ConcurrentHashMap<Int, MutableStateFlow<DashboardState?>>()
    private val structures = ConcurrentHashMap<Int, DashboardStructureSnapshot>()
    private val widgetsByEntity = ConcurrentHashMap<String, MutableSet<Int>>()
    @Volatile private var renderRequester: ((Int, Long, String) -> Unit)? = null
    @Volatile private var configurationChanged: ((String) -> Unit)? = null

    fun attachRenderRequester(requester: (Int, Long, String) -> Unit) {
        renderRequester = requester
    }

    fun attachConfigurationChanged(listener: (String) -> Unit) { configurationChanged = listener }

    @Synchronized
    fun saveConfiguration(config: DashboardConfig, catalog: HaCatalog) {
        configPrefs.edit()
            .putString(key(config.appWidgetId, "config"), config.toJson().toString())
            .putStringSet(KEY_IDS, configuredIds() + config.appWidgetId.toString())
            .apply()
        updateFromCatalog(config.appWidgetId, catalog)
    }

    fun getConfig(appWidgetId: Int): DashboardConfig? = configPrefs
        .getString(key(appWidgetId, "config"), null)
        ?.let { runCatching { parseConfig(JSONObject(it), appWidgetId) }.getOrNull() }

    @Synchronized
    fun get(appWidgetId: Int): DashboardState? = loadState(appWidgetId)

    @Synchronized
    fun observe(appWidgetId: Int): StateFlow<DashboardState?> = flows.getOrPut(appWidgetId) {
        val started = System.currentTimeMillis()
        MutableStateFlow(loadState(appWidgetId).also {
            Log.d(TAG, "local state/cache loaded widgetId=$appWidgetId durationMs=${System.currentTimeMillis() - started}")
        })
    }

    fun all(): List<DashboardConfig> = configuredIds().mapNotNull { it.toIntOrNull()?.let(::getConfig) }

    @Volatile var brightnessOverlay: (String) -> Int? = { null }

    private fun brightnessConnectionId(): String? = timerConnection.load()?.let {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((it.baseUrl + "\u0000" + it.token).toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    fun brightnessTruth(entityId: String): VersionedEntityState? = widgetsContainingEntity(entityId)
        .mapNotNull { atomicStore.read(it).entities[entityId] }
        .filter { it.brightnessConnectionId == brightnessConnectionId() }
        .maxByOrNull { it.confirmedHaLastUpdatedMillis ?: Long.MIN_VALUE }

    @Synchronized
    fun brightnessChanged(entityId: String) {
        widgetsContainingEntity(entityId).forEach { touchAndRequestRender(it, "BRIGHTNESS") }
    }

    fun entityIds(appWidgetId: Int): List<String> = structure(appWidgetId)?.entityIds.orEmpty()

    @Synchronized
    fun selectNextTimerDuration(appWidgetId: Int, deviceKey: String): Pair<DashboardCard, TimerDurationPreset>? {
        val serverUrl = timerConnection.load()?.baseUrl ?: return null
        val state = get(appWidgetId) ?: return null
        val card = state.cards.firstOrNull { it.key == deviceKey } ?: return null
        val config = state.config.autoOffTimersByDevice[deviceKey]?.takeIf {
            it.enabled && it.timerEntityId != null && AutoOffTimerPolicy.validate(it.durations)
        } ?: return null
        val presentation = card.timerState?.let { HaTimerPresentationPolicy.resolve(it, Instant.now()) }
        val next = AutoOffTimerPolicy.tapIndex(
            config,
            presentation?.status ?: HaTimerStatus.UNKNOWN,
            presentation?.remainingMillis,
            presentation?.actualDurationMinutes,
        )
        if (next !in config.durations.indices) return null
        val updated = state.config.copy(autoOffTimersByDevice = state.config.autoOffTimersByDevice +
            (deviceKey to config.copy(selectedDurationIndex = next)))
        configPrefs.edit().putString(key(appWidgetId, "config"), updated.toJson().toString()).apply()
        val primary = AutoOffTimerPolicy.resolveControl(card.controls, config) ?: return null
        val timerId = requireNotNull(config.timerEntityId)
        val baseline = atomicStore.read(appWidgetId, knownEntityIds(appWidgetId)).entities[timerId]
        val now = System.currentTimeMillis()
        timerResets.put(TimerReset(UUID.randomUUID().toString(), timerId, appWidgetId,
            primary.entityId, primary.domain, config.durations[next].minutes, now,
            baseline?.confirmedHaLastUpdatedMillis, now + config.durations[next].minutes * 60_000L,
            serverUrl = serverUrl, baselineFinishAt = TimerResetPolicy.timestamp(baseline?.timerFinishesAt)))
        touchAndRequestRender(appWidgetId, "TIMER_PRESET")
        return card to config.durations[next]
    }

    fun widgetsContainingEntity(entityId: String): List<Int> {
        all().forEach { structure(it.appWidgetId) }
        return widgetsByEntity[entityId]?.toList().orEmpty()
    }

    fun currentStateRevision(appWidgetId: Int): Long = revisionState(appWidgetId).committedRevision

    fun revisionState(appWidgetId: Int): DashboardRevisionState =
        atomicStore.read(appWidgetId, knownEntityIds(appWidgetId)).let {
            DashboardRevisionState(it.committedRevision, it.requestedRenderRevision, it.renderedRevision)
        }

    fun activeOperations(): List<Pair<Int, DashboardOperation>> = all().flatMap { config ->
        atomicStore.read(config.appWidgetId, knownEntityIds(config.appWidgetId)).operations.values
            .filter { it.status.isActive }
            .map { config.appWidgetId to it }
    }

    @Synchronized
    fun requiresCatalogRefresh(appWidgetId: Int, now: Long = System.currentTimeMillis()): Boolean {
        ensureMigrated(appWidgetId)
        val structure = structurePrefs.getString(structureKey(appWidgetId), null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: return true
        return structure.optInt("schema", 0) < STORAGE_SCHEMA_VERSION ||
            DashboardCatalogPolicy.isDue(structure.optLong("catalog_updated_at", 0L), now)
    }

    @Synchronized
    fun updateFromCatalog(appWidgetId: Int, catalog: HaCatalog) {
        val storedConfig = getConfig(appWidgetId) ?: return
        val catalogSpaceIds = catalog.spaces().map { it.id }
        val migrated = migrateLegacyUnassigned(storedConfig, catalog)
        val config = migrated.copy(
            // Missing objects are excluded by presentation, not erased from user preferences.
            spaceOrderIds = DashboardCustomizationPolicy.mergeRetainingMissing(migrated.spaceOrderIds, catalogSpaceIds),
        )
        if (config != storedConfig) {
            configPrefs.edit().putString(key(appWidgetId, "config"), config.toJson().toString()).apply()
        }
        val spaces = catalog.spaces().map { DashboardSpace(it.id, it.name, it.areaIds) }
        val areaNames = catalog.areas.associate { it.id to it.name }
        val allEntities = catalog.groups.flatMap { it.entities }.distinctBy { it.entityId }
        val entitiesById = allEntities.associateBy { it.entityId }
        val assignedTimerIds = CompositeTimerPresentationPolicy.assignedTimerIds(config.autoOffTimersByDevice)
        val previousCards = structure(appWidgetId)?.cards.orEmpty()
        val previousCatalogAt = structurePrefs.getString(structureKey(appWidgetId), null)
            ?.let { runCatching { JSONObject(it).optLong("catalog_updated_at", 0L) }.getOrDefault(0L) } ?: 0L
        // v0.6.1 sorted at render time; seed the retained order from what users actually saw.
        val previousCardOrder = (if (previousCatalogAt == 0L) previousCards.sortedBy { it.title.lowercase() }
            else previousCards).map { it.key }
        val cardsByKey = catalog.groups.mapNotNull { group ->
            group.copy(entities = group.entities.filterNot {
                it.domain in SCENARIO_DOMAINS || it.entityId in assignedTimerIds
            })
                .takeIf { it.entities.isNotEmpty() }
                ?.toDashboardCard(config, areaNames, entitiesById)
        }.associateBy { …11186 tokens truncated…                           .put("domain", metric.domain).put("class", metric.deviceClass)
                            .put("timer_duration", metric.timerDuration)
                            .put("timer_remaining", metric.timerRemaining)
                            .put("timer_finishes_at", metric.timerFinishesAt)
                    }),
            )
        }
    }

    private fun parseCards(array: JSONArray) = buildList {
        for (i in 0 until array.length()) array.getJSONObject(i).let { item ->
            val metrics = buildList {
                val source = item.optJSONArray("metrics") ?: JSONArray()
                for (m in 0 until source.length()) source.getJSONObject(m).let { metric ->
                    add(
                        DashboardMetric(
                            metric.getString("id"), metric.optString("label"), metric.optString("state"),
                            metric.optString("raw"), metric.optString("domain"), metric.optNullable("class"),
                            metric.optNullable("timer_duration"), metric.optNullable("timer_remaining"),
                            metric.optNullable("timer_finishes_at"),
                        ),
                    )
                }
            }
            val controls = buildList {
                val source = item.optJSONArray("controls")
                if (source != null) {
                    for (c in 0 until source.length()) source.getJSONObject(c).let { control ->
                        add(
                            DashboardControl(
                                control.getString("id"), control.optString("label"),
                                control.optString("domain"), control.optString("state"),
                            ),
                        )
                    }
                } else {
                    val legacyId = item.optNullable("control_entity")
                    val legacyDomain = item.optNullable("control_domain")
                    if (legacyId != null && legacyDomain != null) {
                        add(
                            DashboardControl(
                                legacyId, item.optString("title"), legacyDomain,
                                item.optNullable("control_state").orEmpty(),
                            ),
                        )
                    }
                }
            }
            add(
                DashboardCard(
                    item.getString("key"), item.optString("title"), item.optNullable("area"),
                    item.optNullable("room"),
                    runCatching { DeviceCategory.valueOf(item.optString("category")) }
                        .getOrDefault(DeviceCategory.OTHER),
                    metrics, controls,
                    item.optJSONObject("auto_off_timer")?.let { timer ->
                        val durations = buildList {
                            val values = timer.optJSONArray("durations") ?: JSONArray()
                            for (index in 0 until values.length()) values.getJSONObject(index).let {
                                add(TimerDurationPreset(it.optString("id", "preset-$index"), it.optInt("minutes")))
                            }
                        }
                        AutoOffTimerConfig(
                            timer.optBoolean("enabled"), timer.optNullable("timer_entity_id"),
                            durations.takeIf(AutoOffTimerPolicy::validate) ?: AutoOffTimerConfig.DEFAULT_TIMER_PRESETS,
                            timer.optInt("selected_index", -1),
                            timer.optNullable("control_entity_id"),
                        )
                    },
                    item.optJSONObject("timer_state")?.let { metric -> DashboardMetric(
                        metric.getString("id"), metric.optString("label"), metric.optString("state"),
                        metric.optString("raw"), metric.optString("domain"), metric.optNullable("class"),
                        metric.optNullable("timer_duration"), metric.optNullable("timer_remaining"),
                        metric.optNullable("timer_finishes_at"),
                    ) },
                ),
            )
        }
    }

    private fun mapOfListsJson(value: Map<String, List<String>>) = JSONObject().also { out ->
        value.forEach { (mapKey, list) -> out.put(mapKey, JSONArray(list)) }
    }

    private fun JSONObject?.mapOfLists(): Map<String, List<String>> = this?.let { json ->
        json.keys().asSequence().associateWith { json.optJSONArray(it).stringList() }
    }.orEmpty()

    private fun JSONObject?.stringMap(): Map<String, String> = this?.let { json ->
        json.keys().asSequence().associateWith { json.optString(it) }
    }.orEmpty()

    private fun JSONArray?.stringList(): List<String> = this?.let { array ->
        buildList { for (i in 0 until array.length()) add(array.getString(i)) }
    }.orEmpty()

    private fun JSONObject.optNullable(name: String): String? =
        optString(name).takeIf { !isNull(name) && it.isNotBlank() }

    private fun parseTimestamp(value: String?): Long? = value?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
    }

    private fun currentRevision(appWidgetId: Int) = configPrefs.getLong(key(appWidgetId, "revision"), 0L)
    private fun configuredIds(): Set<String> = configPrefs.getStringSet(KEY_IDS, emptySet())?.toSet().orEmpty()
    private fun key(id: Int, suffix: String) = "dashboard_${id}_$suffix"
    private fun structureKey(id: Int) = "dashboard_${id}_structure"
    private fun stateKey(id: Int, entityId: String) = "dashboard_${id}_state_$entityId"
    private fun operationKey(id: Int, entityId: String) = "dashboard_${id}_operation_$entityId"

    companion object {
        private const val TAG = "HAWidgetDashboard"
        private const val KEY_IDS = "configured_dashboard_ids"
        private const val STORAGE_SCHEMA_VERSION = 6
        private const val DEFAULT_METRIC_LIMIT = 5
        private const val TERMINAL_STATUS_VISIBLE_MS = 3_000L
    }
}
