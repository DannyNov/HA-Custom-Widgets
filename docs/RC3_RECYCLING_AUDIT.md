# v0.6.1-rc3 / code 36

Base: `test/v0.6.1-rc2`, `8b1e9ab06c15f1711f33f13abd80c38b91dcca24`.

## Findings and decision

RC2's native API 26–30 collection has two independent defects:

* `Factory.card()` creates a new RemoteViews action list but only appends children to
  `legacy_header_controls`, `legacy_controls`, and `legacy_metrics`. On reapply the
  host reuses the existing hierarchy; XML initialization is not repeated. Each bind
  appends another card's controls and metrics. `legacy_remaining` also retains its
  prior text/color when hidden, and a subsequent timer can inherit the error color.
* Button actions are set on the ROOT of nested button RemoteViews. Android 8's
  `RemoteViews.SetOnClickFillInIntent.apply()` rejects actions unless that particular
  RemoteViews is marked as a collection child. `RemoteViewsService` marks the outer
  row; Android 8's `ViewGroupAction` applies nested RemoteViews without propagating
  that flag. Additionally root-target fill-ins store a row tag instead of attaching
  an independent button listener. A correct template on the outer ListView does not
  repair this. Buttons share resource IDs, so moving all setters onto the outer row
  would repeatedly target the first button rather than each distinct control.

Sources: Android framework
[RemoteViews Android 8](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/core/java/android/widget/RemoteViews.java),
[RemoteViewsService Android 8](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/core/java/android/widget/RemoteViewsService.java).

The accumulation fix is small and included, with a real apply/reapply regression
test. However it does not repair click delivery. Restoring the custom collection
would require redesigning dynamic button addressing and testing actual OEM hosts.
RC3 therefore selects the existing RC1 Glance path on API 26–30 as well as newer
Android. No private Android APIs, fixed limits on entity controls, or OEM-specific
click workarounds are introduced. The native code is retained disabled for audit
and regression tests, not claimed as safe for re-enabling.

Trade-off: Glance may redraw the list and move the scroll position. Correct content
and the established Glance action translation take priority. Package replacement
triggers updateAll so existing RC2 widgets are replaced without deleting settings.

## Remaining audit points

* getViewAt returns immutable snapshots assembled by onDataSetChanged; returning a
  new RemoteViews alone never implied a new host hierarchy.
* getViewTypeCount=2 corresponds to the two TOP-LEVEL layout IDs (section/card).
  Nested button and metric layouts do not require additional collection view types.
  Different semantic cards intentionally share one card layout and must reset it.
* Stable IDs are namespaced section/card keys hashed by stableCollectionId. They do
  not prevent reapply or clear children. No evidence attributes this incident to a
  hash collision; RC3 does not attempt to cure accumulation by changing IDs.
* Adapter identity is widget-specific and deliberately independent of navigation,
  revision and entity payload. notifyAppWidgetViewDataChanged is appropriate for
  that adapter; it is precisely what exposes repeated row reapplication.
* ListView template PendingIntent is widget-specific and mutable on old APIs.
  Row extras carry widget/action/key/entity/domain. The rejection happens while
  binding nested click actions, before those extras can reach the receiver.
* Prior instrumentation installed a plain ArrayAdapter and reapplied HEADER setters;
  the other test only inflated drawables. Neither bound real heterogeneous rows,
  recycled their dynamic children, nor clicked native collection buttons.

## Regression coverage

DashboardRc3RecyclingTest feeds 12 heterogeneous fixtures in alternating order,
10 passes (120 bindings per renderer): light, switch, paused auto-off timer, battery,
temperature/humidity, 14 generic attributes, seven controls, automation, script,
scene, unavailable and empty. Both renderers cross a Parcel boundary. The native
test repeatedly reapplies onto the SAME card hierarchy. The Glance test uses the
production card composable and a pool keyed by actual layout ID, as Android does.
Each result must match a fresh apply including tree shape, hidden/visible text,
text color, image pixels, descriptions and clickability. The fallback policy is
checked for every API 26–36. Existing host and functional unit tests remain.

These tests do not claim end-to-end HA service execution, physical OEM validation,
or verification of every PendingIntent payload. Timer/scene/auto-off behavior on
the physical old phones remains unverified until the user tests RC3.

## Separate UX changes

Temperature uses a red drawable and humidity a blue drawable in both renderers;
battery resources and thresholds are unchanged. Space settings use 4dp outer gaps,
2dp card vertical padding, 1dp list item padding and less button content padding.
RU/EN helper text is shortened. Standard Material touch targets and font sizes are
retained. The exact 1.5–2-card visibility target still needs small-screen review.

## Delivery constraints

applicationId=com.danila.hacustomwidgets; versionName=0.6.1; versionCode=36;
minSdk=26; targetSdk=36; release debuggable=false. Existing signing configuration
and expected production certificate are unchanged. Workflow metadata/artifact
labels use v0.6.1-rc3. No main/tag/Release/Play changes.

Compile/unit/instrumentation/signing results are reported with the delivery run;
this document does not predeclare them successful.
