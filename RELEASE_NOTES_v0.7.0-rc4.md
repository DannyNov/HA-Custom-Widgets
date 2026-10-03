# v0.7.0-rc4 — Honor visual follow-up

Based strictly on published RC3 42ef1127af68f1b8d475132e92004bb7a0588bca.
Temperature capability contour rotates another +15° clockwise: 90° total relative to RC2. Palette, alpha, capsule and rainbow remain unchanged.
Color help uses two explicit Text elements, with safe wrapping at large fonts:
RU: «Нажать — включить цвет» / «Удерживать — выбрать цвет».
EN: “Tap — turn on color” / “Hold — choose color”.
Tap, unknown-color fallback and long press retain RC3 behavior.
VersionCode 87 exceeds audited maximum 86, including unpublished CI candidates.
Mandatory gate: all unit/regression, API31/API36 hosts, 19 upgrade scenarios and permanent signed APK/AAB.
Pre-release for physical Honor review. PR #7 remains draft/unmerged. No Final, main/stable merge, Telegram or force-push.
