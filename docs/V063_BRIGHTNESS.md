# Brightness implementation and review

Base: published v0.6.2, `718136bb62386ffbfff3bcdd18de4d403ad14d70`, versionCode 49.
Development builds used code 50. Public RC uses versionName 0.6.3-rc1 and code 51. Audit of all 279 available CI runs (124 unique source commits), branch/tag history and published releases found maximum code 50; see V063_VERSION_AUDIT.json. No Final or Telegram dispatch is permitted.

## Data

`LightBrightness` retains capability presence separately from its validity and current numeric brightness. Modern supported modes take priority: brightness, color_temp, hs, xy, rgb, rgbw, rgbww, white. Explicit null, malformed, empty and unknown-only mode lists do not enable control. Legacy feature bit 1 applies only when the modern attribute is absent. Current brightness never establishes capability.

REST and full state_changed share the full entity decoder. Compact subscriptions retain brightness, supported modes, current color mode and legacy features across deltas, including attribute null/removal. Entity removal produces unavailable state.

HA values 0–255 round to UI 1–100. Null remains unknown. Only accepted HA values above zero update durable history. History is scoped to the connection identity and is used as a fallback only while OFF. A new connection cannot inherit another server's remembered brightness.

Brightness participates in payload equality and timestamp ordering. The existing atomic record and render revisions deliver attribute-only changes. Commands and optimistic values are process-local and are not replayed after death or reconnect.

## Commands

One coordinator is shared by AppContainer, all Dashboard callbacks and the Activity, keyed by connection and entity. Targets are absolute integer brightness_pct sent to light.turn_on. It coalesces for 300 ms, permits one in-flight call, and replaces the queued target. Further taps calculate from the latest optimistic target.

Confirmation reads accepted realtime numeric state with a newer HA timestamp and a one-percentage-point rounding tolerance. If no confirmation arrives in four seconds, one state GET is permitted. Failed/unconfirmed targets remove the overlay and expose actual HA state plus an error. No REST polling loop or per-slider-movement work request is created.

Power cancels the queued brightness target and waits for the in-flight brightness call before sending its existing service command. Brightness does not call timer services or create power operations. Existing timer/power recovery remains in its original worker.

## Layout and accessibility

The name takes remaining width; percentage and power have reserved widths and independent touch targets. Percent text fills its fixed field, preventing the one-pixel center shift found by the first host run. Touch targets are at least 48 dp. At narrow widths or increased font scale, only the central percentage opens the Activity. The percentage field grows with font scale; its compact numeric text is capped at 2x scaling to avoid clipping. The Activity retains the application theme and full lamp name, and waits for confirmed brightness rather than inventing a slider position.

Host tests cover 180/230/320 dp, 1x/1.5x/2x font scales, long bilingual names, 5/65/100 fixed geometry, actual click targets for capability/availability states, and Activity routing. Unit tests cover parsing, conversions, queue replacement, stale echoes, failures, reconnect, power serialization and send-on-finish. Existing tests remain in the full matrix.

## Release boundary

API31/API36, full unit/regression, upgrades from v0.6.2 and every previous mandatory fixture, signed APK/AAB verification and a final source review must pass for the publication commit. RC is prerelease=true and latest=false. No workflow dispatch to Telegram is allowed. Physical phone validation is mandatory before any Final decision.
