"""Guard the offline G4 fixture's HTTP body parsing, including the JDK streaming mode."""
from pathlib import Path
from types import SimpleNamespace
import importlib.util
import io
import unittest

spec = importlib.util.spec_from_file_location('g4_offline_fixture', Path(__file__).with_name('verify-agent-experience-graph.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class GraphFixtureBodyTest(unittest.TestCase):
    def handler(self, headers, body):
        return SimpleNamespace(headers=headers, rfile=io.BytesIO(body))

    def test_fixed_length_body(self):
        self.assertEqual(b'{"a":1}', fixture.read_body(self.handler({'Content-Length': '7'}, b'{"a":1}')))

    def test_streamed_chunked_request_with_extension_and_trailer(self):
        h = self.handler({'Transfer-Encoding': 'chunked'}, b'3;x=y\r\nabc\r\n2\r\nde\r\n0\r\nX-Test: fixture\r\n\r\n')
        self.assertEqual(b'abcde', fixture.read_body(h))

    def test_length_limit_precedes_body_read(self):
        with self.assertRaises(ValueError):
            fixture.read_body(self.handler({'Content-Length': '999'}, b''), maximum=8)

    def test_chunk_limit_counts_all_chunks(self):
        with self.assertRaises(ValueError):
            fixture.read_body(self.handler({'Transfer-Encoding': 'chunked'}, b'3\r\nabc\r\n3\r\ndef\r\n0\r\n\r\n'), maximum=5)

    def test_truncated_fixed_or_chunked_body_is_rejected(self):
        for h in (self.handler({'Content-Length': '7'}, b'abc'),
                  self.handler({'Transfer-Encoding': 'chunked'}, b'3\r\nab'),
                  self.handler({'Transfer-Encoding': 'chunked'}, b'3\nabc')):
            with self.subTest(headers=h.headers), self.assertRaises(ValueError):
                fixture.read_body(h)

    def test_missing_length_is_not_treated_as_empty_json(self):
        with self.assertRaises(ValueError):
            fixture.read_body(self.handler({}, b''))


if __name__ == '__main__':
    unittest.main()
