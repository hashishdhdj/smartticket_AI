"""Build the same verified runtime sources and register a fresh JAR after both test profiles.

Java 17, Maven, and prepare-phase4.py are prerequisites. No vendor model calls.
The local record prevents accidental old/unverified artifacts; it is not a signed attestation.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from quality_checks import ROOT, runtime_baseline, verify_artifact


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--maven', default=shutil.which('mvn.cmd' if os.name == 'nt' else 'mvn'))
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--temp-dir', type=Path, default=ROOT / '.tmp')
    args = parser.parse_args()
    java_home = os.environ.get('JAVA_HOME')
    if not java_home or not args.maven:
        raise ValueError('Set JAVA_HOME to Java17 and put Maven on PATH, or pass --maven')
    java = Path(java_home) / ('bin/java.exe' if os.name == 'nt' else 'bin/java')
    version = subprocess.run([str(java), '-version'], capture_output=True, check=True).stderr.decode(errors='replace')
    if not re.search(r'version "17(?:\.|\")', version):
        raise ValueError('Java17 is required')
    temp = ROOT / '.tmp'
    properties = temp / 'phase4-integration.properties'
    if not properties.is_file():
        raise ValueError('Run prepare-phase4.py first; preserve existing isolated credentials')
    for service_name in ('mysql', 'redis', 'pgvector', 'rabbitmq'):
        health = subprocess.run(['docker', 'inspect', '--format', '{{.State.Health.Status}}',
            'smartticket-stage4-' + service_name + '-1'], capture_output=True, timeout=20)
        if health.returncode or health.stdout.strip() != b'healthy':
            raise RuntimeError('Start Docker and run prepare-phase4.py; isolated dependencies must be healthy before building')
    service = ROOT / '06_项目源代码/ticket-service'
    baseline = runtime_baseline()
    sources = baseline['known_credentials_and_jar_audit']['source_files']
    # Compare with the explicit current source baseline; never auto-rebase changed files.
    for item in sources:
        path = (service / item['path']).resolve()
        if not path.is_relative_to(service.resolve()) or hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise ValueError('Reviewed service sources changed; this needs a new runtime verification baseline')
    current = {file.relative_to(service).as_posix() for parent in ('src/main', 'src/test')
               for file in (service / parent).rglob('*') if file.is_file()}
    if not current.issubset({item['path'] for item in sources}):
        raise ValueError('Unreviewed service source/config found')
    environment = os.environ.copy()
    environment['AI_SMOKE'] = 'false'
    # Short OS temp paths avoid the Windows JDK's UNIX-domain/loopback pathname limit.
    # The desktop sandbox's TEMP can be over 100 characters; do not derive this from it.
    short_temp = args.temp_dir.resolve()
    if len(str(short_temp).encode('utf-8')) > 70:
        raise ValueError('Pass --temp-dir with a short writable absolute path for local socket tests')
    short_temp.mkdir(exist_ok=True, parents=True)
    base = [args.maven, '-B', '-ntp'] + (['-o'] if args.offline else [])
    reports = service / 'target/surefire-reports'
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    pom = ET.parse(service / 'pom.xml')
    totals = {}
    for name in ('unit', 'integration'):
        command = base + ['-P' + name, f'-DargLine=-Djava.io.tmpdir={short_temp} -Djdk.net.unixdomain.tmpdir={short_temp}']
        command += ['clean', 'test'] if name == 'unit' else [f'-Dspring.config.additional-location={properties.as_uri()}', 'package']
        with (temp / ('quality-build-' + name + '.log')).open('wb') as output:
            result = subprocess.run(command, cwd=service, env=environment, stdout=output, stderr=subprocess.STDOUT, timeout=900)
        if result.returncode:
            raise RuntimeError('Current-source ' + name + ' build failed; inspect the ignored build log')
        expected_classes = {Path(node.text).stem for profile in pom.findall('m:profiles/m:profile', ns)
            if profile.findtext('m:id', namespaces=ns) == name for node in profile.findall('.//m:include', ns)}
        suites = [ET.parse(file).getroot() for file in reports.glob('TEST-*.xml')]
        suites = [suite for suite in suites if suite.attrib['name'].split('.')[-1] in expected_classes]
        if {suite.attrib['name'].split('.')[-1] for suite in suites} != expected_classes:
            raise ValueError('Missing current-profile test reports')
        totals[name] = {key: sum(int(suite.attrib[key]) for suite in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
        if totals[name] != baseline['java_profiles'][name]['totals']:
            raise ValueError('Unexpected test counts/failures/skips; no artifact approved')
    jar = service / 'target/ticket-service-0.0.1-SNAPSHOT.jar'
    record = {'status': 'PASS', 'date': datetime.now(timezone.utc).isoformat(), 'vendor_calls': 0,
        'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
        'source_manifest_sha256': hashlib.sha256(json.dumps(sources, sort_keys=True).encode()).hexdigest(),
        'profiles': totals, 'java_major': 17, 'local_build_record_not_signed_attestation': True}
    path = temp / 'quality-artifact-build.json'
    # Register only after both Maven commands and complete-profile counts pass.
    previous = path.read_bytes() if path.exists() else None
    try:
        path.write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
        verify_artifact(jar)
    except BaseException:
        if previous is None:
            path.unlink(missing_ok=True)
        else:
            path.write_bytes(previous)
        raise
    print(json.dumps({'status': 'PASS', 'jar_sha256': record['jar_sha256'], 'profiles': totals, 'vendor_calls': 0}))


if __name__ == '__main__':
    main()
