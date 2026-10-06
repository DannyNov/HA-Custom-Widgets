package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.HaCatalog
import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.tr
import org.json.JSONArray
import org.json.JSONObject

const val MAINTENANCE_TAB_ID = "__maintenance__"

data class RepairIssue(
    val domain: String, val issueId: String, val severity: String,
    val active: Boolean = true, val ignored: Boolean = false, val fixable: Boolean = false,
    val translationKey: String? = null, val placeholders: Map<String, String> = emptyMap(),
    val titles: Map<String, String> = emptyMap(),
) {
    val attention: Boolean get() = active && !ignored
    val rank: Int get() = when (severity) { "critical" -> 0; "error" -> 1; "warning" -> 2; else -> 3 }
    val title: String get() = titles[if (com.danila.hacustomwidgets.isRussianUi()) "ru" else "en"]
        ?: titles["en"] ?: tr("Home Assistant issue", "Проблема Home Assistant")
    val detail: String get() = when (severity) {
        "critical" -> tr("Critical", "Критическая")
        "error" -> tr("Error", "Ошибка")
        "warning" -> tr("Warning", "Предупреждение")
        else -> tr("Issue", "Проблема")
    } + " · " + if (fixable) tr("Fix in Home Assistant Repairs", "Исправьте в Repairs Home Assistant")
        else tr("Review in Home Assistant Repairs", "Проверьте в Repairs Home Assistant")
}

data class MaintenanceBattery(val entity: HaEntity, val area: String?, val deviceKey: String) {
    val attention: Boolean get() = MaintenancePolicy.batteryAttention(entity)
    // Use the Dashboard's existing icon/color policy, normalizing HA binary semantics only here.
    val metric: DashboardMetric get() {
        val raw = if (entity.domain == "binary_sensor") when (entity.state) {
            "on" -> "low"; "off" -> "normal"; else -> entity.state
        } else entity.state
        val display = when {
            entity.state == "unavailable" -> tr("Unavailable", "Недоступна")
            entity.state == "unknown" -> tr("Unknown", "Неизвестно")
            entity.domain == "binary_sensor" -> when (entity.state) {
                "on", "low" -> tr("Low", "Низкий")
                "off", "normal" -> tr("Normal", "Нормальный")
                else -> tr("Unknown", "Неизвестно")
            }
            entity.unit == "%" -> entity.displayState
            else -> entity.displayState
        }
        return DashboardMetric(entity.entityId, entity.friendlyName, display, raw, entity.domain, "battery")
    }
}

data class MaintenanceSnapshot(
    val batteries: List<MaintenanceBattery> = emptyList(), val updates: List<HaEntity> = emptyList(),
    val repairs: List<RepairIssue> = emptyList(), val repairsLoaded: Boolean = false,
    val repairsError: Boolean = false, val catalogLoaded: Boolean = false,
) {
    val attentionBatteries get() = batteries.filter { it.attention }
    val otherBatteries get() = batteries.filterNot { it.attention }
    val availableUpdates get() = updates.filter(MaintenancePolicy::updateAttention)
    val activeRepairs get() = repairs.filter { it.attention }.sortedBy { it.rank }
    val attention get() = attentionBatteries.isNotEmpty() || availableUpdates.isNotEmpty() || activeRepairs.isNotEmpty()
}

object MaintenancePolicy {
    fun isBattery(entity: HaEntity) = entity.domain in setOf("sensor", "binary_sensor") &&
        entity.deviceClass == "battery" && entity.disabledBy == null
    fun isRelevant(entity: HaEntity) = isBattery(entity) || (entity.domain == "update" && entity.disabledBy == null)
    fun batteryAttention(entity: HaEntity): Boolean {
        if (!isBattery(entity)) return false
        val state = entity.state.trim().lowercase(java.util.Locale.ROOT)
        if (state in setOf("unknown", "unavailable")) return false
        if (entity.domain == "binary_sensor") return state in setOf("on", "low")
        if (entity.unit == "%") {
            val percent = state.replace(',', '.').toDoubleOrNull() ?: return false
            return percent.isFinite() && percent in 0.0..5.0
        }
        return state == "low"
    }
    fun updateAttention(entity: HaEntity) = entity.domain == "update" && entity.disabledBy == null && entity.state == "on"
    fun batteries(catalog: HaCatalog): List<MaintenanceBattery> {
        val areas = catalog.areas.associate { it.id to it.name }
        return catalog.groups.flatMap { group -> group.entities.filter(::isBattery).map {
            MaintenanceBattery(it, areas[it.areaId ?: group.device?.areaId], group.key)
        } }.distinctBy { it.entity.entityId }.sortedWith(compareBy({ it.area.orEmpty() }, { it.entity.friendlyName }))
    }
    fun localizedTitle(issue: RepairIssue, resources: JSONObject): String? {
        val key = "component.${issue.domain}.issues.${issue.translationKey ?: issue.issueId}.title"
        val template = resources.optString(key).takeIf { it.isNotBlank() } ?: return null
        return Regex("\\{([^{}]+)\\}").replace(template) { match ->
            issue.placeholders[match.groupValues[1]] ?: match.value
        }.takeUnless { Regex("\\{[^{}]+\\}").containsMatchIn(it) }
    }
    fun parseIssues(result: JSONObject): List<RepairIssue> {
        val array = result.getJSONArray("issues")
        return (0 until array.length()).map { index -> val item = array.getJSONObject(index)
            val placeholders = item.optJSONObject("translation_placeholders") ?: JSONObject()
            RepairIssue(item.getString("domain"), item.getString("issue_id"), item.optString("severity", "warning"),
                item.optBoolean("active", true), item.optBoolean("ignored", false), item.optBoolean("is_fixable", false),
                item.optString("translation_key").takeIf { it.isNotBlank() && it != "null" },
                placeholders.keys().asSequence().associateWith { placeholders.getString(it) })
        }
    }
    fun entityJson(entity: HaEntity): JSONObject = JSONObject().put("id", entity.entityId).put("state", entity.state)
        .put("name", entity.friendlyName).put("unit", entity.unit).put("class", entity.deviceClass)
    fun parseEntity(json: JSONObject) = HaEntity(json.getString("id"), json.getString("state"), json.getString("name"),
        json.optString("unit").takeIf { it.isNotBlank() }, null, deviceClass = json.optString("class").takeIf { it.isNotBlank() })
    fun batteriesJson(values: List<MaintenanceBattery>) = JSONArray().apply { values.forEach {
        put(entityJson(it.entity).put("area", it.area).put("device", it.deviceKey))
    } }
    fun parseBatteries(array: JSONArray) = (0 until array.length()).map { val j = array.getJSONObject(it)
        MaintenanceBattery(parseEntity(j), j.optString("area").takeIf { it.isNotBlank() }, j.getString("device"))
    }
    fun repairsJson(values: List<RepairIssue>) = JSONArray().apply { values.forEach { issue ->
        put(JSONObject().put("domain", issue.domain).put("issue_id", issue.issueId).put("severity", issue.severity)
            .put("active", issue.active).put("ignored", issue.ignored).put("is_fixable", issue.fixable)
            .put("translation_key", issue.translationKey).put("translation_placeholders", JSONObject(issue.placeholders))
            .put("titles", JSONObject(issue.titles)))
    } }
    fun parseStoredRepairs(array: JSONArray): List<RepairIssue> = parseIssues(JSONObject().put("issues", array)).mapIndexed { i, issue ->
        val titles = array.getJSONObject(i).optJSONObject("titles") ?: JSONObject()
        issue.copy(titles = titles.keys().asSequence().associateWith { titles.getString(it) })
    }
}
