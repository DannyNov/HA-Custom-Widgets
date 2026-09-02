package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.*
import org.junit.Test

class DashboardV052CustomizationPolicyTest {
    private fun config(
        hiddenDevices: Map<String, List<String>> = emptyMap(),
        hiddenEntities: Map<String, List<String>> = emptyMap(),
    ) = DashboardConfig(
        1, listOf("home"), mapOf("home" to DashboardGrouping.TYPES), emptyList(),
        emptyMap(), emptyMap(), true, true,
        hiddenDeviceIdsByContext = hiddenDevices,
        hiddenEntityIdsByContext = hiddenEntities,
    )

    private fun card() = DashboardCard(
        key = "dryer", title = "Dryer", areaId = null, roomName = null,
        category = DeviceCategory.SWITCHES,
        metrics = listOf(
            DashboardMetric("sensor.power", "Power", "10 W", "10", "sensor", "power"),
            DashboardMetric("sensor.energy", "Energy", "2 kWh", "2", "sensor", "energy"),
        ),
        controls = listOf(DashboardControl("switch.dryer", "Dryer", "switch", "on")),
        autoOffTimer = AutoOffTimerConfig(enabled = true, timerEntityId = "timer.dryer"),
    )

    @Test fun groupIdentityUsesStableEnumId() {
        assertEquals("SWITCHES", DashboardCustomizationPolicy.groupId(DeviceCategory.SWITCHES))
    }

    @Test fun defaultGroupOrderUsesRanks() {
        assertEquals(
            listOf(DeviceCategory.CLIMATE_SENSORS, DeviceCategory.LIGHTING, DeviceCategory.OTHER),
            DashboardCustomizationPolicy.orderedCategories(
                emptyList(), listOf(DeviceCategory.OTHER, DeviceCategory.LIGHTING, DeviceCategory.CLIMATE_SENSORS),
            ),
        )
    }

    @Test fun savedGroupOrderWins() {
        assertEquals(
            listOf(DeviceCategory.OTHER, DeviceCategory.LIGHTING),
            DashboardCustomizationPolicy.orderedCategories(
                listOf("OTHER", "LIGHTING"), listOf(DeviceCategory.LIGHTING, DeviceCategory.OTHER),
            ),
        )
    }

    @Test fun newGroupIsAppended() {
        assertEquals(
            listOf(DeviceCategory.SWITCHES, DeviceCategory.LIGHTING),
            DashboardCustomizationPolicy.orderedCategories(
                listOf("SWITCHES"), listOf(DeviceCategory.LIGHTING, DeviceCategory.SWITCHES),
            ),
        )
    }

    @Test fun temporarilyMissingGroupIsRetainedAndRestored() {
        val saved = listOf("OTHER", "LIGHTING", "SWITCHES")
        assertEquals(listOf(DeviceCategory.OTHER, DeviceCategory.SWITCHES),
            DashboardCustomizationPolicy.orderedCategories(saved, listOf(DeviceCategory.OTHER, DeviceCategory.SWITCHES)))
        assertEquals(listOf(DeviceCategory.OTHER, DeviceCategory.LIGHTING, DeviceCategory.SWITCHES),
            DashboardCustomizationPolicy.orderedCategories(saved, listOf(DeviceCategory.SWITCHES, DeviceCategory.LIGHTING, DeviceCategory.OTHER)))
    }

    @Test fun reorderingVisibleGroupsKeepsMissingId() {
        assertEquals(
            listOf("OTHER", "SWITCHES", "LIGHTING"),
            DashboardCustomizationPolicy.reorderCategories(
                listOf("LIGHTING", "SWITCHES", "OTHER"),
                listOf(DeviceCategory.LIGHTING, DeviceCategory.OTHER), 0, 1,
            ),
        )
    }

    @Test fun visibilityDefaultsToVisible() {
        assertTrue(DashboardCustomizationPolicy.isDeviceVisible(config(), "home", "dryer"))
        assertTrue(DashboardCustomizationPolicy.isEntityVisible(config(), "home", "sensor.power"))
    }

    @Test fun hiddenStateIsIndependentPerContext() {
        val value = config(hiddenDevices = mapOf("home" to listOf("dryer")))
        assertFalse(DashboardCustomizationPolicy.isDeviceVisible(value, "home", "dryer"))
        assertTrue(DashboardCustomizationPolicy.isDeviceVisible(value, MAIN_TAB_ID, "dryer"))
    }

    @Test fun legacyHiddenDeviceFlagCannotOverrideMainCheckbox() {
        val value = config(hiddenDevices = mapOf(MAIN_TAB_ID to listOf("dryer")))
        assertTrue(DashboardCustomizationPolicy.isDeviceVisible(value, MAIN_TAB_ID, "dryer"))
        assertNotNull(DashboardCustomizationPolicy.presentCard(value, MAIN_TAB_ID, card()))
    }

    @Test fun toggleHiddenIsReversibleAndDropsEmptyContext() {
        val hidden = DashboardCustomizationPolicy.toggleHidden(emptyMap(), "home", "dryer")
        assertEquals(listOf("dryer"), hidden["home"])
        assertEquals(emptyMap<String, List<String>>(), DashboardCustomizationPolicy.toggleHidden(hidden, "home", "dryer"))
    }

    @Test fun hidingDeviceRemovesWholeCard() {
        assertNull(DashboardCustomizationPolicy.presentCard(
            config(hiddenDevices = mapOf("home" to listOf("dryer"))), "home", card(),
        ))
    }

    @Test fun hidingMetricDoesNotRemoveControlOrTimerDependency() {
        val shown = DashboardCustomizationPolicy.presentCard(
            config(hiddenEntities = mapOf("home" to listOf("sensor.power"))), "home", card(),
        )!!
        assertEquals(listOf("sensor.energy"), shown.metrics.map { it.entityId })
        assertEquals("switch.dryer", shown.controls.single().entityId)
        assertTrue(shown.visibleControls.isEmpty())
        assertEquals("timer.dryer", shown.autoOffTimer?.timerEntityId)
    }

    @Test fun selectedControlIsRenderedOnceWithoutDuplicateMetric() {
        val source = card().copy(
            metrics = card().metrics + DashboardMetric("switch.dryer", "Dryer", "ВКЛ", "on", "switch", null),
        )
        val shown = DashboardCustomizationPolicy.presentCard(config(), "home", source)!!
        assertEquals(listOf("switch.dryer"), shown.visibleControls.map { it.entityId })
        assertFalse(shown.metrics.any { it.entityId == "switch.dryer" })
        assertEquals(listOf("sensor.power", "sensor.energy"), shown.metrics.map { it.entityId })
    }

    @Test fun hiddenControlRemainsFunctionalButNotVisible() {
        val source = card().copy(
            metrics = card().metrics + DashboardMetric("switch.dryer", "Dryer", "ВКЛ", "on", "switch", null),
        )
        val shown = DashboardCustomizationPolicy.presentCard(
            config(hiddenEntities = mapOf("home" to listOf("switch.dryer"))), "home", source,
        )!!
        assertEquals("switch.dryer", shown.controls.single().entityId)
        assertTrue(shown.visibleControls.isEmpty())
        assertFalse(shown.metrics.any { it.entityId == "switch.dryer" })
    }

    @Test fun cardWithAllMetricsHiddenRemainsWhenControllable() {
        val shown = DashboardCustomizationPolicy.presentCard(
            config(hiddenEntities = mapOf("home" to listOf("sensor.power", "sensor.energy"))), "home", card(),
        )
        assertNotNull(shown)
        assertTrue(shown!!.metrics.isEmpty())
    }

    @Test fun fiveSelectedMetricsAreNeverSilentlyDropped() {
        val metrics = (1..5).map {
            DashboardMetric("sensor.air.$it", "Air $it", "$it ppb", "$it", "sensor", null)
        }
        val shown = DashboardCustomizationPolicy.presentCard(config(), "home", card().copy(metrics = metrics))!!
        assertEquals(5, shown.metrics.size)
    }

    @Test fun emptyPresentationCardIsOmitted() {
        val empty = card().copy(controls = emptyList(), visibleControls = emptyList(), autoOffTimer = null)
        assertNull(DashboardCustomizationPolicy.presentCard(
            config(hiddenEntities = mapOf("home" to listOf("sensor.power", "sensor.energy"))), "home", empty,
        ))
    }

    @Test fun dragHandleTargetIsLargerWithoutChangingEngineConstants() {
        assertEquals(64, REORDER_HANDLE_WIDTH_DP)
        assertEquals(56, REORDER_HANDLE_HEIGHT_DP)
    }
}
