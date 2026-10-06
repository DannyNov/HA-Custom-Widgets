# Maintenance v0.8.0 RC1

Base main: 2568552bf0d46c37ba91a75c2508b4d31a6e0fd4. Application/build sources match published v0.7.0 (58a39ededb89c325e17da016c134726c7c5e0374); the newer main updates documentation only.

Preflight: 467 Actions runs plus all branches/tags and retained commit history; maximum historical versionCode 88. The initial README-only commit has no Android metadata. Local candidate 89 is retained as an internal candidate; the public candidate receives a greater code.

Battery discovery uses sensor/binary_sensor device_class=battery, including diagnostic/hidden entities and excluding disabled entities. Numeric percentage attention is finite 0–5 inclusive. Nonpercentage low and binary on/low alert. Unknown/unavailable/invalid numeric values do not alert. Rendering normalizes binary on/off for the existing batteryHealth and batteryIconResource policies without modifying ordinary Dashboard colors.

All update entities are tracked, but only on appears in attention. Repairs/list_issues already filters inactive issues server-side; absent active defaults to true, explicit active=false and ignored=true are excluded. Critical/error/warning are sorted by severity; unknown future severities remain visible. Titles use frontend/get_translations category issues for en/ru, component.DOMAIN.issues.KEY.title and literal placeholder substitution. No notification contents, webviews, write services or fix flows are involved.

Sources verified on 2026-10-06:
- https://github.com/home-assistant/core/blob/dev/homeassistant/components/repairs/websocket_api.py
- https://github.com/home-assistant/core/blob/dev/homeassistant/components/frontend/__init__.py
- https://github.com/home-assistant/frontend/blob/dev/src/data/repairs.ts
- https://github.com/home-assistant/frontend/blob/dev/src/panels/config/repairs/ha-config-repairs.ts
- https://www.home-assistant.io/integrations/update/
- https://www.home-assistant.io/integrations/binary_sensor/

Per-widget show_maintenance defaults to true without rewriting old stored configuration. Hiding a selected Maintenance tab persists a fallback to the first visible ordinary space, then Favorites/scenarios/explicit empty state as available. Data is retained. The tab precedes Scenarios to preserve the existing Scenarios-last navigation contract.

Catalog persists complete maintenance entity metadata independently of ordinary card limits and user-hidden card selections. The existing atomic state store overlays manual/realtime entity states. Entity IDs join the existing shared subscription set. The shared HA socket additionally subscribes to repairs_issue_registry_updated and entity/device/area registry changes, with a 500ms debounce. Repairs refresh is read-only on a bounded authenticated request socket; catalog registry changes fetch complete catalog. Manual refresh always fetches catalog and Repairs. Repairs cache is scoped to HA connection identity.

Existing compile/unit/host/upgrade assertions are retained. Historical tests that intentionally test no extra system tab explicitly disable Maintenance in their fixture, preserving exact assertions. Existing transport fixtures inject an empty Repairs reader instead of accessing their fake hostname. Upgrade source fixture remains compilable with historical application classes, using reflection only after upgrading. All 21 previous upgrade cases remain, plus v0.7.0 Final on API31/API36 (23 total).

Release rule: exact-source full gate and permanent signed artifacts first; public RC only; no force push, main merge, Final, or Telegram. Stop for physical testing after publication.
