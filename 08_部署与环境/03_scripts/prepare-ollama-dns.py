"""Resolve the official download CDN through HTTPS DNS when local DNS returns Fake-IP.

Only the Ollama container receives this exact-host mapping. TLS and Ollama public-IP
checks remain enabled. Regenerate on each setup; never trust a stale override file.
"""
from pathlib import Path
from datetime import datetime, timezone
import ipaddress
import json
import socket
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
HOST = 'dd20bb891979d25aebc8bec07b2b3bbc.r2.cloudflarestorage.com'
OVERRIDE = ROOT / '.tmp/ollama-public-dns.override.yml'


def main():
    addresses = sorted({r[4][0] for r in socket.getaddrinfo(HOST, 443, type=socket.SOCK_STREAM)})
    fake_range = ipaddress.ip_network('198.18.0.0/15')
    if not any(ipaddress.ip_address(a) in fake_range for a in addresses if ':' not in a):
        # The caller uses only this invocation's result, never an old file implicitly.
        print('SYSTEM_DNS')
        return
    request = urllib.request.Request('https://cloudflare-dns.com/dns-query?name=' + HOST + '&type=A',
                                     headers={'Accept': 'application/dns-json'})
    with urllib.request.urlopen(request, timeout=20) as response:
        data = json.load(response)
    if data.get('Status') != 0:
        raise RuntimeError('HTTPS DNS query failed')
    records = [r for r in data.get('Answer', []) if r.get('type') == 1 and r.get('name', '').rstrip('.') == HOST]
    public = sorted({r['data'] for r in records})
    if not public or any(not ipaddress.ip_address(a).is_global for a in public):
        raise RuntimeError('Official download host did not resolve to public addresses')
    # Keep the genuine hostname for TLS SNI/certificate validation; do not use --insecure.
    value = {'services': {'ollama': {'extra_hosts': [f'{HOST}={a}' for a in public]}}}
    OVERRIDE.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
    evidence = {'queried_at': datetime.now(timezone.utc).isoformat(), 'hostname': HOST,
                'resolver': 'https://cloudflare-dns.com/dns-query', 'addresses': public,
                'ttl_seconds': min(r['TTL'] for r in records), 'scope': 'Ollama container only',
                'tls_verification': True, 'ollama_public_ip_check': True}
    (ROOT / '.tmp/ollama-public-dns-evidence.json').write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    print('PUBLIC_DNS_OVERRIDE')


if __name__ == '__main__':
    main()
