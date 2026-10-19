"""Fail if a new test is silently omitted from the explicit no-vendor profiles."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2] / '06_项目源代码/ticket-service'
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
pom = ET.parse(root / 'pom.xml')
profiles = {}
for profile in pom.findall('m:profiles/m:profile', ns):
    name = profile.findtext('m:id', namespaces=ns)
    if name in ('unit', 'integration'):
        profiles[name] = {Path(node.text).stem for node in profile.findall('.//m:include', ns)}
assert profiles.keys() == {'unit', 'integration'}
assert not profiles['unit'] & profiles['integration'], 'Duplicate profile test classes'
for file in (root / 'src/test/java').rglob('*Test.java'):
    text = file.read_text(encoding='utf-8-sig')
    vendor = bool(re.search(r'^@EnabledIfEnvironmentVariable', text, re.M))
    expected = 'integration' if re.search(r'^@SpringBootTest', text, re.M) else 'unit'
    if vendor:
        assert all(file.stem not in classes for classes in profiles.values()), f'Vendor in CI: {file.stem}'
    else:
        assert file.stem in profiles[expected], f'Unclassified/misclassified test: {file.stem}'
print('Test profile coverage complete; vendor tests excluded from CI.')
