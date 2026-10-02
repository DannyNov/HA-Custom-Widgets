"""Preserve stable regressions, historical gates and Android/signing identity for RC."""
from pathlib import Path
import json, subprocess
base = '273a6d60360b295523f23bd4b416a34ff4cb2d2b'
git = lambda *args: subprocess.check_output(['git', *args])
git('merge-base', '--is-ancestor', base, 'HEAD')
for prefix in ('app/src/test/', 'app/src/androidTest/', '.github/scripts/'):
    for path in git('ls-tree', '-r', '--name-only', base, prefix).decode().splitlines():
        assert git('show', f'{base}:{path}') == Path(path).read_bytes(), f'Historical test/gate changed: {path}'
for path in ('app/src/main/AndroidManifest.xml','build.gradle.kts','settings.gradle.kts','gradle.properties',
             '.github/workflows/telegram-release.yml'):
    assert git('show', f'{base}:{path}') == Path(path).read_bytes(), path
old = git('show', f'{base}:app/build.gradle.kts').decode()
new = Path('app/build.gradle.kts').read_text()
audit = json.loads(Path('V070_VERSION_AUDIT.json').read_text())
code = audit['newCode']
assert new == old.replace('versionCode = 69', f'versionCode = {code}').replace('versionName = "0.6.4"', 'versionName = "0.7.0-rc1"')
assert max(row['versionCode'] for row in audit['ci'] + audit['refs']) == audit['maximum']
assert code > audit['maximum']
workflow = Path('.github/workflows/compile-test-v035.yml').read_text()
assert 'publish-final:' not in workflow and 'needs: publish-final' not in workflow
assert 'v0.6.4-api36' in workflow and 'ref: '+base in workflow
print('V070_SCOPE_PASS: historical tests/gates, dependency/build identity, manifest and Telegram unchanged; strictly greater versionCode')
