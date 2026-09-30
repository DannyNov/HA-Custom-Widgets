"""Compare RC4 with physically accepted RC3, including exact callback payloads."""
from pathlib import Path
import re
import subprocess

base = '184d71d8003000f933330330628e41f3ddf20b47'
prefix = 'app/src/main/java/com/danila/hacustomwidgets/dashboard/'
def prior(path):
    return subprocess.check_output(['git', 'show', f'{base}:{path}'], text=True)

for name in ['BrightnessActivity.kt', 'BrightnessControls.kt', 'BrightnessCoordinator.kt',
             'DashboardActions.kt', 'DashboardModels.kt', 'DashboardRepository.kt']:
    path = prefix + name
    assert Path(path).read_text(encoding='utf-8') == prior(path), f'Accepted behavior changed: {path}'
for path in ['app/src/main/AndroidManifest.xml', 'app/src/main/res/values/themes.xml',
             'app/src/main/res/drawable/brightness_capsule.xml',
             'app/src/main/res/drawable/circle_timer_active.xml',
             'app/src/main/res/drawable/circle_accent.xml']:
    assert Path(path).read_text(encoding='utf-8') == prior(path), f'Accepted resources changed: {path}'

path = prefix + 'DashboardWidget.kt'
old, new = prior(path), Path(path).read_text(encoding='utf-8')
# Reordering must not change callback class, widget/device/entity/domain parameters.
callbacks = lambda text: sorted(re.findall(r'actionRunCallback<[^>]+>\(.*?\)\)', text, re.S))
assert callbacks(old) == callbacks(new), 'Click routing changed'
start = new.index('val timerConfig = card.autoOffTimer')
end = new.index('if (card.visibleControls.size > 1', start)
timer = new[start:end]
assert timer.index('actionRunCallback<DashboardTimerAction>') < timer.index('PrimaryPowerButton(')
assert 'GlanceModifier.width(PrimaryPowerButtonPolicy.TOUCH_SIZE_DP.dp)' in timer
assert 'ColorProvider(R.color.widget_accent)' in timer
assert 'ColorProvider(R.color.widget_light_on)' in timer
for marker, next_marker in [('@Composable\nprivate fun PrimaryPowerButton(', '@Composable\nprivate fun ScenarioRunButton(')]:
    assert old[old.index(marker):old.index(next_marker)] == new[new.index(marker):new.index(next_marker)]
# Stable item IDs and the outer scroll/realtime composition are byte-for-byte preserved.
assert old[:old.index('internal fun DashboardDeviceCard')] == new[:new.index('internal fun DashboardDeviceCard')]
print('RC4_SCOPE_PASS: accepted RC3 behavior, resources, click payloads and stable IDs unchanged')
