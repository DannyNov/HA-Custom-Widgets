const { test } = require('node:test');
const assert = require('node:assert/strict');
const { announcement } = require('./telegram-announcement.cjs');
const start = '<!-- telegram:start -->', end = '<!-- telegram:end -->';
const copy = 'RU: ' + 'Полезные изменения подключения. '.repeat(5) + '\n\nEN: ' + 'Useful connection improvements. '.repeat(5);
const valid = `${start}\n${copy}\n${end}`;
test('valid curated RU/EN block is preserved', () => assert.equal(announcement(valid), copy.trim()));
for (const [name, value] of Object.entries({
  absent: copy, empty: start + end, reversed: end + copy + start,
  duplicateStart: start + valid, duplicateEnd: valid + end,
  englishOnly: start + 'EN: ' + 'Changes '.repeat(50) + end,
  short: start + 'RU: Новая версия опубликована.\nEN: A new release is available.' + end,
  oversized: start + copy.repeat(6) + end,
  diagnostic: valid.replace('Useful', 'versionCode: 109 Useful'),
  sha: valid.replace('Useful', 'SHA: deadbeef Useful'),
  url: valid.replace('Useful', 'https://example.com Useful'),
})) test(`reject ${name}`, () => assert.throws(() => announcement(value)));
test('v0.9.1 prepared Release body passes', () => {
  const fs = require('node:fs');
  assert.ok(announcement(fs.readFileSync('RELEASE_NOTES_v0.9.1.md', 'utf8')));
});

// Execute the actual workflow script with isolated fake APIs: invalid copy
// must fail before even getChat, and valid copy must send exactly once.
async function delivery(body) {
  const fs = require('node:fs');
  const workflow = fs.readFileSync('.github/workflows/telegram-release.yml', 'utf8').replace(/\r\n/g, '\n');
  const script = workflow.split('          script: |\n')[1].split('\n').map(line => line.slice(12)).join('\n');
  const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
  const calls = [], errors = [];
  const summary = { addRaw() { return this; }, async write() {} };
  const release = { tag_name: 'v0.9.1', name: 'HA Custom Widgets v0.9.1', draft: false,
    prerelease: false, body, html_url: 'https://github.com/DannyNov/HA-Custom-Widgets/releases/tag/v0.9.1' };
  await new AsyncFunction('context', 'core', 'github', 'process', 'require', 'fetch', script)(
    { eventName: 'release', payload: { release } },
    { setFailed: message => errors.push(message), notice() {}, info() {}, summary }, {},
    { env: { GITHUB_WORKSPACE: process.cwd(), TELEGRAM_BOT_TOKEN: 'test-only', TELEGRAM_CHAT_ID: 'test-only' } },
    require, async (url, options) => {
      const method = url.split('/').at(-1);
      calls.push({ method, payload: JSON.parse(options.body) });
      return { ok: true, async json() { return { ok: true, result: method === 'getChat'
        ? { id: 1, type: 'channel', username: 'HACustomWidgets' } : { message_id: 2 } }; } };
    });
  return { calls, errors };
}
test('workflow rejects invalid final copy before any Telegram API call', async () => {
  for (const body of ['', start + end, valid + end, valid.replace('Useful', 'certificate: Useful')]) {
    const result = await delivery(body);
    assert.equal(result.errors.length, 1);
    assert.deepEqual(result.calls, []);
  }
});
test('workflow sends curated copy with only the Release URL', async () => {
  const result = await delivery(valid);
  assert.deepEqual(result.errors, []);
  assert.deepEqual(result.calls.map(x => x.method), ['getChat', 'sendMessage']);
  const text = result.calls[1].payload.text;
  assert.ok(text.includes(copy.trim()));
  assert.equal((text.match(/https:\/\//g) || []).length, 1);
  assert.ok(text.endsWith('/releases/tag/v0.9.1'));
});
test('publication validator precedes gh release create', () => {
  const fs = require('node:fs');
  const workflow = fs.readFileSync('.github/workflows/publish-final.yml', 'utf8');
  assert.ok(workflow.indexOf('node .github/scripts/telegram-announcement.cjs') < workflow.indexOf('gh release create'));
});
