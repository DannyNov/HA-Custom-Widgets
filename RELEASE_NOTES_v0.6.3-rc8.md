# HA Custom Widgets v0.6.3-rc8

Timer interval and remaining time now start at exactly the same fixed X from the user's 1.png reference (464/1098 of the safe card width). Neither preset cycling nor countdown text width participates in the anchor calculation. Timer remains 56dp before the text anchor (48dp touch target + 8dp gap). Power retains its independent right 48dp zone.

Remaining time uses Start alignment in a full lower row below both controls, including the horizontal space beneath Power. Long RU/EN captions wrap safely on narrow widths and larger fonts. Accepted colors, click payloads, auto-off backend, brightness, floating Slider, Recents and independent widgets remain unchanged.

versionCode 66 exceeds the audited actual maximum 65 across 326 CI runs and 140 unique commits, including unpublished candidates.

Publication requires all compile/unit/regression, API31/API36 host, seven upgrade scenarios and signed APK/AAB checks to pass on this exact commit. Package/minSdk31/targetSdk36, debuggable=false and permanent signing certificate are preserved.

Honor physical checklist: compare with 1.png; 90 мин starts exactly above Осталось…; repeat for 30/60/120; remaining left edge stays fixed during countdown; Timer and Power do not jump; full Осталось 1 ч 30 мин visible; both clicks; resize/narrow/scroll; brightness/Slider/Recents.

RC1–RC7 retained. RC8 remains a Pre-release pending explicit physical Honor approval. PR #5 remains draft/unmerged. No Final, force-push or Telegram.
