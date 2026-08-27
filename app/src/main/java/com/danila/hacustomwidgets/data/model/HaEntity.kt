package com.danila.hacustomwidgets.data.model

data class HaEntity(
    val entityId: String,
    val state: String,
    val friendlyName: String,
    val unit: String?,
    val lastUpdated: String?,
    val deviceId: String? = null,
    val areaId: String? = null,
    val deviceClass: String? = null,
    val icon: String? = null,
    val entityCategory: String? = null,
    val hiddenBy: String? = null,
    val disabledBy: String? = null,
) {
    val domain: String get() = entityId.substringBefore('.')
    val displayState: String
        get() = if (unit.isNullOrBlank()) state else "$state $unit"
}

data class HaDevice(
    val id: String,
    val name: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val areaId: String? = null,
)

data class HaArea(
    val id: String,
    val name: String,
    val floorId: String? = null,
)

data class HaFloor(val id: String, val name: String, val level: Int? = null)

enum class HaSpaceKind { FLOOR, AREA, UNASSIGNED }

data class HaSpace(
    val id: String,
    val name: String,
    val kind: HaSpaceKind,
    val areaIds: List<String>,
)

data class HaDeviceGroup(
    val device: HaDevice?,
    val entities: List<HaEntity>,
    val syntheticKey: String? = null,
    val syntheticTitle: String? = null,
) {
    val key: String get() = device?.id ?: syntheticKey ?: UNASSIGNED_DEVICE_ID
    val title: String get() = device?.name ?: syntheticTitle ?: "Без устройства"
    val effectiveAreaId: String?
        get() = entities.mapNotNull { it.areaId }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: device?.areaId

    companion object { const val UNASSIGNED_DEVICE_ID = "__unassigned__" }
}

data class HaCatalog(
    val groups: List<HaDeviceGroup>,
    val areas: List<HaArea> = emptyList(),
    val floors: List<HaFloor> = emptyList(),
) {
    fun spaces(): List<HaSpace> {
        val assignedAreaIds = areas.map { it.id }.toSet()
        val result = if (floors.isNotEmpty()) {
            floors.sortedWith(compareBy<HaFloor> { it.level ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() })
                .map { floor ->
                    HaSpace(
                        id = "floor:${floor.id}",
                        name = floor.name,
                        kind = HaSpaceKind.FLOOR,
                        areaIds = areas.filter { it.floorId == floor.id }.map { it.id },
                    )
                } + areas.filter { it.floorId == null }.map { area ->
                HaSpace("area:${area.id}", area.name, HaSpaceKind.AREA, listOf(area.id))
            }
        } else {
            areas.map { area -> HaSpace("area:${area.id}", area.name, HaSpaceKind.AREA, listOf(area.id)) }
        }
        val hasUnassigned = groups.any { it.effectiveAreaId == null || it.effectiveAreaId !in assignedAreaIds }
        return result + listOfNotNull(
            HaSpace(UNASSIGNED_SPACE_ID, "Без пространства", HaSpaceKind.UNASSIGNED, emptyList())
                .takeIf { hasUnassigned },
        )
    }

    fun groupsForSpace(space: HaSpace): List<HaDeviceGroup> = when (space.kind) {
        HaSpaceKind.UNASSIGNED -> groups.filter { group ->
            val areaId = group.effectiveAreaId
            areaId == null || areas.none { it.id == areaId }
        }
        else -> groups.filter { it.effectiveAreaId in space.areaIds }
    }

    companion object { const val UNASSIGNED_SPACE_ID = "__unassigned_space__" }
}
