import subprocess,re,xml.etree.ElementTree as ET
from pathlib import Path
base='b733daeae463bac15a909264f624efbae463af77'
changed=subprocess.check_output(['git','diff','--name-only',base,'HEAD','--','app/src/main']).decode().splitlines()
allowed={
 'app/src/main/java/com/danila/hacustomwidgets/dashboard/'+name+'.kt'
 for name in ['DashboardModels','DashboardWidget','MaintenanceContent','MaintenancePolicy']
}|{'app/src/main/res/drawable/ic_maintenance.xml','app/src/main/res/drawable/ic_maintenance_attention.xml'}
assert set(changed)==allowed,(changed,allowed)
def source(ref,path): return subprocess.check_output(['git','show',ref+':'+path]).decode()
path='app/src/main/java/com/danila/hacustomwidgets/dashboard/DashboardWidget.kt'
now=Path(path).read_text(encoding='utf-8')
old=source('v0.7.0',path)
def header(s): return s[s.index('fun DashboardHeader('):s.index('@Composable\nprivate fun DashboardTabs(')].strip()
actual=header(now)
start=actual.index('        if (state?.config?.showMaintenance == true)')
end=actual.index('        Text(',start)
assert actual[:start]+actual[end:]==header(old),'Original header geometry changed outside the key'
assert 'width(36.dp).height(20.dp)' in actual
assert 'width(20.dp).height(20.dp)' in actual
for method,next_method in [('isBattery','isRelevant'),('isRelevant','batteryAttention'),('batteryAttention','updateAttention'),('updateAttention','batteries')]:
    p='app/src/main/java/com/danila/hacustomwidgets/dashboard/MaintenancePolicy.kt'
    before=source(base,p); after=Path(p).read_text(encoding='utf-8')
    def body(s): return s[s.index('    fun '+method+'('):s.index('    fun '+next_method+'(')]
    assert body(before)==body(after),method+' changed'
ns='{http://schemas.android.com/apk/res/android}'
normal=ET.parse('app/src/main/res/drawable/ic_maintenance.xml').getroot()
attention=ET.parse('app/src/main/res/drawable/ic_maintenance_attention.xml').getroot()
assert normal.attrib==attention.attrib
assert normal[0].attrib==attention[0].attrib
assert normal[0].get(ns+'fillColor')=='#00000000'
assert normal[0].get(ns+'strokeWidth')=='1.6'
assert len(normal)==1 and len(attention)==2
print('RC2 scope: six production files; original header and attention rules preserved')
