#!/usr/bin/env bash
set -euo pipefail
mkdir -p fixture-evidence
pkg=com.danila.hacustomwidgets
runner=$pkg.test/androidx.test.runner.AndroidJUnitRunner
test_class=$pkg.dashboard.DashboardUpgradeTest
original_failure=0
for variant in original corrected; do
  adb install -r "$RUNNER_TEMP/$variant-test.apk"
  for attempt in $(seq 1 20); do
    adb shell pm clear "$pkg" | grep -F Success
    adb shell appwidget grantbind --package "$pkg" --user 0
    adb shell am instrument -w -e class "$test_class" -e upgradePhase seed -e withLegacy true "$runner" > "fixture-evidence/$variant-$attempt-seed.txt"
    grep -F 'OK (1 test)' "fixture-evidence/$variant-$attempt-seed.txt"
    # Only the unchanged stable APK has ever been installed at this point.
    if ! (adb shell run-as "$pkg" cat shared_prefs/ha_connection.xml 2>/dev/null || true) | python3 -c '
import sys, xml.etree.ElementTree as ET
try:
    keys = {node.attrib.get("name") for node in ET.fromstring(sys.stdin.read())}
except (ET.ParseError, OSError):
    keys = set()
missing = {"base_url", "access_token", "token_iv"} - keys
print("PERSISTENCE_FIELDS missing=" + ",".join(sorted(missing)))
sys.exit(bool(missing))
'
    then
      test "$variant" = original
      echo "ORIGINAL_SEED_LOST_CONNECTION_BEFORE_ANY_UPGRADE attempt=$attempt" | tee fixture-evidence/cause.txt
      original_failure=1
      break
    fi
    if test "$variant" = corrected; then
      adb shell am instrument -w -e class "$test_class" -e upgradePhase preUpgrade "$runner" > "fixture-evidence/corrected-$attempt-pre-upgrade.txt"
      grep -F 'OK (1 test)' "fixture-evidence/corrected-$attempt-pre-upgrade.txt"
    fi
  done
done
test "$original_failure" = 1
echo 'CORRECTED_SEED_DURABLE_AND_DECRYPTABLE_20_OF_20' | tee -a fixture-evidence/cause.txt
