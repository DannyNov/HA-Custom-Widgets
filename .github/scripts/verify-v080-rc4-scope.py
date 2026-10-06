import subprocess,json,hashlib
from pathlib import Path
base='fa8de584b8834ef9021cd4780842c86e7d3955f7'
expected={'app/src/main/java/com/danila/hacustomwidgets/dashboard/DashboardWidget.kt': '3df79028661c81f3d7a7b487c0854eb8c1905299e4014f03fe535224c75ee74e', 'app/src/main/java/com/danila/hacustomwidgets/dashboard/DashboardCollectionsRenderer.kt': '2e4e77fd2eea5c75d8a80053ec03c82b9b89ba216ba5cc7e1f995021e5341e36'}
changed=subprocess.check_output(['git','diff','--name-only',base,'HEAD','--','app/src/main']).decode().splitlines()
assert set(changed)==set(expected),changed
for path,digest in expected.items():
    assert hashlib.sha256(Path(path).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==digest,path
meta=Path('app/build.gradle.kts').read_text()
assert 'versionCode = 103' in meta and 'versionName = "0.8.0-rc4"' in meta
assert 'minSdk = 31' in meta and 'targetSdk = 36' in meta
audit=json.loads(Path('V080_RC4_VERSION_AUDIT.json').read_text())
assert audit['maximum']==102
print('RC4: conditional slots and isolated chrome; native collection algorithm, IDs, adapters and composer unchanged')
