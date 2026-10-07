# Telegram release announcements

Use the existing Actions secrets `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID`.
The destination must be `@HACustomWidgets`, with bot permission to post.
Do not print or change secrets during release preparation.

Every final Release body must contain exactly one ordered pair:

```text
<!-- telegram:start -->
RU: Содержательный анонс изменений для пользователей…

EN: A substantive equivalent announcement for users…
<!-- telegram:end -->
```

The shared validator `.github/scripts/telegram-announcement.cjs` requires
substantive RU and EN sections (at least 100 characters each), no more than
1600 characters total, and no URLs, SHA, CI, certificate, package, versionCode
or test diagnostics. Missing, empty, duplicate, reversed, oversized and
placeholder blocks fail. No fallback is sent, including on manual dispatch.

Use `RELEASE_NOTES_v0.9.1.md` as the exact v0.9.1 GitHub Release body. It
includes the v0.9.1 changes and key v0.9.0 capabilities. Validate before creation:

```sh
node --test .github/scripts/test-telegram-announcement.cjs
node .github/scripts/telegram-announcement.cjs RELEASE_NOTES_v0.9.1.md
```

Regression CI validates release preparation. `publish-final.yml` validates
its exact notes file before creating any tag or Release. That historical
publisher remains pinned to v0.8.0 and does not publish v0.9.1. A future
publisher or authorized manual publication must validate its exact final body
before creation. GitHub UI/API publication outside this workflow cannot be
intercepted by a post-publication release event; the delivery guard still
rejects invalid copy before any Telegram request.

`telegram-release.yml` handles published final numeric releases only. Drafts,
prereleases and RC tags are skipped. Missing secrets fail delivery.
Adding secrets later does not post past releases.

The plain-text message contains the title, curated RU/EN copy and exactly one
URL: the GitHub Release. No direct APK URL or full-changelog fallback.

Only the first run attempt may send. Reruns always skip delivery, including
after a timeout: Telegram sendMessage has no idempotency key, so delivery
cannot safely be retried automatically. Inspect the channel and send manually
if needed. Republishing a deleted/recreated release is a new event and must
be treated as a new announcement.

Releases created using a workflow's GITHUB_TOKEN do not trigger release-event
delivery. Such publishers must explicitly call the reusable Telegram workflow
once after public artifact verification. The historical v0.8.0 caller is
excluded from release-event delivery to avoid duplicates. Never enable both
delivery paths for the same release.

Candidate preparation creates no tag, Release or Telegram post. v0.9.1
publication requires separate approval after the physical UI check.
