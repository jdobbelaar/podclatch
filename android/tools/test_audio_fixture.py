"""Tests for audio_fixture.py. Run from this folder: python -I -m unittest"""

import http.client
import os
import struct
import sys
import threading
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import audio_fixture  # noqa: E402


class FixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lines = []
        cls.server = audio_fixture.FixtureServer(port=0, log=cls.lines.append)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(5)

    def request(self, method, path, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            connection.request(method, path, headers=headers or {})
            response = connection.getresponse()
            return response, response.read()
        finally:
            connection.close()

    def test_binds_loopback_only(self):
        self.assertEqual("127.0.0.1", self.server.server_address[0])

    def test_tone_is_a_valid_wav_of_exact_size(self):
        response, body = self.request("GET", "/tone-2-440.wav")
        self.assertEqual(200, response.status)
        self.assertEqual(44 + 2 * 16000, len(body))
        self.assertEqual(str(len(body)), response.getheader("Content-Length"))
        self.assertEqual("audio/wav", response.getheader("Content-Type"))
        self.assertEqual("bytes", response.getheader("Accept-Ranges"))
        self.assertEqual(b"RIFF", body[:4])
        self.assertEqual(b"WAVE", body[8:12])
        self.assertEqual((1, 1, 8000, 16000, 2, 16), struct.unpack("<HHIIHH", body[20:36]))
        self.assertEqual(2 * 16000, struct.unpack("<I", body[40:44])[0])
        samples = struct.unpack("<%dh" % (2 * 8000), body[44:])
        self.assertGreater(max(samples), 1000)
        self.assertLess(min(samples), -1000)

    def test_range_returns_206_with_the_matching_bytes(self):
        _, whole = self.request("GET", "/tone-3-523.wav")
        response, body = self.request("GET", "/tone-3-523.wav", {"Range": "bytes=0-99"})
        self.assertEqual(206, response.status)
        self.assertEqual(100, len(body))
        self.assertEqual("bytes 0-99/%d" % len(whole), response.getheader("Content-Range"))
        self.assertEqual(whole[:100], body)
        response, body = self.request("GET", "/tone-3-523.wav", {"Range": "bytes=1000-5000"})
        self.assertEqual("bytes 1000-5000/%d" % len(whole), response.getheader("Content-Range"))
        self.assertEqual(whole[1000:5001], body)

    def test_open_ended_and_suffix_ranges(self):
        _, whole = self.request("GET", "/tone-1-440.wav")
        response, body = self.request("GET", "/tone-1-440.wav", {"Range": "bytes=15000-"})
        self.assertEqual(206, response.status)
        self.assertEqual(whole[15000:], body)
        response, body = self.request("GET", "/tone-1-440.wav", {"Range": "bytes=-50"})
        self.assertEqual(206, response.status)
        self.assertEqual(whole[-50:], body)
        response, body = self.request("GET", "/tone-1-440.wav", {"Range": "bytes=100-99999999"})
        self.assertEqual("bytes 100-%d/%d" % (len(whole) - 1, len(whole)), response.getheader("Content-Range"))
        self.assertEqual(whole[100:], body)

    def test_unsatisfiable_range_is_416(self):
        total = 44 + 16000
        response, _ = self.request("GET", "/tone-1-440.wav", {"Range": "bytes=%d-" % total})
        self.assertEqual(416, response.status)
        self.assertEqual("bytes */%d" % total, response.getheader("Content-Range"))

    def test_head_has_headers_and_no_body(self):
        response, body = self.request("HEAD", "/tone-3600-440.wav")
        self.assertEqual(200, response.status)
        self.assertEqual(str(44 + 3600 * 16000), response.getheader("Content-Length"))
        self.assertEqual(b"", body)

    def test_redirect(self):
        response, _ = self.request("GET", "/redirect/tone-60-523.wav")
        self.assertEqual(302, response.status)
        self.assertEqual("/tone-60-523.wav", response.getheader("Location"))

    def test_everything_else_is_404(self):
        for path in (
            "/", "/missing.wav", "/tone-0-440.wav", "/tone-3601-440.wav", "/tone-10-99.wav", "/tone-10-4001.wav",
            "/tone-10-440.mp3", "/tone-x-440.wav",
        ):
            with self.subTest(path=path):
                response, _ = self.request("GET", path)
                self.assertEqual(404, response.status)

    def test_log_line_has_method_path_status_and_auth_only(self):
        self.lines.clear()
        self.request("GET", "/tone-1-440.wav?secret=abc", {"Authorization": "Bearer hunter2"})
        self.request("GET", "/missing.wav")
        self.assertEqual(["GET /tone-1-440.wav 200 auth=yes", "GET /missing.wav 404 auth=no"], self.lines)
        joined = " ".join(self.lines)
        self.assertNotIn("secret", joined)
        self.assertNotIn("hunter2", joined)


if __name__ == "__main__":
    unittest.main()
