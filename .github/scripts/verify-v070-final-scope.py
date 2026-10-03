from pathlib import Path
import subprocess,re,json
base='248c91175b473a5b8be11d0fcdf381f23f67c884'
git=lambda *a:subprocess.check_output(['git','-c','safe.directory=*',*a])
paths=git('ls-tree','-r','--name-only',base).decode().splitlines()
for p in paths:
 if p.startswith(('.github/','docs/')) or p.endswith('.md') or p=='app/build.gradle.kts':continue
 assert git('show',base+':'+p)==Path(p).read_bytes(),p
for p in paths:
 if p.startswith('app/src/main/') or p.startswith('app/src/test/') or p.startswith('app/src/androidTest/'):
  assert git('show',base+':'+p)==Path(p).read_bytes(),p
expected=git('show',base+':app/build.gradle.kts').replace(b'versionCode = 87',b'versionCode = 88').replace(b'0.7.0-rc4',b'0.7.0')
assert Path('app/build.gradle.kts').read_bytes()==expected,'build/dependency invariant'
a=json.loads(Path('V070_FINAL_VERSION_AUDIT.json').read_text())
assert a['maximum']==87 and not a['missing']
assert 88>a['maximum']
w=Path('.github/workflows/compile-test-v035.yml').read_text()
assert len(re.findall(r'^          - source:',w,re.M))==21
for t in ['v0.6.4','v0.6.4-api36','v0.7.0-rc4','v0.7.0-rc4-api36']:assert '- source: '+t+'\n' in w
for tag,sha in [('v0.7.0-rc1','afece5e292314a145d4822d7702b992f0f05be1f'),('v0.7.0-rc2','00fdbf0ab983db90d2ad8f6ff25961fb3e6652dc'),('v0.7.0-rc3','42ef1127af68f1b8d475132e92004bb7a0588bca'),('v0.7.0-rc4',base)]:assert git('rev-parse',tag).decode().strip()==sha
print('V070_FINAL_PRODUCTION_INVARIANT_PASS: only versionName 0.7.0 and versionCode 88; unchanged production, resources, dependencies and all existing tests; 21 mandatory upgrades')
