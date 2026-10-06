import subprocess,json
from pathlib import Path
base='fa8de584b8834ef9021cd4780842c86e7d3955f7'
path='app/src/main/java/com/danila/hacustomwidgets/dashboard/DashboardWidget.kt'
changed=subprocess.check_output(['git','diff','--name-only',base,'HEAD','--','app/src/main']).decode().splitlines()
assert changed==[path],changed
before=subprocess.check_output(['git','show',base+':'+path]).decode()
expected=before.replace('import androidx.glance.Visibility\n','').replace('import androidx.glance.visibility\n','')
expected=expected.replace('if (state != null) {\n            // Fits','if (state != null && state.config.showMaintenance) {\n            // Fits')
expected=expected.replace('\n                .visibility(if (state.config.showMaintenance) Visibility.Visible else Visibility.Gone)','')
start=expected.index('        Box(\n',expected.index('private fun DashboardTabs('));end=expected.index('        Box(\n',start+1)
block=expected[start:end].replace('.visibility(if (state.config.showFavorites) Visibility.Visible else Visibility.Gone).clickable(','.clickable(')
expected=expected[:start]+'        if (state.config.showFavorites) {\n'+''.join('    '+line if line.strip() else line for line in block.splitlines(True))+'        }\n'+expected[end:]
assert Path(path).read_text()==expected,'Production changes beyond conditional slots'
meta=Path('app/build.gradle.kts').read_text()
assert 'versionCode = 102' in meta and 'versionName = "0.8.0-rc4"' in meta
assert 'minSdk = 31' in meta and 'targetSdk = 36' in meta
audit=json.loads(Path('V080_RC4_VERSION_AUDIT.json').read_text())
assert audit['maximum']==101
print('RC4: only conditional wrench/star composition; RC3 viewport and all other production code byte-identical')
