"""Fail closed if Final differs from physically approved RC8 outside release metadata/docs/CI."""
from pathlib import Path
import subprocess

base = 'a588b78539144ed0c3dfac3493f46cdf544d3824'
git = lambda *args: subprocess.check_output(['git', *args])
git('merge-base', '--is-ancestor', base, 'HEAD')
allowed = {'README.md', 'README.ru.md', 'RELEASE_NOTES_v0.6.3.md',
           'V063_FINAL_VERSION_AUDIT.json', 'docs/images/screenshots/multiple-dashboard-widgets.jpg',
           '.github/workflows/android.yml', '.github/workflows/compile-test-v035.yml',
           '.github/scripts/verify-final-scope.py', '.github/workflows/telegram-release.yml', 'app/build.gradle.kts'}
changed = git('diff', '--name-only', base, 'HEAD').decode().splitlines()
assert set(changed) <= allowed, f'Unexpected changes: {set(changed) - allowed}'
for path in git('ls-tree', '-r', '--name-only', base, 'app/src/main').decode().splitlines():
    assert git('show', f'{base}:{path}') == Path(path).read_bytes(), path
old = git('show', f'{base}:app/build.gradle.kts').decode()
new = Path('app/build.gradle.kts').read_text(encoding='utf-8')
assert new == old.replace('versionCode = 66', 'versionCode = 67').replace('versionName = "0.6.3-rc8"', 'versionName = "0.6.3"')
assert git('diff', base, 'HEAD', '--', 'build.gradle.kts', 'settings.gradle.kts', 'gradle', 'gradle.properties') == b''
print('FINAL_SCOPE_PASS: RC8 production code/resources/manifest/dependencies unchanged; only version metadata, docs/media and CI changed')
print('Changed files:', ', '.join(changed))
