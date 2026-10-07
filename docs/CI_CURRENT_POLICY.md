# Current CI and historical release freezes

`compile-test-v035.yml` preserves historical release scope checks at the
commits they approved. The v0.8.0 Final byte/inventory freeze belongs to
`fe0b01267f09478ebaee1e40ae82a3c930bd45b8`, not to future feature branches.
Running it on OAuth development rejected the new application inventory before
compilation (`Application file inventory changed`). Its script is unchanged;
it now runs in a detached worktree, like the other historical guards.

Current HEAD runs `verify-current-project.py` and its regression tests:
strict UTF-8, replacement/control/mojibake detection, well-formed XML,
package `com.danila.hacustomwidgets`, version 0.9.0/108, minSdk 31,
compile/target SDK 36, and preservation of the 31 upgrade and API 31/36 host
matrices. Unit, instrumentation and upgrade jobs remain enabled. This check
does not freeze future source bytes or replace functional tests.

The existing `android.yml` uses the existing signing secrets, tests and release
build, verifies the expected certificate, package/version and non-debuggable
APK, and uploads APK/AAB evidence. No signing or publishing behavior changed.

Full `lintDebug` is not called by these workflows. `assembleRelease` does run
`lintVitalRelease`. On 2026-10-07 full lint still failed with 54 RestrictedApi
errors in five Glance files unchanged since the pre-OAuth release commit
`d159ba3`: BrightnessControls, DashboardCollectionsRenderer, DashboardWidget,
GlanceCollectionComposer and MaintenanceContent. There are no lint error
suppressions or new baselines in this correction. This remains a real legacy
lint failure outside the existing CI gates, not a lint PASS. GitHub main rules
queried on that date listed deletion/non-fast-forward protection and no required
status checks; readiness here still requires the existing build/test workflows.

The text correction restores seven Russian labels/messages in AuthPanel and
MainActivity, including the local fallback trust warning. It changes no OAuth,
network, routing or callback logic. No version change or physical test is
required for this text/CI correction.
