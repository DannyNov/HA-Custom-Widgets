package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.util.Log
import com.danila.hacustomwidgets.data.WidgetRepository
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

class DashboardRepository(context: Context) {
    private val configPrefs = context.getSharedPreferences("dashboard_widgets", Context.MODE_PRIVATE)
    private val structurePrefs = context.getSharedPreferences("dashboard_structure", Context.MODE_PRIVATE)
    private val statePrefs = context.getSharedPreferences("dashboard_entity_states", Context.MODE_PRIVATE)
    private val operationPrefs = context.getSharedPreferences("dashboard_operations", Context.MODE_PRIVATE)
    private val atomicStore = DashboardAtomicStateStore(context)
    private val flows = ConcurrentHashMap<Int, MutableStateFlow<DashboardState?>>()
    @Volatile private var renderRequester: ((Int, Long, String) -> Unit)? = null

    fun attachRenderRequester(requester: (Int, Long, String) -> Unit) {
        renderRequester = requester
    }

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
    fun get(appWidgetId: Int): DashboardState? = flows.getOrPut(appWidgetId) {
        val started = System.currentTimeMillis()
        MutableStateFlow(loadState(appWidgetId).also {
            Log.d(TAG, "local state loaded widgetId=$appWidgetId durationMs=${System.currentTimeMillis() - started}")
        })
    }.value

    @Synchronized
    fun observe(appWidgetId: Int): StateFlow<DashboardState?> = flows.getOrPut(appWidgetId) {
        val started = System.currentTimeMillis()
        MutableStateFlow(loadState(appWidgetId).also {
            Log.d(TAG, "local state/cache loaded widgetId=$appWidgetId durationMs=${System.currentTimeMillis() - started}")
        })
    }

    fun all(): List<DashboardConfig> = configuredIds().mapNotNull { it.toIntOrNull()?.let(::getConfig) }

    fun entityIds(appWidgetId: Int): List<String> = get(appWidgetId)?.cards.orEmpty()
        .flatMap { card -> card.metrics.map { it.entityId } + card.controls.map { it.entityId } }
        .distinct()

    fun widgetsContainingEntity(entityId: String): List<Int> = all()
        .map { it.appWidgetId }
        .filter { entityId in entityIds(it) }

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
    fun requiresCatalogRefresh(appWidgetId: Int): Boolean {
        ensureMigrated(appWidgetId)
        val structure = structurePrefs.getString(structureKey(appWidgetId), null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: return true
        return structure.optInt("schema", 0) < STORAGE_SCHEMA_VERSION
    }

    @Synchronized
    fun updateFromCatalog(appWidgetId: Int, catalog: HaCatalog) {
        val storedConfig = getConfig(appWidgetId) ?: return
        val config = migrateLegacyUnassigned(storedConfig, catalog)
        if (config != storedConfig) {
            configPrefs.edit().putString(key(appWidgetId, "config"), config.toJson().toString()).apply()
        }
        val spaces = catalog.spaces().map { DashboardSpace(it.id, it.name, it.areaIds) }
        val areaNames = catalog.areas.associate { it.id to it.name }
        val cards = catalog.groups.map { group -> group.toDashboardCard(config, areaNames) }
        val structure = JSONObject()
            .put("schema", STORAGE_SCHEMA_VERSION)
            .put("spaces", spacesJson(spaces))
            .put("cards", cardsJson(cards))
        structurePrefs.edit().putString(structureKey(appWidgetId), structure.toString()).apply()
        configPrefs.edit()
            .remove(key(appWidgetId, "error"))
            .apply()
        val entities = catalog.groups.flatMap { it.entities }.distinctBy { it.entityId }
        if (entities.isEmpty()) {
            configPrefs.edit().putLong(key(appWidgetId, "updated"), System.currentTimeMillis()).apply()
            touchAndRequestRender(appWidgetId, "CATALOG")
        } else {
            updateEntityStates(appWidgetId, entities, DashboardStateSource.CATALOG)
        }
    }

    @Synchronized
    fun updateEntityStates(
        appWidgetId: Int,
        entities: List<HaEntity>,
        source: DashboardStateSource = DashboardStateSource.MANUAL_REFRESH,
    ): Long {
        var accepted = 0
        val after = commitAndRequestRender(appWidgetId, source.name) { before ->
            val stateMap = before.entities.toMutableMap()
            val operationMap = before.operations.toMutableMap()
            var revision = before.committedRevision
            entities.forEach { entity ->
                val existing = stateMap[entity.entityId]
                val operation = operationMap[entity.entityId]
                val incomingMillis = parseTimestamp(entity.lastUpdated)
                val decision = DashboardStatePolicy.decide(existing, entity.state, incomingMillis, operation)
                Log.d(
                    TAG,
                    "MERGE_DECISION widgetId=$appWidgetId operationId=${operation?.operationId} " +
                        "entityId=${entity.entityId} source=$source haState=${entity.state} " +
                        "confirmed=${existing?.confirmedRawState} overlay=${existing?.optimisticOverlay} " +
                        "desired=${operation?.desiredState} accept=${decision.accept} reason=${decision.reason}",
                )
                if (!decision.accept) return@forEach
                accepted += 1
                revision += 1
                val updated = VersionedEntityState(
                    entityId = entity.entityId,
                    confirmedDisplayState = entity.displayState,
                    confirmedRawState = entity.state,
                    confirmedHaLastUpdatedMillis = incomingMillis,
                    revision = revision,
                    optimisticOverlay = existing?.optimisticOverlay,
                    optimisticOperationId = existing?.optimisticOperationId,
                )
                stateMap[entity.entityId] = if (decision.confirmsOperation && operation != null) {
                    operationMap[entity.entityId] = operation.copy(
                        status = DashboardOperationStatus.CONFIRMED,
                        completedAt = System.currentTimeMillis(),
                        error = null,
                    )
                    Log.i(TAG, "OPERATION_TERMINAL operationId=${operation.operationId} entityId=${entity.entityId} status=CONFIRMED reason=ha-truth")
                    updated.copy(optimisticOverlay = null, optimisticOperationId = null)
                } else {
                    updated
                }
                Log.d(TAG, "CONFIRMED_STATE_COMMIT widgetId=$appWidgetId entityId=${entity.entityId} confirmed=${entity.state} revision=$revision")
            }
            if (accepted == 0) before else before.copy(
                entities = stateMap,
                operations = operationMap,
                committedRevision = revision,
                requestedRenderRevision = maxOf(before.requestedRenderRevision, revision),
            )
        }
        if (accepted > 0) {
            configPrefs.edit().putLong(key(appWidgetId, "updated"), System.currentTimeMillis())
                .remove(key(appWidgetId, "error")).apply()
        }
        Log.d(TAG, "state applied widgetId=$appWidgetId source=$source accepted=$accepted revision=${after.committedRevision}")
        return after.committedRevision
    }

    @Synchronized
    fun beginOperation(appWidgetId: Int, entityId: String, domain: String): DashboardOperation? {
        val existingOperation = getOperation(appWidgetId, entityId)
        if (!DashboardStatePolicy.canBeginOperation(existingOperation)) return null
        val record = atomicStore.read(appWidgetId, knownEntityIds(appWidgetId))
        val current = record.entities[entityId]?.confirmedRawState ?: get(appWidgetId)?.cards
            ?.asSequence()?.flatMap { it.controls.asSequence() }
            ?.firstOrNull { it.entityId == entityId }?.state
        val plan = DashboardOperationPlanner.plan(domain, current)
        val createdAt = System.currentTimeMillis()
        val operation = DashboardOperation(
            operationId = UUID.randomUUID().toString(),
            entityId = entityId,
            domain = domain,
            service = plan.service,
            desiredState = plan.desiredState,
            optimisticState = plan.optimisticState,
            previousState = current,
            createdAt = createdAt,
            deadlineAt = createdAt + DashboardStatePolicy.OPERATION_WINDOW_MS,
            status = DashboardOperationStatus.PENDING,
        )
        val after = commitAndRequestRender(appWidgetId, "ACTION") { before ->
            val revision = before.committedRevision + 1
            val old = before.entities[entityId] ?: VersionedEntityState(
                entityId, current.orEmpty(), current.orEmpty(), null, before.committedRevision,
            )
            before.copy(
                entities = before.entities + (entityId to old.copy(
                    revision = revision,
                    optimisticOverlay = plan.optimisticState,
                    optimisticOperationId = operation.operationId.takeIf { plan.optimisticState != null },
                )),
                operations = before.operations + (entityId to operation),
                committedRevision = revision,
                requestedRenderRevision = maxOf(before.requestedRenderRevision, revision),
            )
        }
        Log.d(TAG, "OPTIMISTIC_OVERLAY_SET operationId=${operation.operationId} entityId=$entityId overlay=${plan.optimisticState}")
        Log.d(
            TAG,
            "operation created operationId=${operation.operationId} widgetId=$appWidgetId entityId=$entityId " +
                "desiredState=${operation.desiredState} deadline=${operation.deadlineAt} revision=${after.committedRevision}",
        )
        return operation
    }

    fun getOperation(appWidgetId: Int, entityId: String): DashboardOperation? =
        atomicStore.read(appWidgetId, knownEntityIds(appWidgetId)).operations[entityId]

    @Synchronized
    fun setOperationStatus(
        appWidgetId: Int,
        entityId: String,
        operationId: String,
        status: DashboardOperationStatus,
        error: String? = null,
    ): Boolean {
        if (!status.isActive) return finishOperation(appWidgetId, entityId, operationId, status, error)
        val current = getOperation(appWidgetId, entityId) ?: return false
        if (current.operationId != operationId) return false
        commitAndRequestRender(appWidgetId, "OPERATION_STATUS") { before ->
            val latest = before.operations[entityId]
            if (latest?.operationId != operationId || !latest.status.isActive) return@commitAndRequestRender before
            val revision = before.committedRevision + 1
            before.copy(
                operations = before.operations + (entityId to latest.copy(status = status, error = error)),
                committedRevision = revision,
                requestedRenderRevision = maxOf(before.requestedRenderRevision, revision),
            )
        }
        Log.d(
            TAG,
            "operation status operationId=$operationId widgetId=$appWidgetId entityId=$entityId " +
                "status=$status revision=${currentStateRevision(appWidgetId)}",
        )
        return true
    }

    @Synchronized
    fun finishOperation(
        appWidgetId: Int,
        entityId: String,
        operationId: String,
        terminalStatus: DashboardOperationStatus,
        reason: String? = null,
    ): Boolean {
        require(!terminalStatus.isActive)
        var finished = false
        commitAndRequestRender(appWidgetId, "TERMINAL_RECONCILIATION") { before ->
            val operation = before.operations[entityId]
            if (operation?.operationId != operationId || !operation.status.isActive) return@commitAndRequestRender before
            finished = true
            DashboardTerminalStateMachine.finish(
                before, entityId, operationId, terminalStatus, System.currentTimeMillis(), reason,
            )
        }
        if (finished) {
            if (reason != null && terminalStatus != DashboardOperationStatus.CONFIRMED) {
                configPrefs.edit().putString(key(appWidgetId, "error"), reason).apply()
            }
            Log.i(TAG, "OPTIMISTIC_OVERLAY_CLEAR operationId=$operationId entityId=$entityId")
            Log.i(TAG, "OPERATION_TERMINAL operationId=$operationId widgetId=$appWidgetId entityId=$entityId status=$terminalStatus reason=$reason")
        }
        return finished
    }

    @Synchronized
    fun markRefreshInProgress(appWidgetId: Int, active: Boolean) {
        configPrefs.edit().putBoolean(key(appWidgetId, "refreshing"), active).apply()
        touchAndRequestRender(appWidgetId, "REFRESH_STATUS")
    }

    @Synchronized
    fun saveError(appWidgetId: Int, message: String) {
        configPrefs.edit().putString(key(appWidgetId, "error"), message).apply()
        touchAndRequestRender(appWidgetId, "ERROR")
    }

    @Synchronized
    fun clearError(appWidgetId: Int) {
        configPrefs.edit().remove(key(appWidgetId, "error")).apply()
        touchAndRequestRender(appWidgetId, "ERROR_CLEAR")
    }

    @Synchronized
    fun setSelectedTab(appWidgetId: Int, tabId: String) {
        val state = get(appWidgetId) ?: return
        val validIds = state.tabs.map { it.id }.toSet()
        val target = tabId.takeIf { it in validIds } ?: MAIN_TAB_ID
        val started = System.currentTimeMillis()
        Log.d(TAG, "navigation tap received widgetId=$appWidgetId fromTab=${state.selectedTabId} toTab=$target ts=$started")
        configPrefs.edit().putString(key(appWidgetId, "selected_tab"), target).apply()
        flows[appWidgetId]?.value = state.copy(selectedTabId = target)
        touchAndRequestRender(appWidgetId, "NAVIGATION")
        Log.d(
            TAG,
            "selected_tab saved widgetId=$appWidgetId toTab=$target durationMs=${System.currentTimeMillis() - started}",
        )
    }

    @Synchronized
    fun toggleSection(appWidgetId: Int, sectionKey: String) {
        val state = get(appWidgetId) ?: return
        val updated = if (sectionKey in state.collapsedSections) {
            state.collapsedSections - sectionKey
        } else {
            state.collapsedSections + sectionKey
        }
        configPrefs.edit().putStringSet(key(appWidgetId, "collapsed"), updated).apply()
        flows[appWidgetId]?.value = state.copy(collapsedSections = updated)
        touchAndRequestRender(appWidgetId, "SECTION")
    }

    @Synchronized
    fun delete(appWidgetId: Int) {
        val entityIds = entityIds(appWidgetId)
        val editor = configPrefs.edit()
        listOf(
            "config", "cache", "selected_tab", "collapsed", "in_flight", "updated",
            "revision", "error", "refreshing",
        ).forEach { editor.remove(key(appWidgetId, it)) }
        editor.putStringSet(KEY_IDS, configuredIds() - appWidgetId.toString()).apply()
        structurePrefs.edit().remove(structureKey(appWidgetId)).apply()
        val stateEditor = statePrefs.edit()
        val operationEditor = operationPrefs.edit()
        entityIds.forEach {
            stateEditor.remove(stateKey(appWidgetId, it))
            operationEditor.remove(operationKey(appWidgetId, it))
        }
        stateEditor.apply()
        operationEditor.apply()
        atomicStore.delete(appWidgetId)
        flows.remove(appWidgetId)
    }

    private fun loadState(appWidgetId: Int): DashboardState? {
        ensureMigrated(appWidgetId)
        val config = getConfig(appWidgetId) ?: return null
        val structure = structurePrefs.getString(structureKey(appWidgetId), null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: return null
        val spaces = parseSpaces(structure.optJSONArray("spaces") ?: JSONArray())
        val baselineCards = parseCards(structure.optJSONArray("cards") ?: JSONArray())
        val atomic = atomicStore.read(appWidgetId, knownEntityIds(appWidgetId))
        val cards = baselineCards.map { card ->
            card.copy(
                metrics = card.metrics.map { metric ->
                    atomic.entities[metric.entityId]?.let {
                        metric.copy(state = it.displayState, rawState = it.rawState)
                    } ?: metric
                },
                controls = card.controls.map { control ->
                    atomic.entities[control.entityId]?.let {
                        control.copy(state = it.rawState)
                    } ?: control
                },
            )
        }
        val entityIds = cards.flatMap { card ->
            card.metrics.map { it.entityId } + card.controls.map { it.entityId }
        }.distinct()
        val operations = atomic.operations.filterKeys { it in entityIds }
        val now = System.currentTimeMillis()
        val visibleOperations = operations.filterValues {
            it.status.isActive || (it.completedAt ?: 0L) + TERMINAL_STATUS_VISIBLE_MS > now
        }
        val visibleTabs = config.visibleSpaceIds.filter { id -> spaces.any { it.id == id } }
        val storedTab = configPrefs.getString(key(appWidgetId, "selected_tab"), MAIN_TAB_ID) ?: MAIN_TAB_ID
        val selectedTab = DashboardStatePolicy.resolveSelectedTab(storedTab, visibleTabs)
        if (selectedTab != storedTab) {
            configPrefs.edit().putString(key(appWidgetId, "selected_tab"), selectedTab).apply()
        }
        return DashboardState(
            config = config,
            spaces = spaces,
            cards = cards,
            selectedTabId = selectedTab,
            collapsedSections = configPrefs.getStringSet(key(appWidgetId, "collapsed"), emptySet())
                ?.toSet().orEmpty(),
            inFlightDeviceKeys = visibleOperations.filterValues { it.status.isActive }.keys,
            operationStatusByEntity = visibleOperations.mapValues { it.value.status },
            stateRevision = atomic.committedRevision,
            refreshInProgress = configPrefs.getBoolean(key(appWidgetId, "refreshing"), false),
            lastUpdatedMillis = configPrefs.getLong(key(appWidgetId, "updated"), 0L),
            error = configPrefs.getString(key(appWidgetId, "error"), null),
        )
    }

    private fun publish(appWidgetId: Int) {
        flows[appWidgetId]?.value = loadState(appWidgetId)
    }

    @Synchronized
    fun markRendered(appWidgetId: Int, revision: Long) {
        atomicStore.commit(appWidgetId, knownEntityIds(appWidgetId), "RENDER_SUCCESS") { before ->
            before.copy(renderedRevision = maxOf(before.renderedRevision, revision))
        }
    }

    fun requestPendingRenders(reason: String) {
        all().forEach { config ->
            val revision = revisionState(config.appWidgetId)
            if (revision.committedRevision > revision.renderedRevision ||
                revision.requestedRenderRevision > revision.renderedRevision
            ) {
                renderRequester?.invoke(
                    config.appWidgetId,
                    maxOf(revision.committedRevision, revision.requestedRenderRevision),
                    reason,
                )
            }
        }
    }

    @Synchronized
    private fun touchAndRequestRender(appWidgetId: Int, reason: String): Long =
        commitAndRequestRender(appWidgetId, reason) { before ->
            val revision = before.committedRevision + 1
            before.copy(
                committedRevision = revision,
                requestedRenderRevision = maxOf(before.requestedRenderRevision, revision),
            )
        }.committedRevision

    @Synchronized
    private fun commitAndRequestRender(
        appWidgetId: Int,
        reason: String,
        mutation: (AtomicDashboardRecord) -> AtomicDashboardRecord,
    ): AtomicDashboardRecord {
        val ids = knownEntityIds(appWidgetId)
        val before = atomicStore.read(appWidgetId, ids)
        val after = atomicStore.commit(appWidgetId, ids, reason, mutation)
        publish(appWidgetId)
        if (after.requestedRenderRevision > before.renderedRevision &&
            (after.requestedRenderRevision > before.requestedRenderRevision ||
                after.committedRevision > before.committedRevision)
        ) {
            renderRequester?.invoke(appWidgetId, after.requestedRenderRevision, reason)
        }
        return after
    }

    private fun knownEntityIds(appWidgetId: Int): List<String> {
        val structure = structurePrefs.getString(structureKey(appWidgetId), null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: return emptyList()
        return parseCards(structure.optJSONArray("cards") ?: JSONArray()).flatMap { card ->
            card.metrics.map { it.entityId } + card.controls.map { it.entityId }
        }.distinct()
    }

    private fun ensureMigrated(appWidgetId: Int) {
        if (!DashboardStatePolicy.shouldMigrateStorage(
                structurePrefs.contains(structureKey(appWidgetId)),
                configPrefs.contains(key(appWidgetId, "cache")),
            )
        ) return
        val legacy = configPrefs.getString(key(appWidgetId, "cache"), null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: return
        val cards = parseCards(legacy.optJSONArray("cards") ?: JSONArray())
        var revision = currentRevision(appWidgetId)
        cards.flatMap { card ->
            card.metrics.map { Triple(it.entityId, it.state, it.rawState) } +
                card.controls.map { Triple(it.entityId, it.state, it.state) }
        }.distinctBy { it.first }.forEach { (entityId, display, raw) ->
            revision += 1
            writeEntityState(
                appWidgetId,
                VersionedEntityState(entityId, display, raw, null, revision),
            )
        }
        val structure = JSONObject()
            .put("schema", STORAGE_SCHEMA_VERSION)
            .put("spaces", legacy.optJSONArray("spaces") ?: JSONArray())
            .put("cards", legacy.optJSONArray("cards") ?: JSONArray())
        structurePrefs.edit().putString(structureKey(appWidgetId), structure.toString()).commit()
        configPrefs.edit()
            .putLong(key(appWidgetId, "updated"), legacy.optLong("updated"))
            .putLong(key(appWidgetId, "revision"), revision)
            .putString(key(appWidgetId, "error"), legacy.optString("error").takeIf { it.isNotBlank() })
            .remove(key(appWidgetId, "cache"))
            .remove(key(appWidgetId, "in_flight"))
            .apply()
        Log.i(TAG, "migrated dashboard storage widgetId=$appWidgetId revision=$revision")
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
        fun replaceLegacy(items: List<String>) = items.flatMap { item ->
            if (item == legacyKey) replacementKeys else listOf(item)
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

    private fun readEntityState(appWidgetId: Int, entityId: String): VersionedEntityState? = statePrefs
        .getString(stateKey(appWidgetId, entityId), null)
        ?.let { runCatching { parseEntityState(JSONObject(it)) }.getOrNull() }

    private fun writeEntityState(appWidgetId: Int, state: VersionedEntityState) {
        statePrefs.edit().putString(stateKey(appWidgetId, state.entityId), state.toJson().toString()).apply()
    }

    private fun writeOperation(appWidgetId: Int, operation: DashboardOperation) {
        operationPrefs.edit().putString(operationKey(appWidgetId, operation.entityId), operation.toJson().toString()).apply()
    }

    private fun VersionedEntityState.toJson() = JSONObject()
        .put("id", entityId)
        .put("display", confirmedDisplayState)
        .put("raw", confirmedRawState)
        .put("ha_updated", confirmedHaLastUpdatedMillis)
        .put("revision", revision)
        .put("optimistic_operation", optimisticOperationId)

    private fun parseEntityState(json: JSONObject) = VersionedEntityState(
        entityId = json.getString("id"),
        confirmedDisplayState = json.optString("display"),
        confirmedRawState = json.optString("raw"),
        confirmedHaLastUpdatedMillis = json.optLong("ha_updated").takeIf { !json.isNull("ha_updated") },
        revision = json.optLong("revision"),
        optimisticOverlay = json.optString("raw").takeIf {
            json.optString("optimistic_operation").isNotBlank() && !json.isNull("optimistic_operation")
        },
        optimisticOperationId = json.optNullable("optimistic_operation"),
    )

    private fun DashboardOperation.toJson() = JSONObject()
        .put("operation_id", operationId)
        .put("entity_id", entityId)
        .put("domain", domain)
        .put("service", service)
        .put("desired", desiredState)
        .put("optimistic", optimisticState)
        .put("previous", previousState)
        .put("created", createdAt)
        .put("deadline", deadlineAt)
        .put("status", status.name)
        .put("completed", completedAt)
        .put("error", error)

    private fun parseOperation(json: JSONObject) = DashboardOperation(
        operationId = json.getString("operation_id"),
        entityId = json.getString("entity_id"),
        domain = json.getString("domain"),
        service = json.getString("service"),
        desiredState = json.optNullable("desired"),
        optimisticState = json.optNullable("optimistic"),
        previousState = json.optNullable("previous"),
        createdAt = json.optLong("created"),
        deadlineAt = json.optLong("deadline").takeIf { it > 0L }
            ?: (json.optLong("created") + DashboardStatePolicy.OPERATION_WINDOW_MS),
        status = runCatching { DashboardOperationStatus.valueOf(json.optString("status")) }
            .getOrDefault(DashboardOperationStatus.FAILED),
        completedAt = json.optLong("completed").takeIf { !json.isNull("completed") },
        error = json.optNullable("error"),
    )

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
        items.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("areas", JSONArray(it.roomAreaIds)))
        }
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
                    runCatching { DeviceCategory.valueOf(item.optString("category")) }
                        .getOrDefault(DeviceCategory.OTHER),
                    metrics, controls,
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
        private const val STORAGE_SCHEMA_VERSION = 3
        private const val DEFAULT_METRIC_LIMIT = 5
        private const val TERMINAL_STATUS_VISIBLE_MS = 4_000L
    }
}
