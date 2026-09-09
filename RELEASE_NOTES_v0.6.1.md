# HA Custom Widgets v0.6.1-rc6 — Android 12+ candidate

Version code: **41**. Minimum Android: **12 / API 31**. Target and compile SDK: **36**.

## Русский

- Dashboard объединяет близкие обновления, игнорирует одинаковые REST/WebSocket данные и сохраняет кэш, порядок и стабильные ID карточек. Переход рабочего задания из ожидания в выполнение не вызывает отдельную перерисовку.
- Countdown обновляется локально примерно раз в минуту без нового HA event. Используется `finishes_at`, при его отсутствии — привязанный ко времени снимка `remaining`. После завершения отображается минимальный preset. Проверен расчёт 120 минут.
- Круглые кнопки power, timer и запуска сценария используют единый Glance renderer и exclusive pending-глиф без наложения обычной иконки.
- Текстовые battery-состояния Tuya отображаются компактно и локализованно: высокий/средний/низкий; неизвестное состояние — серое. Процентные пороги сохранены.
- Добавлен раздел «О приложении» с логотипом, установленной версией, GitHub, Telegram, Privacy Policy, Issues, Apache-2.0 и контактным email.
- В сценариях доступны `scene` с `scene.turn_on`, без state toggle. Доступ к запуску включается отдельно для каждой сцены. Управление automation и script сохранено.
- Android 12+ использует единый Glance renderer. Legacy/static API 26–30 collection path удалён. SDK 36, AGP 8.9.1 и Gradle wrapper 8.11.1 сохранены.

## English

- Dashboard coalesces nearby updates, ignores duplicate REST/WebSocket payloads and preserves cached cards, ordering and stable item IDs. Worker start metadata no longer triggers a separate render.
- Countdown uses a local, best-effort minute tick, HA `finishes_at` or an anchored `remaining` snapshot. Expired timers return to the minimum preset; 120-minute calculations are covered.
- Power, timer and scenario buttons use the single Glance renderer and an exclusive pending glyph without overlapping the normal icon.
- Tuya textual battery levels have compact localized labels and semantic colors. Percentage thresholds remain unchanged.
- About includes the existing house-H logo, installed version and all official project, privacy, support and license links.
- Scenarios include scenes using `scene.turn_on`, with optional per-scene launch permission and no state toggle.
- Android 12+ uses one Glance renderer. The API 26–30 legacy/static collection path was removed. Target/compile SDK remain 36.

- Entities of one HA device remain one card; default metric order is Temperature → Humidity → Battery.

## Validation and remaining device checks

Validation is performed by the RC6 workflows on API 31 and API 36, including upgrade checks from v0.6.0.3 and RC5. WorkManager countdown timing remains best-effort and can be delayed by Android power management. Actual auto-off is performed by the configured Home Assistant automation, not by the phone.
