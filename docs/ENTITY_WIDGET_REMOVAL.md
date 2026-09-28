# Entity widget removal audit (0.6.2-rc1)

Base: main `28b1aaab6b0e6788b75f1a94eedda3eea0b268ad`. Stable:
`v0.6.1.1`, `df0fff631634657403b5436d8a7440de91f79cba`, versionCode 46.
All published tags and branch tips were checked; no 0.6.2 RC existed. RC1 uses 47;
CI also checks the full fetched version history before allowing publication.

## Dependency audit

Removed EntityStateWidget/Receiver, EntityStateWidgetConfigActivity,
EntityWidgetRenderCoordinator, RefreshEntityAction, WidgetIdKey, WidgetConfig,
WidgetMetric, WidgetRepository, entity_state_widget_info.xml, provider/config
manifest entries and the two exclusive strings in both locales.
The device-only paths were removed from DashboardEventCoordinator,
WidgetSyncWorker, ThemeChangeReceiver, AppContainer and the catalog test fixture.
No other production/test references to these classes remain.

Preserved shared infrastructure: AppContainer (now in its own file), the exact
metric-label shortening algorithm (MetricLabels), HA client/security/catalog,
Dashboard repositories/rendering/config/actions/realtime/timers, periodic worker
identity `ha_widget_periodic_sync` and theme/package receiver.
`widget_preview.xml` and its background are referenced by Dashboard's provider
XML and are therefore retained. Dashboard provider sizing is unchanged.
README/README.ru.md do not promise the removed type; both remain byte-identical,
including the latest badge/discoverability work. Historical release notes remain historical.

## Android upgrade semantics

Audited AOSP AppWidgetServiceImpl:
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/appwidget/java/com/android/server/appwidget/AppWidgetServiceImpl.java

`updateProvidersForPackageLocked` enumerates the updated package's receivers;
providers missing from the keep set go through `deleteProviderLocked`.
`deleteWidgetsLocked` removes their IDs and host references; `removeWidgetLocked`
schedules host removal notification. Provider broadcasts are cancelled. The
removed receiver cannot receive onDeleted/onDisabled, so app preferences are
not cleaned by that lifecycle. Launcher3 handles host removal callbacks and
checks restored provider availability. OEM launcher UI still requires physical
verification; the app cannot edit another launcher's private placement database.

No tombstone provider is needed. A package-update receiver already exists, so
the application starts cleanup on package replacement as well as ordinary startup.
`LegacyEntityWidgetCleanup` runs before Dashboard startup/Glance rendering.
It clears `entity_widgets`, including old single-entity keys and orphan records,
removes only corresponding `dashboard_sync_freshness` keys, cancels only their
Glance `appWidget-N` work, and removes their per-ID state/layout files (Glance
1.1.1 naming verified in its source). Dashboard IDs are explicitly excluded.
The dedicated preferences are cleared last; file/commit failure leaves them for
retry. Empty installations are a no-op. This bridge must remain while direct
upgrades from <=0.6.1.1 are supported; it can then be removed.
No Dashboard config, secure connection store, timer state or shared periodic
work is deleted. Old legacy records no longer participate in subscription sets,
REST reconciliation or periodic sync even if cleanup must retry.

## Verification

Existing unit/regression and all Android host tests remain enabled. Added checks
cover the source and installed manifest/picker, unchanged shared label behavior,
idempotent cleanup, orphan records and absence of legacy state.
The stable upgrade CI cases install the new instrumentation APK against the old
application, bind actual Dashboard and optionally legacy AppWidget IDs, seed
Dashboard order/visibility/timer configuration and encrypted connection, replace
only the application APK, then verify byte-equivalent Dashboard preferences,
connection preservation, surviving Dashboard ID and removal of legacy ID/state.
The full existing host suite runs afterwards. Existing older-version upgrade
jobs and API31/API36 host matrix are retained.

Publication is gated on successful tests, host/upgrade jobs and a signed release
build of the exact source SHA. Signing secrets and certificate are unchanged.
Only a public `prerelease=true`, non-Latest RC is published. Final remains gated
on explicit physical phone approval; Telegram workflow is unchanged.
