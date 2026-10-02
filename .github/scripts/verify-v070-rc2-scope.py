"""RC2 is a corrective visual release, preserving the RC1 behavioral surface."""
from pathlib import Path
import json, subprocess
base = 'afece5e292314a145d4822d7702b992f0f05be1f'
git = lambda *args: subprocess.check_output(['git', *args])
git('merge-base', '--is-ancestor', base, 'HEAD')
allowed = {
    'app/src/main/java/com/danila/hacustomwidgets/dashboard/BrightnessActivity.kt',
    'app/src/main/java/com/danila/hacustomwidgets/dashboard/LightColorControls.kt',
    'app/src/main/java/com/danila/hacustomwidgets/dashboard/LightCapsuleContours.kt',
    'app/src/main/java/com/danila/hacustomwidgets/dashboard/LightControlStyle.kt',
}
for path in git('ls-tree', '-r', '--name-only', base).decode().splitlines():
    if path.startswith(('app/src/test/', 'app/src/androidTest/', '.github/scripts/')) or (
        path.startswith('app/src/main/') and path not in allowed
    ) or path in ('build.gradle.kts','settings.gradle.kts','gradle.properties','.github/workflows/telegram-release.yml','V070_VERSION_AUDIT.json'):
        assert git('show', f'{base}:{path}') == Path(path).read_bytes(), f'RC1 protected file changed: {path}'
audit = json.loads(Path('V070_RC2_VERSION_AUDIT.json').read_text())
prior = json.loads(Path('V070_VERSION_AUDIT.json').read_text())
assert set(audit['ciSHAs']) <= {r['sha'] for r in prior['ci'] + audit['newlyChecked']}
assert audit['maximum'] == max(r['versionCode'] for r in prior['ci'] + prior['refs'] + audit['newlyChecked'])
assert audit['newCode'] > audit['maximum']
old = git('show', f'{base}:app/build.gradle.kts').decode()
assert Path('app/build.gradle.kts').read_text() == old.replace('versionCode = 79', f"versionCode = {audit['newCode']}").replace('versionName = "0.7.0-rc1"', 'versionName = "0.7.0-rc2"')
workflow = Path('.github/workflows/compile-test-v035.yml').read_text()
assert 'v0.7.0-rc1-api36' in workflow and 'ref: '+base in workflow
assert 'publish-final:' not in workflow
print('RC2_SCOPE_PASS: protected RC1 behavior/tests/build identity unchanged; visual allowlist; audited increasing versionCode; RC1 upgrade on both APIs')
