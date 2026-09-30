# HA Custom Widgets v0.6.3-rc1

## Русский

Публичный кандидат для физической проверки. Это не Final.

- Регулировка яркости поддерживаемых ламп: название, − / процент / +, отдельная кнопка питания.
- Шаг кнопок — 5 процентных пунктов, диапазон 1–100%. Из OFF лампа включается на выбранной яркости.
- Нажатие на процент открывает компактный экран со Slider. Команда отправляется после завершения выбора.
- На узкой ширине остаются название, процент для открытия Slider и power.
- При OFF используется последняя ненулевая яркость, действительно подтверждённая HA. Если значения ещё нет, показано «—%» и изменение заблокировано.
- Быстрые нажатия и несколько Dashboard используют общую очередь последнего target. Недоступные лампы не принимают команды.

Установка: установить APK поверх v0.6.2, не удаляя приложение. Требуется Android 12 / API31 или новее.

Физическая проверка: ON/OFF; шаг ±5; Slider по нажатию %; длинные RU/EN имена; dimmable/onoff; unavailable; быстрые нажатия; внешнее изменение яркости в HA; несколько виджетов; resize, scroll, таймер и отдельный power.

После проверки сообщите результат. Final и Telegram-анонс возможны только отдельным последующим этапом.

## English

Public release candidate for physical phone validation, not a Final release.

- Brightness-capable lights get separate name, brightness and power zones.
- Buttons change brightness by 5 percentage points within 1–100%; changing an OFF light turns it on at the selected level.
- Tap the percentage for a compact Compose Slider. Only the finished selection sends a command.
- Narrow layouts retain the name, percentage shortcut and power button.
- OFF lights can use their last nonzero HA-confirmed brightness. Unknown brightness shows “—%” without inventing a value.
- Widgets and the slider share one latest-target queue. Unavailable lights cannot send commands.

Install the APK over v0.6.2 without uninstalling. Android 12 / API31 or later is required.

Please test brightness, power, timers, rapid taps, external HA changes, multiple widgets, long names, resizing and scrolling before approving a Final release.
