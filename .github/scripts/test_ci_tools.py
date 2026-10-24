"""Tests for ci_tools.py. Run from the repository root: python -m unittest discover -s .github/scripts -v

The health tests run against a local http.server stub on a free port; nothing here needs AWS.
"""

import contextlib
import http.server
import io
import json
import threading
import unittest
import unittest.mock

import ci_tools

SHA_A = "a" * 40
SHA_B = "b" * 40


class StubHandler(http.server.BaseHTTPRequestHandler):
    # Set per test: a list of (status, body) answered in order; the last one repeats.
    answers = []
    seen_paths = []

    def do_GET(self):
        cls = type(self)
        cls.seen_paths.append(self.path)
        status, body = cls.answers[0] if len(cls.answers) == 1 else cls.answers.pop(0)
        data = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


class HealthTest(unittest.TestCase):
    def setUp(self):
        StubHandler.answers = []
        StubHandler.seen_paths = []
        self.server = http.server.HTTPServer(("127.0.0.1", 0), StubHandler)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def run_health(self, timeout, interval=0.05):
        lines = []
        ci_tools.wait_for_health(self.url + "?secret=1", timeout, interval, log=lines.append)
        return lines

    def test_succeeds_on_ok(self):
        StubHandler.answers = [(200, '{"status":"ok"}')]
        self.run_health(5)
        self.assertEqual(StubHandler.seen_paths, ["/healthz"])  # the query string is dropped

    def test_keeps_polling_through_503_then_succeeds(self):
        StubHandler.answers = [(503, "{}"), (503, "{}"), (200, '{"status":"ok"}')]
        lines = self.run_health(10)
        self.assertEqual(len(StubHandler.seen_paths), 3)
        self.assertTrue(any("HTTP 503" in line for line in lines))
        self.assertNotIn("secret", "".join(lines))

    def test_wrong_body_is_not_healthy(self):
        StubHandler.answers = [(200, '{"status":"starting"}')]
        with self.assertRaises(ci_tools.CiError):
            self.run_health(0.3)

    def test_times_out_with_a_clear_message(self):
        StubHandler.answers = [(503, "{}")]
        with self.assertRaises(ci_tools.CiError) as raised:
            self.run_health(0.3)
        self.assertIn("Timed out", str(raised.exception))
        self.assertIn("/healthz", str(raised.exception))
        self.assertIn("HTTP 503", str(raised.exception))

    def test_unreachable_host_times_out(self):
        self.server.shutdown()
        self.server.server_close()
        with self.assertRaises(ci_tools.CiError):
            self.run_health(0.3)

    def test_empty_url_is_rejected(self):
        with self.assertRaises(ci_tools.CiError):
            ci_tools.wait_for_health("", 1, 0.1)

    def test_command_line_exit_codes(self):
        StubHandler.answers = [(200, '{"status":"ok"}')]
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(ci_tools.main(["health", "--url", self.url, "--timeout", "5"]), 0)
        StubHandler.answers = [(503, "{}")]
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(ci_tools.main(["health", "--url", self.url, "--timeout", "0.3", "--interval", "0.05"]), 1)
        self.assertIn("::error::Timed out", out.getvalue())


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def clock(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class WaitServiceTest(unittest.TestCase):
    def wait(self, statuses, timeout=100, interval=10, grace=30):
        fake = FakeClock()
        queue = list(statuses)
        lines = []
        ci_tools.wait_for_service(
            "podclatch-dev", lambda name: queue.pop(0) if len(queue) > 1 else queue[0],
            timeout, interval, grace, sleep=fake.sleep, clock=fake.clock, log=lines.append,
        )
        return lines

    def test_waits_for_the_deployment_to_start_and_finish(self):
        lines = self.wait(["RUNNING", "OPERATION_IN_PROGRESS", "OPERATION_IN_PROGRESS", "RUNNING"])
        self.assertEqual(lines[-1], "App Runner service podclatch-dev: RUNNING")
        self.assertEqual(len(lines), 4)  # the first RUNNING (before the deployment began) did not count

    def test_continues_with_a_warning_when_no_deployment_is_seen(self):
        lines = self.wait(["RUNNING"])
        self.assertTrue(any(line.startswith("::warning::") for line in lines))

    def test_failed_status_stops_at_once(self):
        with self.assertRaises(ci_tools.CiError) as raised:
            self.wait(["OPERATION_IN_PROGRESS", "CREATE_FAILED"])
        self.assertIn("CREATE_FAILED", str(raised.exception))

    def test_missing_service_is_a_clear_error(self):
        with self.assertRaises(ci_tools.CiError) as raised:
            self.wait([None])
        self.assertIn("podclatch-dev", str(raised.exception))

    def test_times_out_if_never_running(self):
        with self.assertRaises(ci_tools.CiError) as raised:
            self.wait(["OPERATION_IN_PROGRESS"], timeout=50)
        self.assertIn("Timed out", str(raised.exception))


def images(*entries):
    return {"imageDetails": [{"imageDigest": digest, "imageTags": tags} for digest, tags in entries]}


class SelectPromotionTest(unittest.TestCase):
    listing = images(
        ("sha256:" + "1" * 64, [SHA_A]),
        ("sha256:" + "2" * 64, [SHA_B, "dev"]),
    )

    def test_default_is_the_image_running_on_dev(self):
        self.assertEqual(ci_tools.select_promotion(self.listing), (SHA_B, "sha256:" + "2" * 64))

    def test_explicit_sha_that_runs_on_dev(self):
        self.assertEqual(ci_tools.select_promotion(self.listing, SHA_B), (SHA_B, "sha256:" + "2" * 64))

    def test_refuses_an_image_that_was_not_on_dev(self):
        with self.assertRaises(ci_tools.CiError) as raised:
            ci_tools.select_promotion(self.listing, SHA_A)
        self.assertIn("not the one tagged dev", str(raised.exception))

    def test_refuses_an_unknown_sha(self):
        with self.assertRaises(ci_tools.CiError):
            ci_tools.select_promotion(self.listing, "c" * 40)

    def test_refuses_a_malformed_sha(self):
        with self.assertRaises(ci_tools.CiError):
            ci_tools.select_promotion(self.listing, "main")

    def test_nothing_tagged_dev(self):
        with self.assertRaises(ci_tools.CiError):
            ci_tools.select_promotion(images(("sha256:" + "1" * 64, [SHA_A])))

    def test_dev_image_without_a_sha_tag(self):
        with self.assertRaises(ci_tools.CiError):
            ci_tools.select_promotion(images(("sha256:" + "1" * 64, ["dev"])))

    def test_main_prints_outputs(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), unittest.mock.patch("sys.stdin", io.StringIO(json.dumps(self.listing))):
            self.assertEqual(ci_tools.main(["select-promotion"]), 0)
        self.assertEqual(out.getvalue().splitlines(), [f"sha={SHA_B}", "digest=sha256:" + "2" * 64])


if __name__ == "__main__":
    unittest.main()
