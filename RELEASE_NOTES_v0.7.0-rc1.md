# HA Custom Widgets v0.7.0-rc1

Public testing release; physical Honor verification is required before any Final.

- Dashboard brightness capsules now indicate color-temperature and color capabilities with one or two subtle inner contours, including narrow capsules. Brightness-only appearance and targets are retained.
- The existing floating light control adds real-range Kelvin adjustment, reachable Warm/Medium/Cool presets, color restore, and a compact wheel with accessible hue/saturation sliders.
- Temperature/color selections use light.turn_on, including when the lamp is OFF. Canonical HS service calls use Home Assistant's officially documented conversions to the lamp's supported mode and keep brightness independent.
- Current Home Assistant state remains authoritative; confirmed temperature/color history is connection/entity scoped. Pending mode targets are coalesced and share network serialization with brightness and Power. Reconnect never replays commands.
- First color-bar tap opens the picker if no confirmed color exists. The picker sends once on explicit Apply; Cancel/Back sends nothing.
- Dashboard configurations, Favorites, brightness history, timer and realtime storage remain compatible with v0.6.4.

Honor checks: brightness-only; temperature contour/presets/drag/OFF→temperature; color contour/long press/wheel/restore/OFF→color; both contours survive mode switches; external HA realtime; Power during adjustment; multiple Dashboards; narrow/resized/scroll/large font; timer/Favorites/brightness controls.

RC only: no Final, main merge or Telegram announcement.
