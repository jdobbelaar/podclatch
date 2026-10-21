"""A tiny audio server for testing PodClatch playback. Standard library only; binds 127.0.0.1 only.

    python -I android/tools/audio_fixture.py --port 8191

Routes (everything else is 404):

    GET|HEAD /tone-<seconds>-<hz>.wav   seconds 1..3600, hz 100..4000: an 8 kHz mono 16-bit PCM WAV of
                                        exactly 44 + seconds * 16000 bytes, generated on the fly
    GET|HEAD /redirect/<path>           302 to /<path>

One `Range: bytes=a-b` (also `a-` and `-n`) is answered with 206 and a correct `Content-Range`; a range
that cannot be satisfied gets 416. The server writes nothing to disk. It logs one line per request holding
only the method, the path (no query string), the status, and `auth=yes|no` (whether an `Authorization`
header was present); never a header value.
"""

import argparse
import math
import re
import struct
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

SAMPLE_RATE = 8000
BYTES_PER_SECOND = SAMPLE_RATE * 2
HEADER_SIZE = 44
AMPLITUDE = 12000
CHUNK = 64 * 1024

TONE_PATH = re.compile(r"^/tone-(\d+)-(\d+)\.wav$")
RANGE_HEADER = re.compile(r"^bytes=(\d*)-(\d*)$")


def parse_tone(path):
    """Returns (seconds, hz) for a valid tone path, else None."""
    match = TONE_PATH.match(path)
    if not match:
        return None
    seconds, hz = int(match.group(1)), int(match.group(2))
    if not (1 <= seconds <= 3600 and 100 <= hz <= 4000):
        return None
    return seconds, hz


def wav_header(seconds):
    data_size = seconds * BYTES_PER_SECOND
    return (
        b"RIFF" + struct.pack("<I", 36 + data_size) + b"WAVE"
        + b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, SAMPLE_RATE, BYTES_PER_SECOND, 2, 16)
        + b"data" + struct.pack("<I", data_size)
    )


def tone_period(hz):
    """One repeating cycle of the tone as PCM bytes (the tone repeats every 8000 / gcd(8000, hz) samples)."""
    length = SAMPLE_RATE // math.gcd(SAMPLE_RATE, hz)
    samples = [int(AMPLITUDE * math.sin(2 * math.pi * hz * i / SAMPLE_RATE)) for i in range(length)]
    return struct.pack("<%dh" % length, *samples)


def total_size(seconds):
    return HEADER_SIZE + seconds * BYTES_PER_SECOND


def wav_slice(seconds, hz, start, end):
    """The bytes start..end (inclusive) of the file, produced in chunks."""
    period = tone_period(hz)
    header = wav_header(seconds)
    position = start
    while position <= end:
        if position < HEADER_SIZE:
            stop = min(end, HEADER_SIZE - 1)
            yield header[position:stop + 1]
            position = stop + 1
            continue
        stop = min(end, position + CHUNK - 1)
        offset = position - HEADER_SIZE
        count = stop - position + 1
        first = offset % len(period)
        repeated = period[first:] + period * (count // len(period) + 1)
        yield repeated[:count]
        position = stop + 1


def parse_range(header, total):
    """Returns (start, end) inclusive, None for no usable Range header, or "unsatisfiable"."""
    if header is None:
        return None
    match = RANGE_HEADER.match(header.strip())
    if not match:
        return None  # not a single byte range: ignore it and send the whole file
    first, last = match.group(1), match.group(2)
    if first == "" and last == "":
        return None
    if first == "":
        suffix = int(last)
        if suffix == 0:
            return "unsatisfiable"
        return max(0, total - suffix), total - 1
    start = int(first)
    end = total - 1 if last == "" else min(int(last), total - 1)
    if start >= total or end < start:
        return "unsatisfiable"
    return start, end


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "AudioFixture"
    sys_version = ""

    def log_message(self, format, *args):  # the default log line would carry the query string
        pass

    def _finish(self, status, headers, body_chunks, send_body):
        self.send_response(status)
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()
        if send_body:
            try:
                for chunk in body_chunks:
                    self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                self.close_connection = True
        self.server.log(
            "%s %s %d auth=%s"
            % (self.command, urlsplit(self.path).path, status, "yes" if self.headers.get("Authorization") else "no")
        )

    def _handle(self, send_body):
        path = urlsplit(self.path).path
        if path.startswith("/redirect/") and len(path) > len("/redirect/"):
            return self._finish(302, [("Location", "/" + path[len("/redirect/"):]), ("Content-Length", "0")], (), False)
        tone = parse_tone(path)
        if tone is None:
            return self._finish(404, [("Content-Length", "0")], (), False)
        seconds, hz = tone
        total = total_size(seconds)
        common = [("Content-Type", "audio/wav"), ("Accept-Ranges", "bytes")]
        requested = parse_range(self.headers.get("Range"), total)
        if requested == "unsatisfiable":
            return self._finish(416, [("Content-Range", "bytes */%d" % total), ("Content-Length", "0")], (), False)
        if requested is None:
            return self._finish(
                200, common + [("Content-Length", str(total))], wav_slice(seconds, hz, 0, total - 1), send_body
            )
        start, end = requested
        headers = common + [
            ("Content-Range", "bytes %d-%d/%d" % (start, end, total)),
            ("Content-Length", str(end - start + 1)),
        ]
        return self._finish(206, headers, wav_slice(seconds, hz, start, end), send_body)

    def do_GET(self):
        self._handle(send_body=True)

    def do_HEAD(self):
        self._handle(send_body=False)


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, port=8191, log=None):
        super().__init__(("127.0.0.1", port), Handler)
        self.log = log or (lambda line: print(line, flush=True))


def main(argv=None):
    parser = argparse.ArgumentParser(description="PodClatch audio fixture server (127.0.0.1 only).")
    parser.add_argument("--port", type=int, default=8191)
    args = parser.parse_args(argv)
    server = FixtureServer(args.port)
    print("audio fixture listening on 127.0.0.1:%d" % server.server_address[1], file=sys.stderr, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
