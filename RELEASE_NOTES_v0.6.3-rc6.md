# HA Custom Widgets v0.6.3-rc6

RC6 moves the accepted RC5 Timer composition visibly right toward the center. RC5 centered the entire block, including its remaining-time text, keeping the circular control too far left. RC6 centers the primary Timer + widest preset zone within the available area before the fixed 48dp Power column. A dp spacer sets that anchor independently of remaining-time width; the existing text column uses the space to its right and wraps before Power. Narrow widths and large fonts use a safe start anchor with full available text capacity.

Interval remains to the right of Timer at its vertical center; remaining time stays directly below interval with the same left text edge. Presets, click routing, Timer/Power colors, backend, brightness, floating Slider, Recents, signing and SDK invariants are preserved. No pixel offsets or Honor-specific coordinates are used.

VersionCode 63 exceeds the audited maximum 62 across 315 CI runs and 137 refs, including unpublished candidates. RC1–RC5 are retained. PR #5 stays draft and unmerged; no Final or Telegram publication.

On Honor compare against RC5: Timer noticeably nearer center; interval/remaining alignment preserved; Power right; no horizontal jumps across 30/60/90/120; both clicks; narrow/resize/scroll; quick brightness/Slider/Recents regression. RC6 remains an RC until explicit physical approval.
