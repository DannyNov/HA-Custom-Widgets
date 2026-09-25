package com.danila.hacustomwidgets.dashboard

/** Catalog freshness is independent of state traffic and survives process restarts. */
object DashboardCatalogPolicy {
    const val MAX_AGE_MS = 15 * 60_000L

    fun isDue(lastCatalogAt: Long, now: Long): Boolean =
        lastCatalogAt <= 0L || now < lastCatalogAt || now - lastCatalogAt >= MAX_AGE_MS
}
