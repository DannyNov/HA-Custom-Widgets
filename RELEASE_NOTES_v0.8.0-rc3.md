# HA Custom Widgets v0.8.0-rc3

Narrow corrective Pre-release after the physically checked RC2.

- Dashboard settings consistently call the same tab **Main / Главное**. The switch, configure button, screen title and selection instructions use the same terminology. Persisted `showFavorites` and favorite device keys are unchanged.
- Each space keeps its own native scrollable framework collection in the widget host, with a distinct persisted view ID. Switching spaces changes visibility; each update sends adapter data only for the active space. Main, Scenarios and Maintenance have separate collections too. Glance 1.1.1 still translates their original rows and action transport. Stable item IDs support anchor synchronization during refresh, reorder and entity removal.

The host owns the actual first visible item and pixel offset; the app does not pretend to restore scrolling by writing an unused preference. A launcher that saves/restores the widget view hierarchy can restore its viewport. A completely new host with no saved view state starts at the top; catalog structure or widget size changes may require a new hierarchy. Honor launcher behavior still needs the physical A → B → A / B check.

RC2 header dimensions, outlined wrench orientation, small red attention exclamation, cleaned battery names, Scenarios → Maintenance order and all Maintenance attention rules are preserved. Numeric battery attention is still strictly **≤5%**; 6%+ and unknown/unavailable are not attention. Repairs, Updates, colors, light controls and transport are unchanged.

Validation before publication: full compile/unit gate, real framework ListView/AppWidgetHostView viewport tests on API31 and API36, RU/EN settings tests, narrow/large-font smoke, upgrade matrix including v0.7.0 Final/RC4 and v0.8.0 RC1/RC2, signed APK/AAB inspection and public re-download/hash verification.

Package `com.danila.hacustomwidgets`; minSdk31; targetSdk36; non-debuggable release. Permanent signing certificate remains unchanged.

PR #8 remains Draft/open/unmerged. Main is unchanged. No force-push, Telegram notification or Final publication. Stop for physical Honor verification.
