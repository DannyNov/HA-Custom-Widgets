package com.danila.hacustomwidgets.data.model

data class HaEntity(
    val entityId: String,
    val state: String,
    val friendlyName: String,
    val unit: String?,
    val lastUpdated: String?,
) {
    val displayState: String
        get() = if (unit.isNullOrBlank()) state else "$state $unit"
}
