"""Verify scoped DNS repair without network requests or Docker changes."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('ollama_dns', Path(__file__).with_name('prepare-ollama-dns.py'))
dns = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dns)


class DnsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / '.tmp').mkdir()
        self.override = self.root / '.tmp/override.json'
        self.patches = [mock.patch.object(dns, 'ROOT', self.root),
                        mock.patch.object(dns, 'OVERRIDE', self.override)]
        for patch in self.patches: patch.start()

    def tearDown(self):
        for patch in self.patches: patch.stop()
        self.temp.cleanup()

    def resolve(self, address):
        return mock.patch.object(dns.socket, 'getaddrinfo', return_value=[(None, None, None, None, (address, 443))])

    def response(self, address):
        return io.BytesIO(json.dumps({'Status':0, 'Answer':[
            {'name':dns.HOST, 'type':1, 'data':address, 'TTL':300}]}).encode())

    def test_real_system_dns_does_not_use_stale_override_or_network(self):
        self.override.write_text('stale mapping')
        with self.resolve('172.64.66.1'), mock.patch.object(dns.urllib.request, 'urlopen') as fetch, mock.patch('builtins.print') as output:
            dns.main()
        fetch.assert_not_called()
        output.assert_called_once_with('SYSTEM_DNS')

    def test_fake_ip_repaired_only_for_exact_public_download_host(self):
        with self.resolve('198.18.0.159'), mock.patch.object(dns.urllib.request, 'urlopen', return_value=self.response('172.64.66.1')):
            dns.main()
        data = json.loads(self.override.read_text())
        self.assertEqual({'services':{'ollama':{'extra_hosts':[dns.HOST+'=172.64.66.1']}}}, data)
        evidence = json.loads((self.root / '.tmp/ollama-public-dns-evidence.json').read_text())
        self.assertTrue(evidence['tls_verification'])
        self.assertTrue(evidence['ollama_public_ip_check'])

    def test_non_public_doh_response_is_rejected(self):
        with self.resolve('198.18.0.159'), mock.patch.object(dns.urllib.request, 'urlopen', return_value=self.response('127.0.0.1')):
            with self.assertRaises(RuntimeError): dns.main()
        self.assertFalse(self.override.exists())


if __name__ == '__main__': unittest.main()
