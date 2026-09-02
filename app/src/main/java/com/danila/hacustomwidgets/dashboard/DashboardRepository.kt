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

    fun entityIds(appWidgetId: Int): List<String> = structure(appWidgetId)?.entityIds.orEmpty()

    @Synchronized
    fun selectNextTimerDuration(appWidgetId: Int, deviceKey: String): Pair<DashboardCard, TimerDurationPreset>? {
        val state = get(appWidgetId) ?: return null
        val card = state.cards.firstOrNull { it.key == deviceKey } ?: return null
        val config = state.config.autoOffTimersByDevice[deviceKey]?.takeIf {
            it.enabled && it.timerEntityId != null && AutoOffTimerPolicy.validate(it.durations)
        } ?: return null
        val actual = card.timerState?.let { HaTimerPresentationPolicy.resolve(it, Instant.now()).actualDurationMinutes }
        val next = AutoOffTimerPolicy.nextIndex(config, actual.takeIf { card.timerState?.rawState in setOf("active", "paused") })
        if (next !in config.durations.indices) return null
        val updated = state.config.copy(autoOffTimersByDevice = state.config.autoOffTimersByDevice +
            (deviceKey to config.copy(selectedDurationIndex = next)))
        configPrefs.edit().putString(key(appWidgetId, "config"), updated.toJson().toString()).apply()
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
        val catalogSpaceIds = catalog.spaces().map { it.id }
        val migrated = migrateLegacyUnassigned(storedConfig, catalog)
        val config = migrated.copy(
            visibleSpaceIds = migrated.visibleSpaceIds.filter { it in catalogSpaceIds },
            spaceOrderIds = DashboardOrderPolicy.merge(migrated.spaceOrderIds, catalogSpaceIds),
        )
        if (config != storedConfig) {
            configPrefs.edit().putString(key(appWidgetId, "config"), config.toJson().toString()).apply()
        }
        val spaces = catalog.spaces().map { DashboardSpace(it.id, it.name, it.areaIds) }
        val areaNames = catalog.areas.associate { it.id to it.name }
        val allEntities = catalog.groups.flatMap { it.entities }.distinctBy { it.entityId }
        val entitiesById = allEntities.associateBy { it.entityId }
        val cards = catalog.groups.mapNotNull { group ->
            group.copy(entities = group.entities.filterNot { it.domain in SCENARIO_DOMAINS })
                .takeIf { it.entities.isNotEmpty() }
                ?.toDashboardCard(config, areaNames, entitiesById)
        }
        val scenarios = catalog.groups.flatMap { group ->
            group.entities.filter { it.domain in SCENARIO_DOMAINS }.map { entity ->
                DashboardScenarioAction(
                    entityId = entity.entityId,
                    title = entity.friendlyName,
                    domain = entity.domain,
                    state = entity.state,
                    spaceId = ScenarioPolicy.resolveSpaceId(entity.areaId, group.device?.areaId, catalog),
                )
            }
        }.distinctBy { it.entityId }
        val structure = JSONObject()
            .put("schema", STORAGE_SCHEMA_VERSION)
            .put("spaces", spacesJson(spaces))
            .put("cards", cardsJson(cards))
            .put("scenarios", scenariosJson(scenarios))
        structurePrefs.edit().putString(structureKey(appWidgetId), structure.toString()).apply()
        invalidateStructure(appWidgetId)
        configurationChanged?.invoke("DASHBOARD_STRUCTURE_CHANGED")
        configPrefs.edit()
            .remove(key(appWidgetId, "error"))
            .apply()
        val entities = allEntities
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
            val confirmations = mutableListOf<Pair<String, String>>()
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
                        "lastUpdated=${entity.lastUpdated} lastChanged=${entity.lastChanged} " +
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
                    timerDuration = entity.timerDuration,
                    timerRemaining = entity.timerRemaining,
                    timerFinishesAt = entity.timerFinishesAt,
                )
                stateMap[entity.entityId] = updated
                if (decision.confirmsOperation && operation != null) {
                    confirmations += entity.entityId to operation.operationId
                }
                Log.d(TAG, "CONFIRMED_STATE_COMMIT widgetId=$appWidgetId entityId=${entity.entityId} confirmed=${entity.state} revision=$revision")
            }
            if (accepted == 0) return@commitAndRequestRender before
            var result = before.copy(
                entities = stateMap,
                operations = operationMap,
                committedRevision = revision,
                requestedRenderRevision = maxOf(before.requestedRenderRevision, revision),
            )
            confirmations.forEach { (entityId, operationId) ->
                result = DashboardTerminalStateMachine.finish(
                    result, entityId, operationId, DashboardOperationStatus.CONFIRMED,
                    System.currentTimeMillis(), null,
                )
                Log.i(TAG, "OPERATION_TERMINAL operationId=$operationId entityId=$entityId status=CONFIRMED reason=ha-truth")
            }
            result
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
        val plan = DashboardNavigationPolicy.plan(
            state.selectedTabId,
            tabId,
            state.tabs.map { it.id }.filter { it != MAIN_TAB_ID },
        )
        val target = plan.targetTabId
        if (plan.publicationCount == 0) return
        val started = System.currentTimeMillis()
        Log.d(TAG, "NAV_TAP widgetId=$appWidgetId fromTab=${state.selectedTabId} toTab=$target ts=$started")
        configPrefs.edit().putString(key(appWidgetId, "selected_tab"), target).apply()
        flows[appWidgetId]?.value = state.copy(selectedTabId = target)
        val revision = touchAndRequestRender(appWidgetId, "NAVIGATION", publishState = false)
        Log.d(
            TAG,
            "NAV_STATE_COMMIT widgetId=$appWidgetId toTab=$target revision=$revision publications=1 " +
                "durationMs=${System.currentTimeMillis() - started}",
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
        touchAndRequestRender(appWidgetId, "SECTION", publishState = false)
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
        invalidateStructure(appWidgetId)
        configurationChanged?.invoke("DASHBOARD_DELETED")
    }

    private fun loadState(appWidgetId: Int): DashboardState? {
        val loadStarted = System.currentTimeMillis()
        Log.d(TAG, "STATE_LOAD_START widgetId=$appWidgetId")
        ensureMigrated(appWidgetId)
        val config = getConfig(appWidgetId) ?: return null
        val structure = structure(appWidgetId) ?: return null
        val spaces = structure.spaces
        val atomic = atomicStore.read(appWidgetId, structure.entityIds)
        val cards = structure.cards.map { card ->
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
                timerState = card.timerState?.let { timer ->
                    atomic.entities[timer.entityId]?.let { state -> timer.copy(
                        state = state.displayState, rawState = state.rawState,
                        timerDuration = state.timerDuration,
                        timerRemaining = state.timerRemaining,
                        timerFinishesAt = state.timerFinishesAt,
                    ) } ?: timer
                },
            )
        }
        val scenarios = structure.scenarioActions.map { action ->
            atomic.entities[action.entityId]?.let { action.copy(state = it.rawState) } ?: action
        }
        val operations = atomic.operations.filterKeys { it in structure.entityIds }
        val now = System.currentTimeMillis()
        val visibleOperations = operations.filterValues {
            it.status.isActive || (it.completedAt ?: 0L) + TERMINAL_STATUS_VISIBLE_MS > now
        }
        val visibleTabs = config.visibleSpaceIds.filter { id -> spaces.any { it.id == id } } +
            listOfNotNull(SCENARIOS_TAB_ID.takeIf { config.scenariosEnabled })
        val storedTab = configPrefs.getString(key(appWidgetId, "selected_tab"), MAIN_TAB_ID) ?: MAIN_TAB_ID
        val selectedTab = DashboardStatePolicy.resolveSelectedTab(storedTab, visibleTabs)
        if (selectedTab != storedTab) {
            configPrefs.edit().putString(key(appWidgetId, "selected_tab"), selectedTab).apply()
        }
        val result = DashboardState(
            config = config,
            spaces = spaces,
            cards = cards,
            scenarioActions = scenarios,
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
        val selectedCount = if (selectedTab == MAIN_TAB_ID) config.favoriteDeviceKeys.size
        else structure.cardKeysBySpace[selectedTab].orEmpty().size
        Log.d(
            TAG,
            "STATE_LOAD_END widgetId=$appWidgetId durationMs=${System.currentTimeMillis() - loadStarted} " +
                "cardsTotal=${cards.size} cardsInSelectedSpace=$selectedCount entitiesTotal=${structure.entityIds.size} " +
                "structureBytes=${structure.structureBytes}",
        )
        return result
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
    private fun touchAndRequestRender(
        appWidgetId: Int,
        reason: String,
        publishState: Boolean = true,
    ): Long =
        commitAndRequestRender(appWidgetId, reason, publishState) { before ->
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
        publishState: Boolean = true,
        mutation: (AtomicDashboardRecord) -> AtomicDashboardRecord,
    ): AtomicDashboardRecord {
        val ids = knownEntityIds(appWidgetId)
        val before = atomicStore.read(appWidgetId, ids)
        val after = atomicStore.commit(appWidgetId, ids, reason, mutation)
        if (publishState) publish(appWidgetId)
        if (after.requestedRenderRevision > before.renderedRevision &&
            (after.requestedRenderRevision > before.requestedRenderRevision ||
                after.committedRevision > before.committedRevision)
        ) {
            renderRequester?.invoke(appWidgetId, after.requestedRenderRevision, reason)
        }
        return after
    }

    private fun knownEntityIds(appWidgetId: Int): List<String> = structure(appWidgetId)?.entityIds.orEmpty()

    private fun structure(appWidgetId: Int): DashboardStructureSnapshot? = structures[appWidgetId]
        ?: structurePrefs.getString(structureKey(appWidgetId), null)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                val spaces = parseSpaces(json.optJSONArray("spaces") ?: JSONArray())
                val cards = parseCards(json.optJSONArray("cards") ?: JSONArray())
                val scenarios = parseScenarios(json.optJSONArray("scenarios") ?: JSONArray())
                val ids = cards.flatMap { card ->
                    buildList {
                        addAll(card.metrics.map { it.entityId })
                        addAll(card.controls.map { it.entityId })
                        card.autoOffTimer?.timerEntityId?.let(::add)
                    }
                }.plus(scenarios.map { it.entityId }).distinct()
                val areaCards = cards.filter { it.areaId != null }.groupBy { requireNotNull(it.areaId) }
                    .mapValues { (_, values) -> values.map { it.key } }
                val spaceCards = spaces.associate { space ->
                    space.id to space.roomAreaIds.flatMap { areaCards[it].orEmpty() }.distinct()
                } + ("__unassigned_space__" to cards.filter { it.areaId == null }.map { it.key })
                DashboardStructureSnapshot(
                    spaces, cards, scenarios, ids, spaceCards, areaCards,
                    raw.toByteArray(Charsets.UTF_8).size,
                )
            }.getOrNull()?.also { registerStructure(appWidgetId, it) }
        }

    private fun registerStructure(appWidgetId: Int, snapshot: DashboardStructureSnapshot) {
        structures.put(appWidgetId, snapshot)?.entityIds?.forEach { entityId ->
            widgetsByEntity[entityId]?.remove(appWidgetId)
        }
        snapshot.entityIds.forEach { entityId ->
            widgetsByEntity.getOrPut(entityId) { ConcurrentHashMap.newKeySet<Int>() }.add(appWidgetId)
        }
    }

    private fun invalidateStructure(appWidgetId: Int) {
        structures.remove(appWidgetId)?.entityIds?.forEach { entityId ->
            widgetsByEntity[entityId]?.remove(appWidgetId)
        }
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
        invalidateStructure(appWidgetId)
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
        entitiesById: Map<String, HaEntity>,
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
        }.filterNot { it.entityId == config.autoOffTimersByDevice[key]?.timerEntityId }
        val timerConfig = config.autoOffTimersByDevice[key]
            ?.takeIf { it.enabled && it.timerEntityId != null && AutoOffTimerPolicy.validate(it.durations) }
        val timerId = timerConfig?.timerEntityId
        val timerEntity = timerId?.let(entitiesById::get)
        fun HaEntity.asMetric() = DashboardMetric(
            entityId, WidgetRepository.compactMetricName(title, friendlyName), displayState, state,
            domain, deviceClass, timerDuration, timerRemaining, timerFinishesAt,
        )
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
                    timerDuration = entity.timerDuration,
                    timerRemaining = entity.timerRemaining,
                    timerFinishesAt = entity.timerFinishesAt,
                )
            }.filterNot { it.entityId == timerId },
            controls = controls,
            autoOffTimer = timerConfig,
            timerState = timerEntity?.asMetric(),
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
        .put("space_order", JSONArray(spaceOrderIds))
        .put("grouping", JSONObject().also { out -> groupingBySpace.forEach { (k, v) -> out.put(k, v.name) } })
        .put("favorites", JSONArray(favoriteDeviceKeys))
        .put("entity_order", mapOfListsJson(entityOrderByDevice))
        .put("card_order", mapOfListsJson(cardOrderBySpace))
        .put("show_updated", showLastUpdated)
        .put("compact", compactDensity)
        .put("scenarios_enabled", scenariosEnabled)
        .put("scenario_automation_visible", scenarioAutomationVisible)
        .put("scenario_script_visible", scenarioScriptVisible)
        .put("scenario_order", mapOfListsJson(scenarioOrderBySpaceAndDomain))
        .put("auto_off_timers", JSONObject().also { out ->
            autoOffTimersByDevice.forEach { (deviceKey, timer) -> out.put(deviceKey, timer.toJson()) }
        })

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
        spaceOrderIds = json.optJSONArray("space_order").stringList().ifEmpty {
            json.optJSONArray("spaces").stringList()
        },
        scenariosEnabled = json.optBoolean("scenarios_enabled", true),
        scenarioAutomationVisible = json.optBoolean("scenario_automation_visible", true),
        scenarioScriptVisible = json.optBoolean("scenario_script_visible", true),
        scenarioOrderBySpaceAndDomain = json.optJSONObject("scenario_order").mapOfLists(),
        autoOffTimersByDevice = json.optJSONObject("auto_off_timers").timerConfigMap(),
    )

    private fun AutoOffTimerConfig.toJson() = JSONObject()
        .put("enabled", enabled)
        .put("timer_entity_id", timerEntityId)
        .put("selected_index", selectedDurationIndex)
        .put("durations", JSONArray().also { array -> durations.forEach { preset ->
            array.put(JSONObject().put("id", preset.id).put("minutes", preset.minutes))
        } })

    private fun JSONObject?.timerConfigMap(): Map<String, AutoOffTimerConfig> = this?.let { json ->
        json.keys().asSequence().associateWith { key ->
            val value = json.getJSONObject(key)
            val durations = buildList {
                val array = value.optJSONArray("durations") ?: JSONArray()
                for (index in 0 until array.length()) array.getJSONObject(index).let {
                    add(TimerDurationPreset(it.optString("id", "preset-$index"), it.optInt("minutes")))
                }
            }.takeIf(AutoOffTimerPolicy::validate) ?: AutoOffTimerConfig.DEFAULT_TIMER_PRESETS
            AutoOffTimerConfig(
                enabled = value.optBoolean("enabled"),
                timerEntityId = value.optNullable("timer_entity_id"),
                durations = durations,
                selectedDurationIndex = value.optInt("selected_index", -1),
            )
        }
    }.orEmpty()

    private fun scenariosJson(items: List<DashboardScenarioAction>) = JSONArray().also { array ->
        items.forEach { action ->
            array.put(JSONObject().put("id", action.entityId).put("title", action.title)
                .put("domain", action.domain).put("state", action.state).put("space", action.spaceId))
        }
    }

    private fun parseScenarios(array: JSONArray) = buildList {
        for (i in 0 until array.length()) array.getJSONObject(i).let { item ->
            add(DashboardScenarioAction(item.getString("id"), item.optString("title"),
                item.optString("domain"), item.optString("state"), item.optString("space")))
        }
    }

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
                                    .put("domain", metric.domain).put("class", metric.deviceClass)
                                    .put("timer_duration", metric.timerDuration)
                                    .put("timer_remaining", metric.timerRemaining)
                                    .put("timer_finishes_at", metric.timerFinishesAt),
                            )
                        }
                    })
                    .put("auto_off_timer", card.autoOffTimer?.toJson())
                    .put("timer_state", card.timerState?.let { metric ->
                        JSONObject().put("id", metric.entityId).put("label", metric.label)
                            .put("state", metric.state).put("raw", metric.rawState)
                            .put("domain", metric.domain).put("class", metric.deviceClass)
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
        private const val STORAGE_SCHEMA_VERSION = 4
        private const val DEFAULT_METRIC_LIMIT = 5
        private val SCENARIO_DOMAINS = setOf("automation", "script")
        private const val TERMINAL_STATUS_VISIBLE_MS = 4_000L
    }
}
