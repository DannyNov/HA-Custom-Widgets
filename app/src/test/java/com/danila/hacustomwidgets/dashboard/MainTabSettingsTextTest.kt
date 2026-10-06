package com.danila.hacustomwidgets.dashboard

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class MainTabSettingsTextTest {
    private fun check(locale: Locale, title: String, configure: String, show: String, select: String) {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            assertEquals(title, MainTabSettingsText.title)
            assertEquals(configure, MainTabSettingsText.configure)
            assertEquals(show, MainTabSettingsText.show)
            assertEquals(select, MainTabSettingsText.select)
            for (text in listOf(title, configure, show, select)) {
                assertFalse(text.contains("★")); assertFalse(text.contains("Favorites")); assertFalse(text.contains("Избран"))
            }
        } finally { Locale.setDefault(old) }
    }
    @Test fun russianUsesMainEverywhere() = check(Locale("ru"), "Вкладка «Главное»", "Настроить вкладку «Главное»",
        "Показывать вкладку «Главное»", "Отметьте карточки для вкладки «Главное». Нажмите название устройства, чтобы выбрать и упорядочить параметры.")
    @Test fun englishUsesMainEverywhere() = check(Locale.ENGLISH, "Main tab", "Configure Main tab", "Show “Main” tab",
        "Select cards for the Main tab. Tap a device name to choose and order its parameters.")
}
