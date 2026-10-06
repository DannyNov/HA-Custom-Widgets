import subprocess,json,hashlib
from pathlib import Path
base='fa8de584b8834ef9021cd4780842c86e7d3955f7'
expected={'app/src/main/java/com/danila/hacustomwidgets/dashboard/DashboardWidget.kt': 'a3c1b18dc9b5d666873efcce614cd6e32feafb23c64536a3e363522a76223dd5', 'app/src/main/java/com/danila/hacustomwidgets/dashboard/DashboardCollectionsRenderer.kt': '3d32d7d9658b267c42d03eac5145fe854ff046e609064287ee462fe6c577dd3a', 'app/src/main/res/layout/dashboard_collection_root.xml': 'b22a6e5d8f6d5b4a940e7fb5a2b69a1c03e4d32f7005980891c0a70d8a506638', 'app/src/main/res/drawable/dashboard_widget_background.xml': 'b8ab145a3aecce0bd102e3ecd16caf25a394d156ba42e928422199a3fc88477a'}
changed=subprocess.check_output(['git','diff','--name-only',base,'HEAD','--','app/src/main']).decode().splitlines()
assert set(changed)==set(expected),changed
for path,digest in expected.items():
    assert hashlib.sha256(Path(path).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==digest,path
meta=Path('app/build.gradle.kts').read_text()
assert 'versionCode = 106' in meta and 'versionName = "0.8.0-rc4"' in meta
assert 'minSdk = 31' in meta and 'targetSdk = 36' in meta
audit=json.loads(Path('V080_RC4_VERSION_AUDIT.json').read_text())
assert audit['maximum']==105
print('RC4: conditional slots and isolated chrome; native collection algorithm, IDs, adapters and composer unchanged')
