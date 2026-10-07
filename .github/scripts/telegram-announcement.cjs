const fs = require('node:fs');

function announcement(source) {
  const start = '<!-- telegram:start -->', end = '<!-- telegram:end -->';
  if (source.split(start).length !== 2 || source.split(end).length !== 2 || source.indexOf(end) < source.indexOf(start)) {
    throw new Error('Final release requires exactly one ordered Telegram block.');
  }
  const body = source.slice(source.indexOf(start) + start.length, source.indexOf(end)).trim();
  const diagnostics = /build identity|package\s*:|versioncode|minSdk|targetSdk|certificate|\bSHA\b|SHA[-\s]?256|\bCI\b|(?:unit|regression|host)\s+tests?|test\s+counts?/i;
  if (body.length > 1600 || diagnostics.test(body) || /https?:\/\/|www\.|<!--|-->/.test(body)) {
    throw new Error('Telegram block exceeds 1600 characters or contains diagnostics, URLs or markers.');
  }
  const match = /^RU:\s*([\s\S]+?)\s+EN:\s*([\s\S]+)$/.exec(body);
  if (!match || match[1].trim().length < 100 || match[2].trim().length < 100 || /Новая версия опубликована|A new release is available/i.test(body)) {
    throw new Error('Telegram block requires substantive RU and EN copy (at least 100 characters each), without placeholder fallback.');
  }
  return body;
}
module.exports = { announcement };
if (require.main === module) {
  announcement(fs.readFileSync(process.argv[2], 'utf8'));
  console.log('TELEGRAM_BLOCK_PASS');
}
