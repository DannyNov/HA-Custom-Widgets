package com.danila.hacustomwidgets.dashboard

import android.content.Context
import com.danila.hacustomwidgets.data.WidgetRepository
import com.danila.hacustomwidgets.data.model.HaCatalog
import com.danila.hacustomwidgets.data.model.HaDeviceGroup
import com.danila.hacustomwidgets.data.model.HaEntity
import org.json.JSONArray
import org.json.JSONObject

class DashboardRepository(context: Context) {
    private val prefs = context.getSharedPreferences("dashboard_widgets", Context.MODE_PRIVATE)

    fun saveConfiguration(config: DashboardConfig, catalog: HaCatalog) {
        prefs.edit()
            .putString(key(config.appWidgetId, "config"), config.toJson().toString())
            .putStringSet(KEY_IDS, configuredIds() + config.appWidgetId.toString())
            .apply()
        updateFromCatalog(config.appWidgetId, catalog)
    }

    fun getConfig(appWidgetId: Int): DashboardConfig? = prefs
        .getString(key(appWidgetId, "config"), null)
        ?.let { runCatching { parseConfig(JSONObject(it), appWidgetId) }.getOrNull() }

    fun get(appWidgetId: Int): DashboardState? {
        val config = getConfig(appWidgetId) ?: return null
        val cache = prefs.getString(key(appWidgetId, "cache"), null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: return null
        val spaces = parseSpaces(cache.optJSONArray("spaces") ?: JSONArray())
        val cards = parseCards(cache.optJSONArray("cards") ?: JSONArray())
        val tab = prefs.getString(key(appWidgetId, "selected_tab"), MAIN_TAB_ID) ?: MAIN_TAB_ID
        val collapsed = prefs.getStringSet(key(appWidgetId, "collapsed"), emptySet())?.toSet().orEmpty()
        val now = System.currentTimeMillis()
        val inFlight = prefs.getString(key(appWidgetId, "in_flight"), null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }?.let { json ->
            json.keys().asSequence().filter { json.optLong(it) + ACTION_TIMEOUT_MS > now }.toSet()
        }.orEmpty()
        return DashboardState(
            config = config,
            spaces = spaces,
            cards = cards,
            selectedTabId = tab,
            collapsedSections = collapsed,
            inFlightDeviceKeys = inFlight,
            lastUpdatedMillis = cache.optLong("updated"),
            error = cache.optString("error").takeIf { it.isNotBlank() },
        )
    }

    fun all(): List<DashboardConfig> = configuredIds().mapNotNull { it.toIntOrNull()?.let(::getConfig) }

    fun entityIds(appWidgetId: Int): List<String> = get(appWidgetId)?.cards.orEmpty()
        .flatMap { card -> card.metrics.map { it.entityId } + card.controls.map { it.entityId } }
        .distinct()

    fun requiresCatalogRefresh(appWidgetId: Int): Boolean {
        val cache = prefs.getString(key(appWidgetId, "cache"), null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: return true
        return cache.optInt("schema", 0) < CACHE_SCHEMA_VERSION
    }

    fun updateFromCatalog(appWidgetId: Int, catalog: HaCatalog) {
        val storedConfig = getConfig(appWidgetId) ?: return
        val config = migrateLegacyUnassigned(storedConfig, catalog)
        if (config != storedConfig) {
            prefs.edit().putString(key(appWidgetId, "config"), config.toJson().toString()).apply()
        }
        val spaces = catalog.spaces().map { DashboardSpace(it.id, it.name, it.areaIds) }
        val areaNames = catalog.areas.associate { it.id to it.name }
        val cards = catalog.groups.map { group -> group.toDashboardCard(config, areaNames) }
        val json = JSONObject()
            .put("schema", CACHE_SCHEMA_VERSION)
            .put("spaces", spacesJson(spaces))
            .put("cards", cardsJson(cards))
            .put("updated", System.currentTimeMillis())
        prefs.edit().putString(key(appWidgetId, "cache"), json.toString()).apply()
    }

    fun updateEntityStates(appWidgetId: Int, entities: List<HaEntity>) {
        val state = get(appWidgetId) ?: return
        val byId = entities.associateBy { it.entityId }
        val cards = state.cards.map { card ->
            val metrics = card.metrics.map { metric ->
                byId[metric.entityId]?.let { metric.copy(state = it.displayState, rawState = it.state) } ?: metric
            }
            val controls = card.controls.map { control ->
                byId[control.entityId]?.let { control.copy(state = it.state) } ?: control
            }
            card.copy(metrics = metrics, controls = controls)
        }
        writeCache(appWidgetId, state.spaces, cards, System.currentTimeMillis(), null)
    }

    fun saveError(appWidgetId: Int, message: String) {
        val state = get(appWidgetId) ?: return
        writeCache(appWidgetId, state.spaces, state.cards, state.lastUpdatedMillis, message)
    }

    fun setSelectedTab(appWidgetId: Int, tabId: String) {
        prefs.edit().putString(key(appWidgetId, "selected_tab"), tabId).apply()
    }

    fun toggleSection(appWidgetId: Int, sectionKey: String) {
        val current = prefs.getStringSet(key(appWidgetId, "collapsed"), emptySet())?.toSet().orEmpty()
        val updated = if (sectionKey in current) current - sectionKey else current + sectionKey
        prefs.edit().putStringSet(key(appWidgetId, "collapsed"), updated).apply()
    }

    @Synchronized
    fun tryBeginAction(appWidgetId: Int, deviceKey: String): Boolean {
        val now = System.currentTimeMillis()
        val json = prefs.getString(key(appWidgetId, "in_flight"), null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: JSONObject()
        val previous = json.optLong(deviceKey)
        if (previous > 0 && previous + ACTION_TIMEOUT_MS > now) return false
        json.put(deviceKey, now)
        prefs.edit().putString(key(appWidgetId, "in_flight"), json.toString()).commit()
        return true
    }

    fun finishAction(appWidgetId: Int, deviceKey: String) {
        val json = prefs.getString(key(appWidgetId, "in_flight"), null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: return
        json.remove(deviceKey)
        prefs.edit().putString(key(appWidgetId, "in_flight"), json.toString()).apply()
    }

    fun delete(appWidgetId: Int) {
        val editor = prefs.edit()
        listOf("config", "cache", "selected_tab", "collapsed", "in_flight").forEach {
            editor.remove(key(appWidgetId, it))
        }
        editor.putStringSet(KEY_IDS, configuredIds() - appWidgetId.toString()).apply()
    }

    private fun HaDeviceGroup.toDashboardCard(
        config: DashboardConfig,
        areaNames: Map<String, String>,
    ): DashboardCard {
        val requested = config.entityOrderByDevice[key]
        val ordered = if (requested.isNullOrEmpty()) {
            defaultMetricOrder(entities).take(DEFAULT_METRIC_LIMIT)
        } else {
            requested.mapNotNull { id -> entities.firstOrNull { it.entityId == id } }
        }
        val controls = entities.mapNotNull { entity ->
            serviceAction(entity)?.let {
                DashboardControl(
                    entityId = entity.entityId,
                    label = WidgetRepository.compactMetricName(title, entity.friendlyName),
                    domain = entity.domain,
                    state = entity.state,
                )
            }
        }
        return DashboardCard(
            key = key,
            title = title,
            areaId = effectiveAreaId,
            roomName = effectiveAreaId?.let(areaNames::get),
            category = deviceCategory(this),
            metrics = ordered.map { entity ->
                DashboardMetric(
                    entityId = entity.entityId,
                    label = WidgetRepository.compactMetricName(title, entity.friendlyName),
                    state = entity.displayState,
                    rawState = entity.state,
                    domain = entity.domain,
                    deviceClass = entity.deviceClass,
                )
            },
            controls = controls,
        )
    }

    private fun migrateLegacyUnassigned(config: DashboardConfig, catalog: HaCatalog): DashboardConfig {
        val legacyKey = HaDeviceGroup.UNASSIGNED_DEVICE_ID
        val replacementKeys = catalog.groups.filter { it.device == null }.map { it.key }
        if (replacementKeys.isEmpty()) return config
        fun replaceLegacy(items: List<String>) = items.flatMap { key ->
            if (key == legacyKey) replacementKeys else listOf(key)
        }.distinct()
        val hasLegacy = legacyKey in config.favoriteDeviceKeys ||
            config.cardOrderBySpace.values.any { legacyKey in it } ||
            legacyKey in config.entityOrderByDevice
        if (!hasLegacy) return config
        return config.copy(
            favoriteDeviceKeys = replaceLegacy(config.favoriteDeviceKeys),
            entityOrderByDevice = config.entityOrderByDevice - legacyKey,
            cardOrderBySpace = config.cardOrderBySpace.mapValues { replaceLegacy(it.value) },
        )
    }

    private fun writeCache(
        appWidgetId: Int,
        spaces: List<DashboardSpace>,
        cards: List<DashboardCard>,
        updated: Long,
        error: String?,
    ) {
        val json = JSONObject()
            .put("schema", CACHE_SCHEMA_VERSION)
            .put("spaces", spacesJson(spaces))
            .put("cards", cardsJson(cards))
            .put("updated", updated)
        if (error != null) json.put("error", error)
        prefs.edit().putString(key(appWidgetId, "cache"), json.toString()).apply()
    }

    private fun DashboardConfig.toJson() = JSONObject()
        .put("spaces", JSONArray(visibleSpaceIds))
        .put("grouping", JSONObject().also { out -> groupingBySpace.forEach { (k, v) -> out.put(k, v.name) } })
        .put("favorites", JSONArray(favoriteDeviceKeys))
        .put("entity_order", mapOfListsJson(entityOrderByDevice))
        .put("card_order", mapOfListsJson(cardOrderBySpace))
        .put("show_updated", showLastUpdated)
        .put("compact", compactDensity)

    private fun parseConfig(json: JSONObject, appWidgetId: Int) = DashboardConfig(
        appWidgetId = appWidgetId,
        visibleSpaceIds = json.optJSONArray("spaces").stringList(),
        groupingBySpace = json.optJSONObject("grouping").stringMap().mapValues {
            runCatching { DashboardGrouping.valueOf(it.value) }.getOrDefault(DashboardGrouping.TYPES)
        },
        favoriteDeviceKeys = json.optJSONArray("favorites").stringList(),
        entityOrderByDevice = json.optJSONObject("entity_order").mapOfLists(),
        cardOrderBySpace = json.optJSONObject("card_order").mapOfLists(),
        showLastUpdated = json.optBoolean("show_updated", true),
        compactDensity = json.optBoolean("compact", true),
    )

    private fun spacesJson(items: List<DashboardSpace>) = JSONArray().also { array ->
        items.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("areas", JSONArray(it.roomAreaIds))) }
    }

    private fun parseSpaces(array: JSONArray) = buildList {
        for (i in 0 until array.length()) array.getJSONObject(i).let {
            add(DashboardSpace(it.getString("id"), it.getString("name"), it.optJSONArray("areas").stringList()))
        }
    }

    private fun cardsJson(items: List<DashboardCard>) = JSONArray().also { array ->
        items.forEach { card ->
            array.put(
                JSONObject()
                    .put("key", card.key).put("title", card.title).put("area", card.areaId)
                    .put("room", card.roomName).put("category", card.category.name)
                    .put("controls", JSONArray().also { controls ->
                        card.controls.forEach { control ->
                            controls.put(
                                JSONObject().put("id", control.entityId).put("label", control.label)
                                    .put("domain", control.domain).put("state", control.state),
                            )
                        }
                    })
                    .put("metrics", JSONArray().also { metrics ->
                        card.metrics.forEach { metric ->
                            metrics.put(
                                JSONObject().put("id", metric.entityId).put("label", metric.label)
                                    .put("state", metric.state).put("raw", metric.rawState)
                                    .put("domain", metric.domain).put("class", metric.deviceClass),
                            )
                        }
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
                    runCatching { DeviceCategory.valueOf(item.optString("category")) }.getOrDefault(DeviceCategory.OTHER),
                    metrics, controls,
                ),
            )
        }
    }

    private fun mapOfListsJson(value: Map<String, List<String>>) = JSONObject().also { out ->
        value.forEach { (key, list) -> out.put(key, JSONArray(list)) }
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

    private fun JSONObject.optNullable(key: String): String? = optString(key).takeIf { !isNull(key) && it.isNotBlank() }
    private fun configuredIds(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet())?.toSet().orEmpty()
    private fun key(id: Int, suffix: String) = "dashboard_${id}_$suffix"

    companion object {
        private const val KEY_IDS = "configured_dashboard_ids"
        private const val CACHE_SCHEMA_VERSION = 2
        private const val DEFAULT_METRIC_LIMIT = 5
        private const val ACTION_TIMEOUT_MS = 10_000L
    }
}
