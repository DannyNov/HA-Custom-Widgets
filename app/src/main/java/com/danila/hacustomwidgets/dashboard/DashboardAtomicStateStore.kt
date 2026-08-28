package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * One durable record per Dashboard. Entity truth, optimistic overlays, operations and
 * render-delivery revisions are committed with one SharedPreferences transaction.
 */
class DashboardAtomicStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("dashboard_atomic_state_v4", Context.MODE_PRIVATE)
    private val legacyState = context.getSharedPreferences("dashboard_entity_states", Context.MODE_PRIVATE)
    private val legacyOperations = context.getSharedPreferences("dashboard_operations", Context.MODE_PRIVATE)
    private val legacyConfig = context.getSharedPreferences("dashboard_widgets", Context.MODE_PRIVATE)

    @Synchronized
    fun read(appWidgetId: Int, entityIds: Collection<String> = emptyList()): AtomicDashboardRecord {
        prefs.getString(key(appWidgetId), null)?.let { raw ->
            return runCatching { parseRecord(JSONObject(raw)) }.getOrElse {
                throw IllegalStateException("Повреждён атомарный state record Dashboard $appWidgetId", it)
            }
        }
        return migrate(appWidgetId, entityIds)
    }

    @Synchronized
    fun commit(
        appWidgetId: Int,
        entityIds: Collection<String>,
        source: String,
        mutation: (AtomicDashboardRecord) -> AtomicDashboardRecord,
    ): AtomicDashboardRecord {
        val before = read(appWidgetId, entityIds)
        val after = mutation(before).let {
            require(it.committedRevision >= before.committedRevision) { "Revision cannot decrease" }
            require(it.requestedRenderRevision >= before.requestedRenderRevision) { "Requested revision cannot decrease" }
            require(it.renderedRevision >= before.renderedRevision) { "Rendered revision cannot decrease" }
            it
        }
        check(prefs.edit().putString(key(appWidgetId), after.toJson().toString()).commit()) {
            "Не удалось атомарно сохранить Dashboard $appWidgetId"
        }
        Log.d(
            TAG,
            "REVISION_COMMIT widgetId=$appWidgetId source=$source " +
                "committed=${after.committedRevision} requested=${after.requestedRenderRevision} " +
                "rendered=${after.renderedRevision}",
        )
        return after
    }

    @Synchronized
    fun delete(appWidgetId: Int) {
        check(prefs.edit().remove(key(appWidgetId)).commit())
    }

    private fun migrate(appWidgetId: Int, entityIds: Collection<String>): AtomicDashboardRecord {
        val legacyRevision = legacyConfig.getLong("dashboard_${appWidgetId}_revision", 0L)
        val legacyEntities = entityIds.distinct().mapNotNull { entityId ->
            legacyState.getString("dashboard_${appWidgetId}_state_$entityId", null)?.let { raw ->
                runCatching {
                    val json = JSONObject(raw)
                    entityId to VersionedEntityState(
                        entityId = entityId,
                        confirmedDisplayState = json.optString("display"),
                        confirmedRawState = json.optString("raw"),
                        confirmedHaLastUpdatedMillis = json.optLong("ha_updated").takeIf { !json.isNull("ha_updated") },
                        revision = json.optLong("revision", legacyRevision),
                        optimisticOverlay = json.optString("raw").takeIf {
                            json.optString("optimistic_operation").isNotBlank() && !json.isNull("optimistic_operation")
                        },
                        optimisticOperationId = json.optString("optimistic_operation")
                            .takeIf { it.isNotBlank() && !json.isNull("optimistic_operation") },
                    )
                }.getOrNull()
            }
        }.toMap()
        val operations = entityIds.distinct().mapNotNull { entityId ->
            legacyOperations.getString("dashboard_${appWidgetId}_operation_$entityId", null)?.let { raw ->
                runCatching { entityId to parseOperation(JSONObject(raw)) }.getOrNull()
            }
        }.toMap()
        val entities = legacyEntities.mapValues { (entityId, state) ->
            val operation = operations[entityId]
            if (operation?.status?.isActive == true && state.optimisticOperationId == operation.operationId) {
                val confirmed = operation.previousState ?: state.confirmedRawState
                state.copy(
                    confirmedDisplayState = confirmed,
                    confirmedRawState = confirmed,
                    optimisticOverlay = operation.optimisticState,
                )
            } else state
        }
        val record = AtomicDashboardRecord(
            entities = entities,
            operations = operations,
            committedRevision = maxOf(legacyRevision, entities.values.maxOfOrNull { it.revision } ?: 0L),
            requestedRenderRevision = legacyRevision,
            renderedRevision = legacyRevision,
        )
        check(prefs.edit().putString(key(appWidgetId), record.toJson().toString()).commit())
        Log.i(TAG, "MIGRATION widgetId=$appWidgetId from=v0.3.2 entities=${entities.size} operations=${operations.size}")
        return record
    }

    private fun AtomicDashboardRecord.toJson() = JSONObject()
        .put("schema", 4)
        .put("committed_revision", committedRevision)
        .put("requested_render_revision", requestedRenderRevision)
        .put("rendered_revision", renderedRevision)
        .put("entities", JSONArray().also { array -> entities.values.forEach { array.put(it.toJson()) } })
        .put("operations", JSONArray().also { array -> operations.values.forEach { array.put(it.toJson()) } })

    private fun VersionedEntityState.toJson() = JSONObject()
        .put("id", entityId)
        .put("confirmed_display", confirmedDisplayState)
        .put("confirmed_raw", confirmedRawState)
        .put("confirmed_ha_updated", confirmedHaLastUpdatedMillis)
        .put("revision", revision)
        .put("optimistic_overlay", optimisticOverlay)
        .put("optimistic_operation", optimisticOperationId)

    private fun DashboardOperation.toJson() = JSONObject()
        .put("operation_id", operationId).put("entity_id", entityId)
        .put("domain", domain).put("service", service)
        .put("desired", desiredState).put("optimistic", optimisticState)
        .put("previous", previousState).put("created", createdAt).put("deadline", deadlineAt)
        .put("status", status.name).put("completed", completedAt).put("error", error)

    private fun parseRecord(json: JSONObject): AtomicDashboardRecord {
        val entities = buildMap {
            val array = json.optJSONArray("entities") ?: JSONArray()
            for (i in 0 until array.length()) parseEntity(array.getJSONObject(i)).let { put(it.entityId, it) }
        }
        val operations = buildMap {
            val array = json.optJSONArray("operations") ?: JSONArray()
            for (i in 0 until array.length()) parseOperation(array.getJSONObject(i)).let { put(it.entityId, it) }
        }
        return AtomicDashboardRecord(
            entities = entities,
            operations = operations,
            committedRevision = json.optLong("committed_revision"),
            requestedRenderRevision = json.optLong("requested_render_revision"),
            renderedRevision = json.optLong("rendered_revision"),
        )
    }

    private fun parseEntity(json: JSONObject) = VersionedEntityState(
        entityId = json.getString("id"),
        confirmedDisplayState = json.optString("confirmed_display"),
        confirmedRawState = json.optString("confirmed_raw"),
        confirmedHaLastUpdatedMillis = json.optLong("confirmed_ha_updated")
            .takeIf { !json.isNull("confirmed_ha_updated") },
        revision = json.optLong("revision"),
        optimisticOverlay = json.optNullable("optimistic_overlay"),
        optimisticOperationId = json.optNullable("optimistic_operation"),
    )

    private fun parseOperation(json: JSONObject) = DashboardOperation(
        operationId = json.getString("operation_id"), entityId = json.getString("entity_id"),
        domain = json.getString("domain"), service = json.getString("service"),
        desiredState = json.optNullable("desired"), optimisticState = json.optNullable("optimistic"),
        previousState = json.optNullable("previous"), createdAt = json.optLong("created"),
        deadlineAt = json.optLong("deadline").takeIf { it > 0L }
            ?: (json.optLong("created") + DashboardStatePolicy.OPERATION_WINDOW_MS),
        status = runCatching { DashboardOperationStatus.valueOf(json.optString("status")) }
            .getOrDefault(DashboardOperationStatus.FAILED),
        completedAt = json.optLong("completed").takeIf { !json.isNull("completed") },
        error = json.optNullable("error"),
    )

    private fun JSONObject.optNullable(name: String): String? =
        optString(name).takeIf { !isNull(name) && it.isNotBlank() }

    private fun key(appWidgetId: Int) = "dashboard_${appWidgetId}_atomic"

    companion object { private const val TAG = "HAWidgetAtomicState" }
}

data class AtomicDashboardRecord(
    val entities: Map<String, VersionedEntityState> = emptyMap(),
    val operations: Map<String, DashboardOperation> = emptyMap(),
    val committedRevision: Long = 0L,
    val requestedRenderRevision: Long = 0L,
    val renderedRevision: Long = 0L,
)
