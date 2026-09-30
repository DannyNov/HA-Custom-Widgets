"""Fail closed outside the two approved UX changes and their tests/release gate."""
from pathlib import Path
import subprocess, re, json
git=lambda *a: subprocess.check_output(['git',*a],text=True)
base='c26bb1af90872db9a6bfb49ede693294d6a2fd51'
prefix='app/src/main/java/com/danila/hacustomwidgets/dashboard/'
allowed={prefix+x for x in ['DashboardModels.kt','DashboardStatePolicy.kt','DashboardEventPolicy.kt',
 'DashboardRepository.kt','DashboardWidgetConfigActivity.kt','DashboardWidget.kt','TimerRemainingLayout.kt']}
allowed.update({'app/build.gradle.kts','.github/workflows/android.yml','.github/workflows/compile-test-v035.yml',
 '.github/scripts/verify-v064-scope.py','V064_RC1_VERSION_AUDIT.json','RELEASE_NOTES_v0.6.4-rc1.md',
 'app/src/test/java/com/danila/hacustomwidgets/dashboard/DashboardFavoritesPolicyTest.kt',
 'app/src/androidTest/java/com/danila/hacustomwidgets/dashboard/DashboardFavoritesHostTest.kt',
 'app/src/androidTest/java/com/danila/hacustomwidgets/dashboard/TimerCardHostTest.kt',
 'app/src/androidTest/java/com/danila/hacustomwidgets/dashboard/DashboardUpgradeTest.kt'})
assert set(git('diff','--name-only',base,'HEAD').splitlines()) <= allowed
old=git('show',base+':app/build.gradle.kts');new=Path('app/build.gradle.kts').read_text()
assert new==old.replace('versionCode = 67','versionCode = 68').replace('versionName = "0.6.3"','versionName = "0.6.4-rc1"')
audit=json.loads(Path('V064_RC1_VERSION_AUDIT.json').read_text());assert audit['maximum']==67 and audit['newCode']==68
assert all(x['code']<68 for x in audit['refs'])
for ref in git('for-each-ref','--format=%(refname)','refs/tags').splitlines():
 code=int(re.search(r'versionCode\s*=\s*(\d+)',git('show',ref+':app/build.gradle.kts'))[1])
 assert code == 68 if ref == 'refs/tags/v0.6.4-rc1' else code < 68
old=git('show',base+':'+prefix+'DashboardWidget.kt');new=Path(prefix+'DashboardWidget.kt').read_text()
callbacks=lambda s: sorted(re.findall(r'actionRunCallback<[^>]+>\(.*?\)\)',s,re.S))
assert callbacks(old)==callbacks(new), 'Click payloads changed'
tail='            if (card.visibleControls.size > 1'
assert old.split(tail)[1:]==new.split(tail)[1:], 'Brightness or subsequent rendering changed'
for path in git('ls-tree','-r','--name-only',base).splitlines():
 if path not in allowed:
  assert subprocess.check_output(['git','show',base+':'+path]) == Path(path).read_bytes(),path
print('V064_SCOPE_PASS: two UX changes; callbacks, resources, brightness, realtime, connection, dependencies and historical tests preserved')
