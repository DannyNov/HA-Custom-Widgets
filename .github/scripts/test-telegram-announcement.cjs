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
