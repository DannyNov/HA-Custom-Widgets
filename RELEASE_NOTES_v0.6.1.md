# HA Custom Widgets v0.6.1 — local review candidate

Version code: **38**. Minimum Android: **8.0 / API 26**. Target and compile SDK: **36**.

## Русский

- Dashboard объединяет близкие обновления, игнорирует одинаковые REST/WebSocket данные и сохраняет кэш, порядок и стабильные ID карточек. Переход рабочего задания из ожидания в выполнение не вызывает отдельную перерисовку.
- Countdown обновляется локально примерно раз в минуту без нового HA event. Используется `finishes_at`, при его отсутствии — привязанный ко времени снимка `remaining`. После завершения отображается минимальный preset. Проверен расчёт 120 минут.
- Круглые кнопки power, timer и запуска сценария используют oval drawable, совместимый с Android 8–11.
- Текстовые battery-состояния Tuya отображаются компактно и локализованно: высокий/средний/низкий; неизвестное состояние — серое. Процентные пороги сохранены.
- Добавлен раздел «О приложении» с логотипом, установленной версией, GitHub, Telegram, Privacy Policy, Issues, Apache-2.0 и контактным email.
- В сценариях доступны `scene` с `scene.turn_on`, без state toggle. Доступ к запуску включается отдельно для каждой сцены. Управление automation и script сохранено.
- SDK 36, AGP 8.9.1 и Gradle wrapper 8.11.1. minSdk не изменён. Уже существующий яркий цвет активного таймера `#2196F3` сохранён.

## English

- Dashboard coalesces nearby updates, ignores duplicate REST/WebSocket payloads and preserves cached cards, ordering and stable item IDs. Worker start metadata no longer triggers a separate render.
- Countdown uses a local, best-effort minute tick, HA `finishes_at` or an anchored `remaining` snapshot. Expired timers return to the minimum preset; 120-minute calculations are covered.
- Power, timer and scenario buttons use oval drawable backgrounds on older Android versions.
- Tuya textual battery levels have compact localized labels and semantic colors. Percentage thresholds remain unchanged.
- About includes the existing house-H logo, installed version and all official project, privacy, support and license links.
- Scenarios include scenes using `scene.turn_on`, with optional per-scene launch permission and no state toggle.
- Target/compile SDK 36, AGP 8.9.1 and Gradle 8.11.1 wrapper; minimum SDK remains 26.

- Android 8–11 (API 26–30) use the static collection fallback built from the same shared Dashboard card model as the modern renderer. Entities of one HA device remain one card; default metric order is Temperature → Humidity → Battery.
- Routine state changes notify the existing collection only. No outer layout update or adapter rebinding is issued for those revisions.

## Validation and remaining device checks

Compile, 376 JVM unit tests, release APK and release AAB builds passed. Release artifacts are unsigned; no signing secrets were inspected or changed. No commit, push, GitHub release or Play publication was made.

Honor View 10, Wileyfox Swift 2 Plus and a modern Android device still require launcher testing: scroll to the middle, toggle an entity repeatedly, exercise a timer, change it to 120 minutes, verify expiry, and inspect circular buttons in both themes. Glance still generates RemoteViews for the widget; these changes reduce redundant publications and preserve collection identity, but do not prove behavior of every OEM launcher. WorkManager countdown timing is best-effort and can be delayed by Android power management.
