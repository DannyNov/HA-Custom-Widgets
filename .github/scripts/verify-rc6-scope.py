"""RC6 changes horizontal placement only; all accepted RC5 behavior is retained."""
from pathlib import Path
import subprocess
base='ce38f2a889410b50be130644bb4fe99edb93e24d'
prefix='app/src/main/java/com/danila/hacustomwidgets/dashboard/'
prior=lambda p: subprocess.check_output(['git','show',f'{base}:{p}'])
path=prefix+'DashboardWidget.kt'
old=prior(path).decode('utf-8')
expected=old.replace('''                        Box(modifier = GlanceModifier.defaultWeight().padding(end = 4.dp),
                            contentAlignment = Alignment.TopCenter) {''','''                        Row(modifier = GlanceModifier.defaultWeight().padding(end = 4.dp),
                            verticalAlignment = Alignment.Top) {
                        Spacer(GlanceModifier.width(timerLayout.leadingSpace.dp))''')
assert expected != old
assert subprocess.check_output(['git','show','99e97b987db3467ff036dafe74e605c661c33796:'+path]).decode('utf-8') == expected, 'Change outside horizontal anchor'
for p in subprocess.check_output(['git','ls-tree','-r','--name-only',base,'app/src/main'],text=True).splitlines():
    if p not in [path,prefix+'TimerBlockLayoutPolicy.kt']:
        if Path(p).suffix in ['.kt','.xml']:
            assert prior(p).decode('utf-8').replace('\r\n','\n')==Path(p).read_text(encoding='utf-8'),p
        else:
            assert prior(p)==Path(p).read_bytes(),p
print('RC6_SCOPE_PASS: RC5 inner composition, callbacks, Power, colors, backend and brightness preserved')
