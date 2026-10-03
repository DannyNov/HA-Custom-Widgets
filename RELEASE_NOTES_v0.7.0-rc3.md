# v0.7.0-rc3 — Honor visual corrections

Based strictly on published v0.7.0-rc2, 00fdbf0ab983db90d2ad8f6ff25961fb3e6652dc.

- Temperature capability contour uses the same palette, alpha, thickness and capsule geometry, with its linear gradient oriented +75 degrees clockwise in Android coordinates. Warm moves to the upper-left shoulder and cold to the lower-right, as explicitly selected after clarification. Endpoints are normalized to the capsule's projected extent to retain the RC2 palette contrast. Rainbow is unchanged.
- Color help: «Нажать — включить цвет • Удерживать — выбрать» / “Tap — turn on color • Hold — choose”. Accessibility wording is equally explicit. Tap, hold, unknown-color fallback and transport are unchanged.

Unified 10dp tracks, circular 20dp thumbs, >=48dp targets and all other RC2 behavior are retained. Complete compile/unit, host API31/API36 and all mandatory historical upgrades including RC2 are required before publication. APK/AAB must retain com.danila.hacustomwidgets, minSdk31/targetSdk36, debuggable=false and the permanent certificate.

Public Pre-release only. PR #7 remains draft/unmerged. Stop for Honor: check temperature-only/dual orientation, unchanged rainbow, clear/unclipped Color help and quick tap/hold behavior. No Final, main/stable merge, Telegram or force-push.
