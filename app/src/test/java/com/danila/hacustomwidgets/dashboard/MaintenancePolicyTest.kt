package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class MaintenancePolicyTest {
    private fun battery(state: String, unit: String? = "%", domain: String = "sensor") =
        HaEntity("$domain.charge", state, "Charge", unit, null, deviceClass = "battery")
    @Test fun exactFiveSixBoundary() {
        assertTrue(MaintenancePolicy.batteryAttention(battery("5")))
        for (state in listOf("6", "10", "15", "20", "100", "5.01")) assertFalse(state, MaintenancePolicy.batteryAttention(battery(state)))
        for (state in listOf("0", "4.9", "5,0")) assertTrue(state, MaintenancePolicy.batteryAttention(battery(state)))
    }
    @Test fun detectionUsesClassAndDomainOnly() {
        assertFalse(MaintenancePolicy.isBattery(battery("2").copy(deviceClass = null, entityId = "sensor.battery_low")))
        assertFalse(MaintenancePolicy.isBattery(battery("2").copy(entityId = "input_number.battery")))
        assertTrue(MaintenancePolicy.isBattery(battery("2").copy(entityId = "sensor.a")))
    }
    @Test fun unknownAndUnavailableNeverAlert() {
        for (domain in listOf("sensor", "binary_sensor")) for (state in listOf("unknown", "unavailable"))
            assertFalse(MaintenancePolicy.batteryAttention(battery(state, domain = domain)))
    }
    @Test fun invalidNumbersNeverAlert() {
        for (state in listOf("-1", "101", "NaN", "Infinity", "", "oops")) assertFalse(MaintenancePolicy.batteryAttention(battery(state)))
    }
    @Test fun numericWithoutPercentIsNotAssumedPercent() {
        assertFalse(MaintenancePolicy.batteryAttention(battery("3", "V")))
        assertFalse(MaintenancePolicy.batteryAttention(battery("3", null)))
        assertTrue(MaintenancePolicy.batteryAttention(battery("low", null)))
    }
    @Test fun binaryOnAndLowAreAttention() {
        for (state in listOf("on", "low")) assertTrue(MaintenancePolicy.batteryAttention(battery(state, null, "binary_sensor")))
        for (state in listOf("off", "normal", "unknown")) assertFalse(MaintenancePolicy.batteryAttention(battery(state, null, "binary_sensor")))
    }
    @Test fun disabledIsExcludedHiddenDiagnosticsRemain() {
        assertFalse(MaintenancePolicy.isBattery(battery("2").copy(disabledBy = "user")))
        assertTrue(MaintenancePolicy.isBattery(battery("2").copy(hiddenBy = "integration", entityCategory = "diagnostic")))
    }
    @Test fun updatesUseOnOnly() {
        for (state in listOf("on", "off", "unknown", "unavailable")) assertEquals(state == "on",
            MaintenancePolicy.updateAttention(battery(state).copy(entityId = "update.a", deviceClass = null)))
    }
    @Test fun onlyActiveNonIgnoredIssuesAlertAcrossSeverities() {
        for (severity in listOf("critical", "error", "warning", "future")) {
            val issue = RepairIssue("ha", "issue", severity)
            assertTrue(issue.attention); assertFalse(issue.copy(ignored = true).attention); assertFalse(issue.copy(active = false).attention)
        }
    }
    @Test fun actualProtocolDoesNotRequireActiveField() {
        val issues = MaintenancePolicy.parseIssues(JSONObject("""{"issues":[{"domain":"ha","issue_id":"a","severity":"error","ignored":false,"is_fixable":true}]}"""))
        assertTrue(issues.single().attention); assertTrue(issues.single().fixable)
    }
    @Test fun severityOrdering() {
        val snap = MaintenanceSnapshot(repairs = listOf("warning", "critical", "error").map { RepairIssue("ha", it, it) })
        assertEquals(listOf("critical", "error", "warning"), snap.activeRepairs.map { it.severity })
    }
    @Test fun translationUsesPlaceholdersLiterally() {
        val issue = RepairIssue("ha", "id", "warning", translationKey = "key", placeholders = mapOf("name" to "A {B}"))
        assertEquals("Issue for Device", MaintenancePolicy.localizedTitle(issue.copy(placeholders = mapOf("name" to "Device")), JSONObject().put("component.ha.issues.key.title", "Issue for {name}")))
        assertNull(MaintenancePolicy.localizedTitle(issue, JSONObject()))
        assertNull(MaintenancePolicy.localizedTitle(issue, JSONObject().put("component.ha.issues.key.title", "{missing}")))
    }
    @Test fun fallbackNeverShowsRawIssueIdentifier() {
        assertFalse(RepairIssue("raw_domain", "secret_technical_id", "warning").title.contains("secret_technical_id"))
    }
    @Test fun batteryVisualScaleIsSharedAndIndependentFromAttention() {
        val b = MaintenanceBattery(battery("6"), null, "a")
        assertEquals(BatteryHealth.CRITICAL, batteryHealth(b.metric)); assertFalse(b.attention)
        assertEquals(BatteryHealth.NORMAL, batteryHealth(b.copy(entity = battery("off", null, "binary_sensor")).metric))
        assertEquals(BatteryHealth.CRITICAL, batteryHealth(b.copy(entity = battery("on", null, "binary_sensor")).metric))
    }
    @Test fun aggregateUsesAnySourceAndDoesNotDuplicateBatteries() {
        val b = MaintenanceBattery(battery("5"), null, "a")
        val snap = MaintenanceSnapshot(batteries = listOf(b, b.copy(entity = battery("6").copy(entityId = "sensor.other"))))
        assertTrue(snap.attention); assertEquals(1, snap.attentionBatteries.size); assertEquals(1, snap.otherBatteries.size)
        assertTrue(MaintenanceSnapshot(updates = listOf(battery("on").copy(entityId = "update.a"))).attention)
        assertTrue(MaintenanceSnapshot(repairs = listOf(RepairIssue("ha", "id", "warning"))).attention)
        assertFalse(MaintenanceSnapshot(batteries = listOf(b.copy(entity = battery("6")))).attention)
    }
    @Test fun percentAndLowOnSameDeviceUseOr() {
        val snap = MaintenanceSnapshot(batteries = listOf(MaintenanceBattery(battery("80"), null, "a"), MaintenanceBattery(battery("on", null, "binary_sensor"), null, "a")))
        assertTrue(snap.attention); assertEquals(1, snap.attentionBatteries.size)
    }
    @Test fun areaResolutionAndAutomaticCatalogRemoval() {
        val catalog = HaCatalog(listOf(HaDeviceGroup(HaDevice("a", "A", areaId = "r"), listOf(battery("6")))), listOf(HaArea("r", "Room")))
        assertEquals("Room", MaintenancePolicy.batteries(catalog).single().area)
        assertTrue(MaintenancePolicy.batteries(catalog.copy(groups = emptyList())).isEmpty())
    }
    @Test fun persistenceRoundTripPreservesUnknownAndTranslation() {
        val b = MaintenanceBattery(battery("unavailable"), "Room", "a")
        assertEquals(b, MaintenancePolicy.parseBatteries(MaintenancePolicy.batteriesJson(listOf(b))).single())
        val issue = RepairIssue("ha", "id", "critical", titles = mapOf("ru" to "Ошибка", "en" to "Issue"))
        assertEquals(issue, MaintenancePolicy.parseStoredRepairs(MaintenancePolicy.repairsJson(listOf(issue))).single())
    }
    @Test fun ruEnAndDefaultOn() {
        val old = Locale.getDefault()
        try {
            val issue = RepairIssue("ha", "id", "warning", titles = mapOf("en" to "Issue", "ru" to "Проблема"))
            Locale.setDefault(Locale.ENGLISH); assertEquals("Issue", issue.title)
            Locale.setDefault(Locale("ru")); assertEquals("Проблема", issue.title)
        } finally { Locale.setDefault(old) }
        assertTrue(DashboardConfig(1, emptyList(), emptyMap(), emptyList(), emptyMap(), emptyMap(), false, false).showMaintenance)
    }
    @Test fun maintenanceNavigationAndRepeatAreSafe() {
        val tabs = listOf("room", MAINTENANCE_TAB_ID)
        assertEquals(MAINTENANCE_TAB_ID, DashboardNavigationPolicy.plan("room", MAINTENANCE_TAB_ID, tabs).targetTabId)
        assertEquals(0, DashboardNavigationPolicy.plan(MAINTENANCE_TAB_ID, MAINTENANCE_TAB_ID, tabs).publicationCount)
        assertNotEquals(MAINTENANCE_TAB_ID, DashboardNavigationPolicy.plan("room", MAINTENANCE_TAB_ID, listOf("room")).targetTabId)
    }
}
