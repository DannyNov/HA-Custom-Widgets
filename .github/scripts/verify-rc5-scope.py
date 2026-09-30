"""Only the Timer renderer region may change from RC4 in existing production files."""
from pathlib import Path
import subprocess, re
base='ba2bb30e8ab3ff79888f7766f0b5070eca1e80a6'
prior=lambda p: subprocess.check_output(['git','show',f'{base}:{p}'],text=True)
path='app/src/main/java/com/danila/hacustomwidgets/dashboard/DashboardWidget.kt'
old,new=prior(path),Path(path).read_text(encoding='utf-8')
start='            val timerConfig = card.autoOffTimer'
end='            if (card.visibleControls.size > 1'
assert old.split(start)[0]==new.split(start)[0]
assert old.split(end)[1:]==new.split(end)[1:]
callbacks=lambda s: sorted(re.findall(r'actionRunCallback<[^>]+>\(.*?\)\)',s,re.S))
assert callbacks(old)==callbacks(new), 'Timer/Power callback payload changed'
power=lambda s: s[s.index('                        if (!primary.brightnessCapable)',s.index(start)):s.index('                    }\n',s.index('                        if (!primary.brightnessCapable)',s.index(start)))]
assert power(old)==power(new), 'Power column changed'
for p in subprocess.check_output(['git','ls-tree','-r','--name-only',base,'app/src/main'],text=True).splitlines():
    if p!=path: assert prior(p)==Path(p).read_text(encoding='utf-8') if Path(p).suffix in ['.kt','.xml'] else subprocess.check_output(['git','show',f'{base}:{p}'])==Path(p).read_bytes(),p
print('RC5_SCOPE_PASS: only Timer composition changed; callbacks, Power, colors and other production code preserved')
