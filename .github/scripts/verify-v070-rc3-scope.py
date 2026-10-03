"""RC3 permits only the agreed contour orientation/help copy and release evidence."""
from pathlib import Path
import json, subprocess
base='00fdbf0ab983db90d2ad8f6ff25961fb3e6652dc'
git=lambda *args: subprocess.check_output(['git',*args])
git('merge-base','--is-ancestor',base,'HEAD')
allowed={
 'app/src/main/java/com/danila/hacustomwidgets/dashboard/LightCapsuleContours.kt',
 'app/src/main/java/com/danila/hacustomwidgets/dashboard/LightColorControls.kt',
 'app/src/androidTest/java/com/danila/hacustomwidgets/dashboard/LightVisualRc2HostTest.kt',
 'app/src/androidTest/java/com/danila/hacustomwidgets/dashboard/LightColorHostTest.kt',
 'app/build.gradle.kts','.github/workflows/android.yml','.github/workflows/compile-test-v035.yml','.github/workflows/publish-rc.yml'
}
for path in git('ls-tree','-r','--name-only',base).decode().splitlines():
 if path not in allowed:
  assert git('show',f'{base}:{path}')==Path(path).read_bytes(),f'RC2 protected file changed: {path}'
old=git('show',f'{base}:app/src/main/java/com/danila/hacustomwidgets/dashboard/LightColorControls.kt').decode()
expected=old.replace('Restore lamp color. Hold to choose another color.','Turn on lamp color. Hold to choose another color.').replace('Вернуть цвет лампы. Удерживайте для выбора другого цвета.','Включить цвет лампы. Удерживайте для выбора другого цвета.').replace('Tap to restore • Hold to choose','Tap — turn on color • Hold — choose').replace('Нажать — вернуть • Удерживать — выбрать','Нажать — включить цвет • Удерживать — выбрать')
assert Path('app/src/main/java/com/danila/hacustomwidgets/dashboard/LightColorControls.kt').read_text(encoding='utf-8')==expected
audit=json.loads(Path('V070_RC3_VERSION_AUDIT.json').read_text(encoding='utf-8'))
prior=json.loads(Path('V070_RC2_VERSION_AUDIT.json').read_text(encoding='utf-8'))
original=json.loads(Path('V070_VERSION_AUDIT.json').read_text(encoding='utf-8'))
rows=original['ci']+original['refs']+prior['newlyChecked']+audit['newlyChecked']
assert set(audit['ciSHAs']) <= {r['sha'] for r in rows if 'sha' in r}
assert audit['maximum']==max(r['versionCode'] for r in rows)
assert audit['newCode']>audit['maximum']
old=git('show',f'{base}:app/build.gradle.kts').decode()
assert Path('app/build.gradle.kts').read_text(encoding='utf-8')==old.replace('versionCode = 83',f"versionCode = {audit['newCode']}").replace('versionName = "0.7.0-rc2"','versionName = "0.7.0-rc3"')
workflow=Path('.github/workflows/compile-test-v035.yml').read_text(encoding='utf-8')
assert 'v0.7.0-rc2-api36' in workflow and 'ref: '+base in workflow
assert 'publish-final:' not in workflow
print('RC3_SCOPE_PASS: RC2 behavior, geometry, palette, historical tests and signing identity protected; copy-only controls; increasing audited version; RC2 upgrades both APIs')
