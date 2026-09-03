# HA Custom Widgets

[Русская версия](README.ru.md)

Native Android widgets for Home Assistant. Home Assistant entity names remain unchanged. Russian system locale selects Russian UI; every other locale uses English.

## Highlights in v0.6.0

- scrolling HA Dashboard with Main, space and Scenarios tabs;
- per-space grouping, card/metric ordering and visibility;
- large power controls for primary light and switch entities;
- configurable HA timer controls, including 120-minute presets;
- colored temperature, humidity and battery indicators;
- per-automation and per-script visibility;
- optional scenario launch button with pending, success and failure feedback;
- English and Russian app/widget UI;
- optional support through CloudTips or USDT (BEP-20 / TON).

Automation launch preserves its conditions (`skip_condition=false`). Green feedback confirms that Home Assistant accepted the call; it does not claim that every physical consequence completed.

## Security and build

The Home Assistant address and token are never stored in source. The token is encrypted with AES-GCM using Android Keystore, and Android backup is disabled. Prefer HTTPS for remote access and a dedicated HA user with minimum permissions.

Requires JDK 17, Android SDK 35 and Gradle 8.9:

```bash
gradle testDebugUnitTest assembleDebug
```

The release workflow builds a non-debuggable signed APK. Signing material is supplied only through GitHub Actions secrets and is not stored in Git.

## Support

Support is voluntary and is not payment for goods, services, or additional features.

- [CloudTips](https://pay.cloudtips.ru/p/ab27592e)
- USDT (BEP-20): `0xe7FA8d9608d50e1B7C645D8185473BCE3A3c14Df`
- USDT (TON): `UQB2SAZRVJZIHu7hpNSIYHKUPhn_frtrlHITFw6CbQKrNk9c`

Send only USDT using the exact network shown.
