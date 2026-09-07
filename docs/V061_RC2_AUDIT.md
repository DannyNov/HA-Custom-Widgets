# v0.6.1-rc2 — audit and device-test candidate

Base: `test/v0.6.1-rc1`, `9cdc49c34b62a8e9e832e6df33d0cd87ca6b31b2`.
Candidate branch: `test/v0.6.1-rc2`. Version name `0.6.1`, code `35`, minSdk `26`, target/compile SDK `36`.

## 1. Dashboard viewport

RC1's coordinator publishes a StateFlow to an active Glance session, or calls `DashboardWidget.update` for a cold session. Both paths eventually reach Glance 1.1.1 `AppWidgetSession.processEmittableTree`, which calls `AppWidgetManager.updateAppWidget` for the entire translated widget. On API 26/29, `GlanceRemoteViewsService.setRemoteAdapter` also installs the service adapter and notifies collection data changes. Stable item IDs and coalescing reduce duplicate work but do not turn this into a collection-only update. The app cannot preserve an OEM host's ListView instance by retaining Kotlin card instances.

The exact internal rebind decision in the two physical OEM launchers cannot be proven from source alone: their host logs and an attached device are not available. In particular, we do not claim that every state change changes the adapter URI or the root layout alias. The confirmed architectural problem is that ordinary state changes still publish/reapply the whole Glance widget, the path on which the reported reset happens.

RC2 routes API 26–30 Dashboards through `DashboardLegacyCollection`. It uses a fixed XML shell and a `RemoteViewsService` factory. The identity is `hacw://dashboard/<widgetId>/collection/v1`, independent of revision, tab, entity contents, process and session. Ordinary updates send a header-only partial update and `notifyAppWidgetViewDataChanged`; they do not set an adapter, replace a container, change the root layout, or request scrolling. The factory reloads persisted repository state after process death and supplies stable section/card IDs and a fixed two-type count. Initial creation, system widget updates, resizing and theme changes can perform a full bind. Modern Android retains the existing Glance path.

The legacy rows reuse the existing ordering, grouping, filtering, timer, battery, power-color and action policies. This is a contained Dashboard host implementation; the application, settings and individual-entity widgets have not been rewritten. Header action PendingIntents and collection fill-in intents dispatch the existing callbacks. All system/configuration/theme entry points were audited to avoid accidentally routing an ordinary legacy update through Glance.

Instrumentation checks apply/reapply real Android RemoteViews and verify that the ListView and its adapter remain the same objects. This is stronger than an item-ID-only test, but is not a substitute for an OEM-launcher viewport test.

## 2. Reset and quick successive timer taps

`selectNextTimerDuration` in RC1 saved only `selectedDurationIndex`. It did not persist the requested duration, start/deadline or remaining time. The next tap recalculated from the previous HA snapshot. In addition, `loadState` reused `card.autoOffTimer` from the cached catalog structure rather than the latest configuration. Thus a successful HA reset did not imply fresh local input for the next tap.

RC2 persists a command generation and immediate timer overlay before scheduling work. The next tap sees the new duration and absolute deadline without waiting for REST, WebSocket or launcher rendering. Card timer configuration is loaded from current preferences. The elapsed threshold is exactly 60,000 ms rather than inferred through rounded display minutes. Expired timers display/start the minimum configured preset, including after preset reordering. The 120-minute interval remains supported.

Timer commands are serialized per HA timer on this installation. Superseded queued generations are skipped; expiry and timer-start workers share a per-timer lock. A successful start is persisted before reconciliation, so failure of the following REST read does not replay an already accepted `timer.start`. The worker checks the actual primary state before deciding whether it needs `turn_on`. An older snapshot cannot replace a pending reset or a server-confirmed reset. The persisted server URL prevents an old job from controlling a different HA connection.

## 3. Drift and HA ordering

RC1 already preferred a parseable `finishes_at` and retained the deadline for repeated identical fallback snapshots. This part was inspected and retained; it was not replaced with receipt-time countdown logic.

A concrete upstream decoder bug was found: the compact `subscribe_entities` protocol can send `lc` without `lu` when both timestamps advance together. RC1 only took `lu` for `lastUpdated`, leaving a stale or missing ordering timestamp. A fresh active/idle event could therefore be rejected against a REST-confirmed timestamp or have an incorrect fallback anchor. Both full compressed states and deltas now use `lu ?: lc`, retaining the previous timestamp only when neither changed. A regression test reproduces an event after a newer-than-cached REST snapshot and verifies acceptance.

Source: [Home Assistant compact state diff generation](https://github.com/home-assistant/core/blob/2026.9.0/homeassistant/components/websocket_api/messages.py), `_state_diff_event`.

Tests also cover server deadline precedence over fallback, equal remaining for two clients with different receipt times, UTC/offset equivalence, stale reset snapshots, and minute/120-minute boundaries. The exact photographed drift cannot be reconstructed without the original state payloads, device clock readings and logs; decoder ordering is a reproduced cause, not a claim that every possible drift source is now experimentally excluded.

## 4. Expiry did not switch off the device

The original composite-timer commit `0f04770` already stated that no delayed local shutdown was scheduled. RC1's timer worker is unchanged from that original implementation in this respect. The settings also explicitly required a HA automation. RC1's countdown worker only refreshed presentation. This is a missing execution path, not a shutdown worker accidentally deleted by RC1.

RC2 adds a persistent owner record and independent `DashboardTimerExpiryWorker`, scheduled after accepted starts. WorkManager survives process death/reboot; application startup restores missing work with KEEP. An inexact `setAndAllowWhileIdle` alarm provides an additional wakeup prompt; no exact-alarm permission is requested. API 31+ can enqueue expedited expiry work; older APIs use regular network-constrained work.

Before sending `turn_off`, the worker reads fresh timer and device states. It waits on paused/unavailable timers, adopts a newer active deadline, rejects idle transitions preceding the expected expiry (cancel), skips an already-OFF device, and skips a device manually switched ON after expiry. Only an idempotent domain-specific `turn_off` is sent to the recorded linked entity. It then refreshes relevant local widgets. The network layer supplies HA state to other clients in the existing way.

Ownership is local per timer, shared by widgets. A second phone that only observes a timer does not schedule shutdown. If two phones both start it, each can own work; fresh server checks and idempotent `turn_off` make duplicate expiry harmless in the ordinary case. There is no distributed atomic ownership or atomic HA compare-and-turn-off operation, so a cross-client race between the last read and a new start/manual action cannot be completely eliminated by this app. Same-installation start/expiry calls are locked, and newer generations supersede old ones.

**Limits:** Doze/EMUI, loss of network, a powered-off/force-stopped phone, and Android scheduling quotas can delay application-side shutdown. This implementation cannot promise exact execution while the phone is unable to run. A server automation remains the reliable option independent of the phone. No HA automation is created or modified. The timer settings explain this in RU/EN. See [Home Assistant timer documentation](https://www.home-assistant.io/integrations/timer/).

## 5. Temperature/humidity icons

RC1's `MetricLine` used `Text("🌡")` and `Text("💧")`, each in a 16-dp-wide cell. Their rendering depends on the OEM emoji font and glyph metrics. Battery used `ImageProvider(drawable)` and therefore followed a different path. The existing temperature/humidity vector resources were present but bypassed.

RC2 uses those drawable resources through ImageProvider/native ImageView, matching the battery resource path. Battery aliases, thresholds and color policies are retained. Instrumentation inflates these resources through RemoteViews on the test APIs. Final EMUI visual confirmation is still required.

## 6. Scene launch control

RC1 processed a card's `unknown` state before checking whether it was a scenario. A never-activated scene can be `unknown`; it therefore rendered `?` instead of its launch action. Scene state is not an ON/OFF state. RC2 gives scenario rendering priority over that generic unknown placeholder and preserves the unavailable guard and user visibility/run permissions.

Launch, pending, success and failure indicators now use drawable resources rather than font glyphs, inside the same 40-dp oval drawable / 48-dp touch target. Scene dispatch remains `scene.turn_on`; only automation exposes a state toggle. The precise shape of the photographed Honor oval cannot be independently established without that launcher, but both the erroneous unknown-state branch and the OEM-dependent glyph path have been removed.

## 7. Space setup cards

The card used three separate Material-control rows (header, grouping/card-order, group-order), plus 10-dp top/bottom padding and 4-dp internal gaps. There was no fixed giant card height to remove. On narrow screens those independent touch-target rows consumed approximately 172 dp before extra wrapping.

The card now uses two rows: card-order moves alongside the title, and grouping/group-order share the second row with weighted text buttons. Vertical padding is 4 dp. Space-list item spacing is reduced from 6 to 4 dp; other reorder lists keep their previous spacing. Font styles and standard 48-dp Material touch targets remain. Text can wrap for long RU/EN names. No overall settings redesign was made. Actual typography/wrapping on the physical devices remains part of acceptance.

## Validation and scope

The original 376 tests are unchanged. RC2 adds 24 unit regressions (400 total) and two Android instrumentation tests. The test workflow compiles instrumentation and runs host checks on API 26 and 29 emulators. Full test/build results and signed artifact digests are recorded in the delivery report after CI completes.

The approved brand PNG and About content, battery behavior, SDK levels, existing modern countdown path and production signing configuration are retained. The release workflow changes only RC2 artifact/version metadata. No main merge, tag, GitHub Release or Play publication is part of this candidate.

## Physical acceptance still required

On Honor View 10, Wileyfox Swift 2 Plus and the modern Honor: install over RC1, verify settings retention; scroll down and toggle several entities; reset after 60 seconds and immediately advance; test 120 minutes; compare two clients against the same HA deadline; let a short timer expire without interaction; repeat with the screen off; cancel/pause/restart a timer; inspect sensor icons and scene launch/feedback; review long RU/EN space names and drag controls. Capture timestamps and HA `finishes_at`/`last_updated` if any countdown disagreement remains.
