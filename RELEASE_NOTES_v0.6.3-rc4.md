# HA Custom Widgets v0.6.3-rc4

Pre-release for physical Honor validation. Final is not authorized.

- Shared auto-off card renderer places Timer and its blue duration label before Power. Power now occupies the same fixed trailing 48dp target column as ordinary control cards; flexible, wrapping timer text cannot move it.
- Confirmed active Timer glyph uses the existing `widget_light_on` yellow token, shared with ON Power. The blue circle and `widget_accent` duration text are unchanged. Inactive, unavailable and pending glyph behavior is preserved.
- RC3 floating Slider, Recents behavior, brightness controls, capsule, stable item IDs, scroll behavior and HA timer/click/pending/recovery architecture are preserved.
- Existing signing, package, minSdk31 and targetSdk36 are unchanged. RC1/RC2/RC3 remain available; PR #5 stays draft and unmerged. No Telegram.

Physical checklist: Timer left / Power fixed right; active yellow timer glyph with blue time text; inactive timer; 30/60/90/120; independent power and timer clicks; narrow width, long RU/EN names, resize and scroll; quick brightness and floating Slider regression.
