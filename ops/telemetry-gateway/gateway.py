"""Read-only, authenticated gateway in front of the lgtm container's Loki, Tempo, and Prometheus (US-38a).

Standard library only. One prefix per backend, then a strict allowlist of query paths:

    /loki/...   -> Loki        http://lgtm:3100/...   (so Loki's /loki/api/v1/query is /loki/loki/api/v1/query)
    /tempo/...  -> Tempo       http://lgtm:3200/...
    /prom/...   -> Prometheus  http://lgtm:9090/...

Rules, in order, for every request:
  1. No token configured (TELEMETRY_GATEWAY_TOKEN unset or empty): refuse everything with 503 (fail closed).
  2. `Authorization: Bearer <token>` must match, compared in constant time (else 401, no upstream call).
  3. The method must be GET or HEAD (else 405).
  4. The raw request path must match the allowlist exactly. No decoding is done: any `%`, `..`, `//`, or
     other character outside the allowlist patterns is refused (403), so encoded slashes and dot segments
     cannot escape it.
  5. The upstream response is refused (502) if it exceeds MAX_RESPONSE_BYTES, and the upstream call has
     an UPSTREAM_TIMEOUT_SECONDS limit (504).

Logs carry only the method, the path (never the query string), and the status. The Authorization header
and the token are never logged.
"""

import hmac
import http.client
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Response size cap (4 MiB) and upstream time limit. Stated in the README too.
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
UPSTREAM_TIMEOUT_SECONDS = 20

UPSTREAM_HOST = os.environ.get("TELEMETRY_UPSTREAM_HOST", "lgtm")
BACKENDS = {"loki": 3100, "tempo": 3200, "prom": 9090}

# Matched against the raw request path (query string removed), nothing else.
ALLOWED = [
    re.compile(r"/loki/loki/api/v1/(query|query_range|labels)"),
    re.compile(r"/loki/loki/api/v1/label/[A-Za-z0-9_.:-]+/values"),
    re.compile(r"/tempo/api/traces/[0-9a-fA-F]{1,32}"),
    re.compile(r"/tempo/api/search"),
    re.compile(r"/prom/api/v1/(query|query_range)"),
]


def is_allowed(path):
    return any(p.fullmatch(path) for p in ALLOWED)


def token_ok(header, token):
    if not token:
        return False
    return hmac.compare_digest((header or "").encode(), ("Bearer " + token).encode())


class Handler(BaseHTTPRequestHandler):
    server_version = "telemetry-gateway"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # the default format can include the query string
        pass

    def reply(self, status, body=b"", content_type="text/plain; charset=utf-8", head_only=False):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        if status == 401:
            self.send_header("WWW-Authenticate", "Bearer")
        self.end_headers()
        if not head_only:
            self.wfile.write(body)
        print(f"{self.command} {self.path.split('?', 1)[0][:200]} {status}", file=sys.stderr, flush=True)

    def handle_any(self):
        head = self.command == "HEAD"
        token = os.environ.get("TELEMETRY_GATEWAY_TOKEN", "")
        if not token:
            return self.reply(503, b"gateway token is not configured\n", head_only=head)
        if not token_ok(self.headers.get("Authorization"), token):
            return self.reply(401, b"unauthorized\n", head_only=head)
        if self.command not in ("GET", "HEAD"):
            return self.reply(405, b"read-only gateway: GET and HEAD only\n")
        path, _, query = self.path.partition("?")
        if not is_allowed(path):
            return self.reply(403, b"path not allowed\n", head_only=head)
        prefix = path.split("/", 2)[1]
        upstream_path = path[len("/" + prefix) :] + ("?" + query if query else "")
        conn = http.client.HTTPConnection(UPSTREAM_HOST, BACKENDS[prefix], timeout=UPSTREAM_TIMEOUT_SECONDS)
        try:
            conn.request("GET", upstream_path, headers={"Accept": "application/json"})
            resp = conn.getresponse()
            body = resp.read(MAX_RESPONSE_BYTES + 1)
            if len(body) > MAX_RESPONSE_BYTES:
                return self.reply(502, b"response too large (cap 4 MiB); narrow the query\n", head_only=head)
            self.reply(resp.status, body, resp.getheader("Content-Type", "application/octet-stream"), head)
        except TimeoutError:
            self.reply(504, b"upstream timeout\n", head_only=head)
        except OSError:
            self.reply(502, b"upstream unavailable\n", head_only=head)
        finally:
            conn.close()

    do_GET = do_HEAD = do_POST = do_PUT = do_DELETE = do_PATCH = do_OPTIONS = handle_any


def main():
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()


if __name__ == "__main__":
    main()
