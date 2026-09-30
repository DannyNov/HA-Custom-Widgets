# HA Custom Widgets v0.6.3-rc3

## Русский

Кандидат для повторной физической проверки на Honor. Это не Final.

- Регулятор яркости: плавающее окно со скруглением 24dp, отступами 24dp и максимальной шириной 420dp; длинное содержимое прокручивается.
- Вспомогательное окно исключено из Recents; Закрыть, Back и Home освобождают его задачу. Повторное нажатие процента открывает окно снова.
- Символ power включённых ламп и переключателей использует существующий жёлтый widget_light_on. Фоны кнопок и pending feedback сохранены.
- Между капсулой и видимым кругом power — 12dp. Сохранены 48dp targets, форма, контур и компактный процент на узкой ширине.
- Сохранены RC2 Slider ownership, отправка после завершения выбора, ±5 и диапазон 1–100%, timers/realtime и независимые Dashboard.

Устанавливать поверх текущей версии без удаления приложения. RC1/RC2 сохраняются. PR остаётся draft до явного физического подтверждения; Telegram не отправляется.

## English

Pre-release for another physical Honor check; not Final.

- Floating brightness panel: 24dp corners and margins, 420dp maximum width, scrollable content for large fonts/short windows.
- Helper task excluded from Recents; Close, Back and Home release it, and percentage opens it again.
- ON power glyphs use existing widget_light_on yellow across the shared Dashboard power renderer. Backgrounds and pending feedback are preserved.
- 12dp visible capsule-to-power gap, unchanged 48dp targets/outline/shape and narrow percentage fallback.
- RC2 slider ownership and finished-gesture submission, ±5, 1–100%, timers/realtime and independent widgets preserved.

Install over the current app. RC1/RC2 remain available. Keep PR draft until explicit physical approval; no Telegram announcement.
