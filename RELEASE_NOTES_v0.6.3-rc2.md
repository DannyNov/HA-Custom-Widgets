# v0.6.3-rc2 — physical testing required

Continues RC1 (41c35c0, code 51). VersionCode 52 exceeds the audited maximum 51.

- Slider keeps its local draft during a gesture, then displays the shared BrightnessCoordinator target until confirmation or failure/timeout. Delayed Dashboard values cannot restore the previous brightness after release. A newer target survives an older operation's echo or failure. Commands remain once per finished gesture.
- Brightness controls have a transparent 1dp capsule outline: the same ColorProvider as their existing ON yellow / OFF secondary content. Native drawable background through Glance 1.1.1; no custom widget View or extra click action. Widths and 48dp touch targets are unchanged; narrow layouts retain a compact percentage capsule.
- Adds ownership/rollback/stale-echo regressions and actual RemoteViews outline geometry, transparency, color and click-target tests on API31/API36. Existing historical upgrade and host scenarios remain required.

Publication requires full CI and permanent signed APK/AAB verification for this exact commit. PR #5 stays draft. RC1 is preserved. No Final, merge or Telegram announcement is authorized.

## Physical checklist

- Drag 88% → 39% and release: no return to 88% while waiting for HA.
- Several consecutive drags before confirmation; last target remains visible.
- ON yellow / OFF light outline exactly matches − % +.
- 5%, 65%, 100% keep capsule and −/+ geometry fixed.
- Narrow 180×110 fallback: compact percentage capsule opens Slider.
- Long Russian/English names; larger font; resize and scroll.
- ±5pp, power, external HA changes and timer remain correct.

RC2 remains a release candidate until explicit user approval after physical testing.
