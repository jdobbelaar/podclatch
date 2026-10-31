"""Test webhook receiver for the Grafana alert (US-37). Standard library only, no secrets.

POST anything: the JSON body is logged to stdout (docker compose logs alert-receiver) and kept as the
latest payload. GET /last returns that latest payload; GET /healthz returns ok.
"""

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LAST = {"payload": None}


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, content_type="application/json"):
        data = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8", "replace")
        try:
            LAST["payload"] = json.loads(raw)
            print(f"ALERT-WEBHOOK {json.dumps(LAST['payload'])}", flush=True)
        except ValueError:
            print(f"ALERT-WEBHOOK-NOT-JSON {raw[:2000]}", flush=True)
        self._send(200, '{"ok":true}')

    def do_GET(self):
        if self.path == "/healthz":
            self._send(200, "ok", "text/plain")
        elif self.path == "/last":
            self._send(200, json.dumps(LAST["payload"], indent=2))
        else:
            self._send(404, '{"detail":"not found"}')

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} {fmt % args}", file=sys.stderr, flush=True)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
