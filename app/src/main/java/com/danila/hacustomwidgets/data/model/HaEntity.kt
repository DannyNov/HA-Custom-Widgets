package com.danila.hacustomwidgets.data.model

data class HaEntity(
    val entityId: String,
    val state: String,
    val friendlyName: String,
    val unit: String?,
    val lastUpdated: String?,
    val deviceId: String? = null,
) {
    val displayState: String
        get() = if (unit.isNullOrBlank()) state else "$state $unit"
}

data class HaDevice(
    val id: String,
    val name: String,
    val manufacturer: String? = null,
    val model: String? = null,
)

data class HaDeviceGroup(
    val device: HaDevice?,
    val entities: List<HaEntity>,
) {
    val key: String get() = device?.id ?: UNASSIGNED_DEVICE_ID
    val title: String get() = device?.name ?: "Без устройства"

    companion object { const val UNASSIGNED_DEVICE_ID = "__unassigned__" }
}

data class HaCatalog(val groups: List<HaDeviceGroup>)
