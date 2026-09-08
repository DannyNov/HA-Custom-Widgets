# RC5 viewport audit and prototype

Base: `test/v0.6.1-rc4`, `f2d78c2890ef95b50e0249a3b4a5ff30ab26ea52`.
This document describes an experimental implementation; passing OEM acceptance must not be inferred from source inspection.

## Findings from RC2 / RC3 / RC4

RC2 enabled `DashboardLegacyCollection` through API 30. Its factory assembled nested RemoteViews with `addView`, without clearing old children. Reapply replayed those actions against the previous hierarchy. RC3 added complete removal of dynamic children but disabled this renderer because the click actions belonged to nested RemoteViews. Android's collection-child flag is attached to the factory result, not arbitrarily nested RemoteViews. RC4 retains the disabled renderer and the RC3 regression tests.

RC4 Glance already supplies stable card/section IDs. The bundled Glance source (`GlanceRemoteViewsService.kt`, `LazyListTranslator.kt`, `WidgetLayout.kt`) shows:

- Older Android uses a service adapter identified by widget ID, generated view ID and size information. Identity is not randomly regenerated on every update; claiming that would be incorrect.
- Each translation sets the adapter again, saves collection data in an in-memory store and calls `notifyAppWidgetViewDataChanged`.
- Factory data loading can start a Glance session after process death. Layout configuration tracks structural layouts, while ordinary list contents are excluded from the outer layout node.
- Stable item IDs support recycling and host synchronization but do not prevent the outer collection view from being replaced or rebound.

The physically reported direction-dependent reset is consistent with host rebind/reapply behavior, not proof of an always-changing adapter URI. Without launcher logs from Wileyfox/Honor the exact OEM decision that loses the viewport remains unproven. RC5 therefore removes routine outer adapter rebinding from state updates rather than relying on a guessed scroll restoration API.

## Alternatives

**A — Glance anchor restoration.** Android exposes no provider-side getter for the first visible item or pixel offset of a ListView living in another application's AppWidgetHost. `RemoteViews.setScrollPosition` is a smooth-scroll command with a known index, not an anchor getter. Guessing an anchor from the last clicked card fails when the user scrolls again or a remote client changes state. Glance does not expose a supported provider callback carrying the launcher's viewport. Stable IDs alone are already present in RC4. Full replacement or renewed adapter binding remains under framework/host control.

**B — repaired dynamic RC2 rows.** Clearing children fixes accumulation but does not by itself fix nested fill-in click actions. Ordinary `setOnClickPendingIntent` is explicitly unsupported for collection items. Reintroducing it would rely on an implementation accident, not API26 compatibility.

**C — static collection shell and static XML rows.** Chosen prototype. Keep Glance on newer Android. For API26–30, bind one ListView and service URI once; send only header setters as partial updates and notify the existing adapter for new data. The factory reads the existing repository and shared presentation policies. Rows have fixed XML slots, with all setters and fill-in actions attached to the factory's root RemoteViews. No `addView` occurs in the new row renderer.

## Safety properties

- Separate layout IDs for compact cards, larger cards and sections; fixed factory view-type capacity of three.
- Every slot resets visibility, enabled state, text, drawable, background, content description and click intent on every render.
- Controls use the standard collection PendingIntent template and root-level fill-in intents, including entity, domain, card key and widget ID. No nested RemoteViews own a click action.
- More than eight controls or twelve metrics creates continuation rows with stable page keys; input is not silently truncated.
- State changes do not alter the count of control slots or continuation pages. Unavailable controls remain laid out but disabled.
- Header updates contain no `setRemoteAdapter`, `setSelection` or `setScrollPosition` action.
- Timer repository reconciliation, workers, preset selection and server authority are unchanged.
- Pending uses one image slot with a replacement drawable; circular background and bounds stay fixed.

## Validation strategy

The debug-only synthetic host uses 30 heterogeneous cards and an actual AppWidgetHost / RemoteViewsService Binder path. It checks click transport, waits for a visible revision marker, measures first-visible stable ID and top offset after twenty updates, and records any transient scroll to item zero. The fixture includes the selected control metrics required by the real customization policy. Debug components are not packaged in release.

Separate checks compare fresh binding and reapply across heterogeneous static rows. Existing RC3/RC4 tests remain unchanged, including the assertion that the old unsafe renderer is disabled. New tests do not make OEM acceptance unnecessary: Wileyfox Swift 2 Plus, Honor View 10 and a modern Honor still need the requested physical sequence.

## Primary references

- [Android RemoteViews API](https://developer.android.com/reference/android/widget/RemoteViews): collection click restriction and scroll setter semantics.
- [Android collection widget guidance](https://developer.android.com/develop/ui/views/appwidgets/collections): PendingIntent template, fill-in intents, factory data updates.
- Bundled dependency source inspected locally: Glance service, lazy list translator and layout configuration. No private or invented scroll API is used.
