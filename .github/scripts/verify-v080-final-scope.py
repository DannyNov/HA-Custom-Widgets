from pathlib import Path
import subprocess,re,json
base='d963e85424e55fc29924f26c9f3352c9d20a54a5'
git=lambda *args:subprocess.check_output(['git',*args])
baseline={p:git('show',base+':'+p) for p in git('ls-tree','-r','--name-only',base).decode().splitlines() if p.startswith('app/')}
current={p for p in git('ls-files','app').decode().splitlines()}
assert current==set(baseline),'Application file inventory changed'
for p,content in baseline.items():
 if p=='app/build.gradle.kts':content=content.replace(b'versionCode = 106',b'versionCode = 107').replace(b'0.8.0-rc4',b'0.8.0')
 assert Path(p).read_bytes()==content,p
for p in ['build.gradle.kts','settings.gradle.kts','gradle.properties']:
 assert git('show',base+':'+p)==Path(p).read_bytes(),p
assert not git('diff',base,'HEAD','--','gradle').strip(),'Gradle dependencies changed'
a=json.loads(Path('V080_FINAL_VERSION_AUDIT.json').read_text())
assert a['maximum']==106 and not a['missing'] and 107>a['maximum']
w=Path('.github/workflows/compile-test-v035.yml').read_text()
assert len(re.findall(r'^          - source:',w,re.M))==31
for t in ['v0.7.0','v0.7.0-rc4','v0.8.0-rc1','v0.8.0-rc2','v0.8.0-rc3','v0.8.0-rc4']:
 for suffix in ['', '-api36']:assert '- source: '+t+suffix+'\n' in w
print('V080_FINAL_PRODUCTION_INVARIANT_PASS: complete app inventory and bytes unchanged except versionName 0.8.0 / versionCode 107; production/dependencies/tests preserved; 31 upgrades')
