from pathlib import Path
import subprocess,re
base='99e97b987db3467ff036dafe74e605c661c33796'
prefix='app/src/main/java/com/danila/hacustomwidgets/dashboard/'
path=prefix+'DashboardWidget.kt'
prior=lambda p: subprocess.check_output(['git','show',f'{base}:{p}'])
old=prior(path).decode(); new=Path(path).read_text(encoding='utf-8')
start='            val timerConfig = card.autoOffTimer'; end='            if (card.visibleControls.size > 1'
assert old.split(start)[0]==new.split(start)[0]
assert old.split(end)[1:]==new.split(end)[1:]
callbacks=lambda s: sorted(re.findall(r'actionRunCallback<[^>]+>\(.*?\)\)',s,re.S))
assert callbacks(old)==callbacks(new), 'Callback payload changed'
power='                        if (!primary.brightnessCapable)'
assert old[old.index(power):]==new[new.index(power):], 'Power or later production changed'
for p in subprocess.check_output(['git','ls-tree','-r','--name-only',base,'app/src/main'],text=True).splitlines():
 if p not in [path,prefix+'TimerBlockLayoutPolicy.kt']:
  assert prior(p).decode().replace('\r\n','\n')==Path(p).read_text(encoding='utf-8') if Path(p).suffix in ['.kt','.xml'] else prior(p)==Path(p).read_bytes(),p
print('RC7_SCOPE_PASS: independent caption only; callbacks, Power, resources, backend and brightness unchanged')
