"""Verify Final against the physical Honor-approved RC1 without production drift."""
from pathlib import Path
import subprocess, json

base = '66298240b6707c3e3d40e6d60ce079e2be664efe'
git = lambda *args: subprocess.check_output(['git', *args])
git('merge-base', '--is-ancestor', base, 'HEAD')
allowed = {
    'app/build.gradle.kts',
    'app/src/androidTest/java/com/danila/hacustomwidgets/dashboard/DashboardUpgradeTest.kt',
    '.github/workflows/android.yml', '.github/workflows/compile-test-v035.yml',
    '.github/workflows/telegram-release.yml', '.github/scripts/verify-v064-final-scope.py',
    'V064_FINAL_VERSION_AUDIT.json', 'RELEASE_NOTES_v0.6.4.md', 'docs/RELEASE_PROCESS.md',
}
changed = git('diff', '--name-only', base, 'HEAD').decode().splitlines()
assert set(changed) <= allowed, f'Unexpected files: {set(changed) - allowed}'
assert git('diff', base, 'HEAD', '--', 'app/src/main') == b'', 'Production sources/resources/manifest changed'
for path in git('ls-tree', '-r', '--name-only', base).decode().splitlines():
    if path not in allowed:
        assert git('show', f'{base}:{path}') == Path(path).read_bytes(), path
old = git('show', f'{base}:app/build.gradle.kts').decode()
new = Path('app/build.gradle.kts').read_text(encoding='utf-8')
assert new == old.replace('versionCode = 68', 'versionCode = 69').replace('versionName = "0.6.4-rc1"', 'versionName = "0.6.4"')
assert git('diff', base, 'HEAD', '--', 'build.gradle.kts', 'settings.gradle.kts', 'gradle', 'gradle.properties') == b''
audit = json.loads(Path('V064_FINAL_VERSION_AUDIT.json').read_text())
assert audit['maximum'] == 68 and audit['newCode'] == 69
assert max(x['code'] for x in audit['refs']) == 68
assert all(x['code'] < 69 for x in audit['refs'])
print('V064_FINAL_SCOPE_PASS: physical RC1 production sources, resources, manifest, dependencies and build behavior identical; only versionName/versionCode changed')
print('Changed files:', ', '.join(changed))
