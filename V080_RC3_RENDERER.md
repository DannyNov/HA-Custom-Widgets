# RC3 collection contract

Dependency remains exactly `androidx.glance:glance-appwidget:1.1.1`.

Each widget has a native FrameLayout shell containing one native ListView per catalog
space and per Main/Scenarios/Maintenance/empty-tab context. The view ID registry is
persisted separately for each appWidgetId; it contains collection identities, never
a pretend scroll index. RemoteViews.addStableView reuses shells by these IDs on API31+.
Inactive collections receive visibility setters only, preserving their existing adapters,
first visible stable item and pixel offset in the host. Only the active collection's
RemoteCollectionItems are included in an update, avoiding the rejected prototype's
2,329,696-byte all-space payload.

Rows use the actual Glance 1.1.1 normalization and collection translation context,
including its fill-in action transport and view-type layout capacity. The small bridge
is adapted from the pinned library's GlanceRemoteViews/LazyListTranslator implementation;
it uses typed internal library entry points, not reflection or launcher-specific APIs.
Upgrading Glance or Kotlin requires re-auditing this version-pinned bridge.

The first publication after provider recreation is prepared before provideContent:
there is no temporary loading hierarchy that would discard retained host collections.
The system controls ListView stable-ID synchronization and missing-anchor fallback.
No application API can read the scroll position of a ListView owned by another launcher.
Saved AppWidgetHostView hierarchy state can restore distinct collection positions;
a completely new host without that saved state starts at the top. Widget size or
structural header option changes may still cause launcher reinflation.

Regression tests round-trip real RemoteViews through Parcel, enforce a 900,000-byte
transport budget, apply/reapply to an unmodified attached AppWidgetHostView, await a
real pre-draw layout frame, and inspect actual ListView item IDs/top offsets. They cover
per-space/per-widget independence, system contexts, refresh, missing entities, reorder,
collapse, hide/show, saved hierarchy recreation, legacy configuration, more than ten
collections, narrow/large-font rendering and a real Glance fill-in callback click.
Physical Honor acceptance remains separate from these framework-contract tests.
