# Release process

Required flow: development → automated tests/CI → public GitHub Pre-release `-rcN` → user physical testing → Final release.

- Check all available branch/tag history, published releases and CI build metadata before assigning a versionCode. Every RC and Final uses a strictly higher code than every previously used build, including diagnostics and internal candidates.
- RC is a public testing release: use an `-rcN` versionName/tag and GitHub `prerelease=true`, with the permanently signed APK. Do not mark it Latest or announce it in Telegram.
- Publish only RCs authorized by the user, after all required tests/CI have passed for the exact source commit. Stop after publishing and wait for the user's physical test results.
- Any defect found during physical testing requires a fix, a new RC number and greater versionCode, automated tests, and another physical test cycle.
- Final requires explicit user confirmation. Build it from exactly the production source code of the physically approved RC. Only release/version metadata may change. Compare `app/src/main` (including resources and manifest), dependencies and build behavior with the approved RC; any production change requires another RC first.
- Final receives the ordinary GitHub Release and short RU/EN Telegram announcement to `@HACustomWidgets`. The existing Telegram workflow must continue excluding draft, prerelease and RC tags. Do not manually dispatch an RC announcement.
- Keep application ID, permanent signing identity, Android 12+ / minSdk 31 / targetSdk 36, Glance, and HA-owned timer auto-off unchanged unless explicitly authorized separately.

Current authorization: publish only `v0.6.1.1-rc1` after successful CI. Code 44 was used for internal corrective candidates, so RC1 uses 45. No authorization to publish Final `v0.6.1.1` or merge to main has been given.
