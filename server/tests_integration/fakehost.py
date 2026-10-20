"""Fake feed host for the integration tests. Standard library only.

It runs inside the Compose stack as `feedhost` and starts empty. Each test stores the exact RSS it
needs and the server fetches it like any publisher's feed.

    GET  /healthz                     200
    PUT  /_control/feeds/<name>.xml   store the request body as that feed, 204
    GET  /feeds/<name>.xml            200 application/rss+xml with the latest body (query ignored)
    POST /_control/reset              forget every feed, 204

An unknown feed is 404. Log lines hold the method, path, and status only, never a query string,
because feed URLs can carry secrets.
"""

import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

PORT = 8080
_NAME = r"[A-Za-z0-9_.-]+\.xml"
_CONTROL_FEED = re.compile(rf"/_control/feeds/({_NAME})")
_FEED = re.compile(rf"/feeds/({_NAME})")

_lock = threading.Lock()
_feeds: dict[str, bytes] = {}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _path(self) -> str:
        return urlsplit(self.path).path

    def _reply(self, status: int, body: bytes = b"", content_type: str | None = None) -> None:
        self.send_response(status)
        if content_type:
            self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body and self.command != "HEAD":
            self.wfile.write(body)

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def do_GET(self) -> None:
        path = self._path()
        if path == "/healthz":
            self._reply(200, b'{"status":"ok"}', "application/json")
            return
        match = _FEED.fullmatch(path)
        with _lock:
            body = _feeds.get(match.group(1)) if match else None
        if body is None:
            self._reply(404, b"not found", "text/plain")
        else:
            self._reply(200, body, "application/rss+xml")

    def do_PUT(self) -> None:
        match = _CONTROL_FEED.fullmatch(self._path())
        body = self._read_body()
        if match is None:
            self._reply(404, b"not found", "text/plain")
            return
        with _lock:
            _feeds[match.group(1)] = body
        self._reply(204)

    def do_POST(self) -> None:
        self._read_body()
        if self._path() == "/_control/reset":
            with _lock:
                _feeds.clear()
            self._reply(204)
        else:
            self._reply(404, b"not found", "text/plain")

    def log_request(self, code: object = "-", size: object = "-") -> None:
        print(f"{self.command} {self._path()} {code}", flush=True)

    def log_message(self, format: str, *args: object) -> None:
        # Silence the default lines (error texts can echo the request line, query string included).
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
