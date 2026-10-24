"""Fake feed host for the integration tests. Standard library only.

It runs inside the Compose stack as `feedhost` and starts empty. Each test stores the exact RSS it
needs and the server fetches it like any publisher's feed.

    GET  /healthz                     200
    PUT  /_control/feeds/<name>.xml   store the request body as that feed, 204
    GET  /feeds/<name>.xml            200 application/rss+xml with the latest body (query ignored)
    GET  /search                      iTunes-style JSON {"resultCount", "results"} (query ignored);
                                      503 while search-fail is true
    PUT  /_control/search.json        store the request body as the /search body, 204
    POST /_control/search-fail        body `true` or `false`: make /search answer 503, 204
    GET  /_control/search-count       /search requests since the last reset, as plain text, 200
    POST /_control/reset              forget every feed and reset the search body, flag, and count

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
_EMPTY_SEARCH = b'{"resultCount":0,"results":[]}'
_search: dict = {"body": _EMPTY_SEARCH, "fail": False, "count": 0}


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
        if path == "/search":
            with _lock:
                _search["count"] += 1
                failing, search_body = _search["fail"], _search["body"]
            if failing:
                self._reply(503, b"unavailable", "text/plain")
            else:
                self._reply(200, search_body, "text/javascript; charset=utf-8")
            return
        if path == "/_control/search-count":
            with _lock:
                count = _search["count"]
            self._reply(200, str(count).encode("ascii"), "text/plain")
            return
        match = _FEED.fullmatch(path)
        with _lock:
            body = _feeds.get(match.group(1)) if match else None
        if body is None:
            self._reply(404, b"not found", "text/plain")
        else:
            self._reply(200, body, "application/rss+xml")

    def do_PUT(self) -> None:
        body = self._read_body()
        if self._path() == "/_control/search.json":
            with _lock:
                _search["body"] = body
            self._reply(204)
            return
        match = _CONTROL_FEED.fullmatch(self._path())
        if match is None:
            self._reply(404, b"not found", "text/plain")
            return
        with _lock:
            _feeds[match.group(1)] = body
        self._reply(204)

    def do_POST(self) -> None:
        body = self._read_body()
        path = self._path()
        if path == "/_control/reset":
            with _lock:
                _feeds.clear()
                _search.update(body=_EMPTY_SEARCH, fail=False, count=0)
            self._reply(204)
        elif path == "/_control/search-fail":
            flag = body.strip().lower()
            if flag not in (b"true", b"false"):
                self._reply(400, b"expected true or false", "text/plain")
                return
            with _lock:
                _search["fail"] = flag == b"true"
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
