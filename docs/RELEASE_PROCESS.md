# Release process

Required flow: development → automated tests/CI → public GitHub Pre-release `-rcN` → user physical testing → Final release.

- Check all available branch/tag history, published releases and CI build metadata before assigning a versionCode. Every RC and Final uses a strictly higher code than every previously used build, including diagnostics and internal candidates.
- RC is a public testing release: use an `-rcN` versionName/tag and GitHub `prerelease=true`, with the permanently signed APK. Do not mark it Latest or announce it in Telegram.
- Publish only RCs authorized by the user, after all required tests/CI have passed for the exact source commit. Stop after publishing and wait for the user's physical test results.
- Any defect found during physical testing requires a fix, a new RC number and greater versionCode, automated tests, and another physical test cycle.
- Final requires explicit user confirmation. Build it from exactly the production source code of the physically approved RC. Only release/version metadata may change. Compare `app/src/main` (including resources and manifest), dependencies and build behavior with the approved RC; any production change requires another RC first.
- Final receives the ordinary GitHub Release and short RU/EN Telegram announcement to `@HACustomWidgets`. The existing Telegram workflow must continue excluding draft, prerelease and RC tags. Do not manually dispatch an RC announcement.
- Keep application ID, permanent signing identity, Android 12+ / minSdk 31 / targetSdk 36, Glance, and HA-owned timer auto-off unchanged unless explicitly authorized separately.

Current authorization (2026-09-25): the user successfully tested v0.6.1.1-rc1 on a physical phone and explicitly approved Final v0.6.1.1 and merging to main. RC1 is 7fe94a6bee9c0994161a046b73f978b37074043c / versionCode 45. Final uses versionCode 46, above the verified historical production/RC/diagnostic maximum of 45, with identical production sources and dependencies. Only final version/release metadata and an additional RC1 upgrade matrix entry are changed.

Current authorization (2026-09-28): remove the legacy Entity State widget and publish only v0.6.2-rc1 after all automated checks pass. VersionCode 48 supersedes the unpublished CI candidate 47. Final and Telegram are not authorized until the user physically tests the RC and approves Final.

Current authorization (2026-09-29): the user physically tested v0.6.2-rc1 (7f133dd7aafb60c937c5c7e3067596806a9a6502) and reported PASS: no observed bugs, only Dashboard in the picker, existing functionality preserved. The user explicitly authorized Final v0.6.2 and ordinary merge of PR #4. Historical branch/tag and 257 CI-run audit (117 unique commits) found maximum versionCode 48; Final uses 49. Application sources, resources, manifest, tests, dependencies and signing behavior remain identical to RC1. Final publication on main requires the full CI matrix and signed build for the exact commit. Since GITHUB_TOKEN-created releases suppress release-triggered workflows, publication dispatches the unchanged telegram-release.yml once for v0.6.2. RC1 is preserved.
