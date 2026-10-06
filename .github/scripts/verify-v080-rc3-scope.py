import subprocess,re,json
from pathlib import Path
base='703ac9e06273b7a192084b2b6e07204aae3ba004'
root='app/src/main/java/com/danila/hacustomwidgets/dashboard/'
changed=subprocess.check_output(['git','diff','--name-only',base,'HEAD','--','app/src/main']).decode().splitlines()
assert set(changed)=={root+n+'.kt' for n in ('DashboardWidget','DashboardWidgetConfigActivity','MaintenanceContent','DashboardCollectionsRenderer','GlanceCollectionComposer','DashboardWidgetUpdater')} | {
    'app/src/main/res/layout/dashboard_collection_shell.xml','app/src/main/res/layout/dashboard_collection_list.xml','app/src/main/res/layout/dashboard_collection_root.xml'},changed
def before(path): return subprocess.check_output(['git','show',base+':'+path]).decode()
def after(path): return Path(path).read_text(encoding='utf-8')
def header(s): return s[s.index('fun DashboardHeader('):s.index('@Composable\nprivate fun DashboardTabs(')].strip()
h=header(after(root+'DashboardWidget.kt'))
h=h.replace('if (state != null) {','if (state?.config?.showMaintenance == true) {')
h=h.replace('GlanceModifier.width(36.dp).height(20.dp)\n                .visibility(if (state.config.showMaintenance) Visibility.Visible else Visibility.Gone)\n                .clickable(', 'GlanceModifier.width(36.dp).height(20.dp).clickable(')
assert h==header(before(root+'DashboardWidget.kt')), 'Header changed beyond keeping the hidden key mounted'
path=root+'MaintenanceContent.kt'
def without_ids(s): return re.sub(r'item\(itemId = DashboardStatePolicy.stableCollectionId\("[^"\n]*"\)\)', 'item', s)
assert without_ids(before(path))==without_ids(after(path)), 'Maintenance changed beyond stable collection IDs'
s=after(root+'DashboardWidgetConfigActivity.kt')
assert '★' not in s and 'Избран' not in s and 'Show Favorites' not in s
for token in ('MainTabSettingsText.title','MainTabSettingsText.configure','MainTabSettingsText.select','MainTabVisibilitySetting(showFavorites, onShowFavorites)'):
    assert token in s,token
meta=Path('app/build.gradle.kts').read_text()
assert 'versionCode = 100' in meta and 'versionName = "0.8.0-rc3"' in meta
assert 'minSdk = 31' in meta and 'targetSdk = 36' in meta and 'androidx.glance:glance-appwidget:1.1.1' in meta
audit=json.loads(Path('V080_RC3_VERSION_AUDIT.json').read_text())
assert audit['maximum']==99 and audit['ciRuns']==515
assert audit['missing']==['8ac8590fdd4c4df7a9d3bb5b31adc090f6860b2c']
renderer=after(root+'DashboardCollectionsRenderer.kt')
assert 'if (id == selected)' in renderer and renderer.count('setRemoteAdapter(')==1
assert 'outer RemoteViews delivered to AppWidgetHostView' in renderer
assert 'DashboardWidget().update(context, appWidgetId)' in after(root+'DashboardWidgetUpdater.kt')
assert 'addStableView' in renderer and '0x00e00000' in renderer
assert 'setSelection' not in renderer and 'setScrollPosition' not in renderer
assert 'reflection' in after(root+'GlanceCollectionComposer.kt')
print('RC3 scope: Main terminology; persistent native collections with Glance 1.1.1 rows and active adapter updates; unchanged header geometry and Maintenance behavior')
