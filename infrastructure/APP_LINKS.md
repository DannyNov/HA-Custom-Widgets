# OAuth website deployment prerequisite

Production remains on dannynov.github.io. No separate domain is planned. Nothing has been published.

The complete deployment layout, verified APK certificate evidence, Play signing distinction, callback directory redirect limitation, privacy boundary, URL acceptance checks, Android verification commands and physical-device checklist are in [PAGES_PUBLICATION_PLAN.ru.md](PAGES_PUBLICATION_PLAN.ru.md).

- Copy the contents of domain-root/ to the approved user Pages source in DannyNov/dannynov.github.io, preserving existing content and associations.
- Publish project docs/ separately in DannyNov/HA-Custom-Widgets, preserving existing privacy policy and publishing configuration.
- Both publishing roots include .nojekyll. Do not publish a debug certificate, private key, tokens or user configuration.
- client_id: https://dannynov.github.io/HA-Custom-Widgets/
- redirect_uri: https://dannynov.github.io/HA-Custom-Widgets/auth/callback

The manifest deliberately matches the exact slashless callback. Verified Android interception precedes HTTP fetching; the directory-index fallback may redirect to a slash and is not a code relay. A fallback browser request can expose code/state to the hosting provider before history cleanup. Do not claim zero hosting metadata exposure.

LLAT remains available. Commits, push, releases, version changes and Pages deployment require separate user authorization.
