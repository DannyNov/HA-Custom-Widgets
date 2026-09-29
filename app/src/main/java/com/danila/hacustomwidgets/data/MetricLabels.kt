package com.danila.hacustomwidgets.data

object MetricLabels {
        internal fun compactMetricName(deviceName: String, entityName: String): String {
            val trimmed = entityName.trim()
            if (trimmed.equals(deviceName, ignoreCase = true)) return trimmed
            val prefix = deviceName.trim().takeIf { it.isNotBlank() } ?: return trimmed
            return trimmed.removePrefixIgnoringCase("$prefix ").ifBlank { trimmed }
        }

        private fun String.removePrefixIgnoringCase(prefix: String): String =
            if (startsWith(prefix, ignoreCase = true)) drop(prefix.length).trim() else this
}
