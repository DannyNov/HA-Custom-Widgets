"""Current supported project invariants; historical release freezes run at their commits."""
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


def check_text(data, path):
    text = data.decode('utf-8', errors='strict')
    if '\ufffd' in text or any(ord(c) < 32 and c not in '\t\n\r' for c in text):
        raise ValueError(f'{path}: replacement character or invalid control character')
    # Character sequences produced by decoding Russian UTF-8 as CP1251/Latin-1.
    if any(marker in text for marker in ('Рџ', 'Рµ', 'Р°', 'С‚', 'СЃ', 'Ð', 'Ñ')):
        raise ValueError(f'{path}: possible mojibake; restore the original UTF-8 text')
    return text


def main():
    paths = subprocess.check_output(['git', 'ls-files', 'app'], text=True).splitlines()
    count = 0
    for name in paths:
        path = Path(name)
        if path.suffix in {'.kt', '.kts', '.xml', '.properties', '.pro', '.json', '.txt'}:
            text = check_text(path.read_bytes(), name)
            if path.suffix == '.xml':
                ET.fromstring(text)
            count += 1
    build = Path('app/build.gradle.kts').read_text(encoding='utf-8')
    for setting, value in [('minSdk', '31'), ('targetSdk', '36'), ('compileSdk', '36'),
                           ('versionCode', '108'), ('versionName', '"0.9.0"'),
                           ('applicationId', '"com.danila.hacustomwidgets"')]:
        if not re.search(r'\b' + setting + r'\s*=\s*' + re.escape(value) + r'\s*$', build, re.M):
            raise ValueError(f'Unexpected {setting}; update supported policy explicitly')
    if re.search(r'isDebuggable\s*=\s*true', build):
        raise ValueError('Explicit debuggable build override requires review')
    workflow = Path('.github/workflows/compile-test-v035.yml').read_text(encoding='utf-8')
    if len(re.findall(r'^          - source:', workflow, re.M)) != 31:
        raise ValueError('Historical upgrade matrix changed')
    if 'api: [31, 36]' not in workflow:
        raise ValueError('Supported Android host matrix changed')
    print(f'CURRENT_PROJECT_PASS: {count} UTF-8 source/resource files; XML; Android 12+; package/version; 31 upgrades')


if __name__ == '__main__':
    main()
