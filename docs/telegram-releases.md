# Telegram release announcements

Add repository Actions secrets `TELEGRAM_BOT_TOKEN` (from BotFather) and
`TELEGRAM_CHAT_ID` (`@HACustomWidgets`). Make the bot an administrator of that
channel with permission to post messages. Do not add a second destination for
the linked Community group.

`telegram-release.yml` handles published final releases only. Drafts,
prereleases and non-numeric/RC tags are skipped. Missing secrets skip delivery
without placeholders. Adding secrets later does not post past releases.

The message uses plain text, a shortened changelog, the release URL and an APK
asset URL when available. Upload assets before publishing the release.

Only the first run attempt may send. Reruns always skip delivery, including
after a timeout: Telegram sendMessage has no idempotency key, so delivery
cannot safely be retried automatically. Inspect the channel and send manually
if needed. Republishing a deleted/recreated release is a new event and must
be treated as a new announcement.

Publish releases through the GitHub UI or a user-authorized CLI/API token.
Releases created using a workflow's GITHUB_TOKEN do not trigger this workflow.
