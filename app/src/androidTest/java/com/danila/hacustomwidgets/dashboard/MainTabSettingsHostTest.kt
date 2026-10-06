package com.danila.hacustomwidgets.dashboard

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danila.hacustomwidgets.ui.HaCustomWidgetsTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class MainTabSettingsHostTest {
    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
        (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::nodes).orEmpty() }
    private fun screen(locale: Locale, expected: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = Locale.getDefault()
        val changed = AtomicBoolean(false)
        try {
            Locale.setDefault(locale)
            ActivityScenario.launch<ViewportHostActivity>(Intent(instrumentation.targetContext, ViewportHostActivity::class.java)).use { scenario ->
                scenario.onActivity { it.setContent { HaCustomWidgetsTheme { MainTabVisibilitySetting(true) { value -> changed.set(!value) } } } }
                instrumentation.waitForIdleSync()
                var found: List<AccessibilityNodeInfo> = emptyList()
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    found = instrumentation.uiAutomation.rootInActiveWindow?.let(::nodes).orEmpty()
                    if (found.any { it.text?.toString() == expected }) break
                    android.os.SystemClock.sleep(50)
                }
                assertTrue(found.any { it.text?.toString() == expected })
                assertFalse(found.any { it.text?.contains("Favorites") == true || it.text?.contains("Избран") == true || it.text?.contains("★") == true })
                val switch = found.first { it.isCheckable && it.isClickable }
                assertTrue(switch.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                assertTrue("Renamed switch still updates the same showFavorites setting", changed.get())
            }
        } finally { Locale.setDefault(original) }
    }
    @Test fun russianSettingsSwitchUsesMainAndRemainsFunctional() = screen(Locale("ru"), "Показывать вкладку «Главное»")
    @Test fun englishSettingsSwitchUsesMainAndRemainsFunctional() = screen(Locale.ENGLISH, "Show “Main” tab")
}
