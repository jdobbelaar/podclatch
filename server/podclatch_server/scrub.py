"""The one place an exception becomes text that may leave the process (issue #123, decision #27).

`scrub_exception` is the only function that turns an exception into export-safe text. Every export
path (the OTLP log handler, the server span in `TelemetryMiddleware`, the per-Feed span in
`refresh.py`) calls it and nothing else formats an exception for telemetry. It never raises.

What it does to the text of an exception and of every exception in its `__cause__` and
`__context__` chain (to `MAX_CHAIN_DEPTH` links, each exception visited once):

1. The configured secrets (`PODCLATCH_TOKEN`, `TELEMETRY_GATEWAY_TOKEN`, and any value passed to
   `register_secrets`) are replaced by `[redacted]` wherever they appear, inside longer strings too.
2. Every URL of any scheme is reduced to `scheme://host`: userinfo, port, path, query string, and
   fragment are dropped.
3. From a word such as `Authorization`, `Bearer`, `Basic`, `X-Api-Key`, or `Cookie` (any case,
   quoting, or spacing) to the end of the message becomes `[redacted]`: the value's quoting is not
   parsed, so nothing after the word can leak. Bracketed IPv6 hosts are kept as hosts; a URL with an
   unclosed bracket redacts the rest of the message.
4. Runs of `OPAQUE_MIN_CHARS` or more characters of `[A-Za-z0-9_-]` (and padded base64) become
   `[redacted]`.
5. The result is cut to `MAX_CHARS` characters, after scrubbing, with a visible marker.

The stack summary is module path, function name, and line number per frame (innermost last, at
most `MAX_FRAMES` frames); source lines, locals, and arguments are never read. It is scrubbed the
same way.

What it cannot do: recognize feed text (an episode title quoted by a parser) as sensitive. The cap
and review of the messages our own code raises are the controls for that (accepted on issue #123).
"""

import os
import re
import traceback
from typing import NamedTuple

REDACTED = "[redacted]"
TRUNCATION_MARKER = "...[truncated]"
MAX_CHARS = 1000
OPAQUE_MIN_CHARS = 24
MAX_CHAIN_DEPTH = 5
MAX_FRAMES = 20
RAW_LINK_CHARS = 2000
SECRET_ENV_VARS = ("PODCLATCH_TOKEN", "TELEMETRY_GATEWAY_TOKEN")

# Every pattern is linear in the input: bounded quantifiers, and a lookbehind (with a possessive
# quantifier) so a run of characters is scanned once, not once per starting position. The raw text
# is also cut to RAW_LINK_CHARS per exception before any pattern runs.
_URL = re.compile(r"[A-Za-z][A-Za-z0-9+.\-]{0,31}://(?:\[[^\]\s]{0,64}\]|[^\s'\"<>()\[\]{}`])*")
# Fail closed: from one of these words (any case, any quoting or spacing around it) to the end of
# the message is redacted, rather than trying to parse the value's quoting. `Basic` and `cookie`
# are ordinary words too; losing the text after them is accepted, a leak is not.
_SENSITIVE = re.compile(
    r"(?is)\b(authorization|proxy-authorization|x-api-key|x-auth-token|api[-_]?key|"
    r"set-cookie|cookie|bearer|basic)\b.*"
)
_OPAQUE = re.compile(rf"(?<![A-Za-z0-9_\-])[A-Za-z0-9_\-]{{{OPAQUE_MIN_CHARS},}}+")
_BASE64_PADDED = re.compile(r"(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{16,}+={1,2}")

_extra_secrets: set[str] = set()


class ScrubbedException(NamedTuple):
    type: str
    message: str
    stacktrace: str


def register_secrets(*values: str | None) -> None:
    """Add secret values (for example the configured token) that must never appear in output."""
    for value in values:
        if isinstance(value, str) and value.strip():
            _extra_secrets.add(value)


def _known_secrets() -> list[str]:
    values = set(_extra_secrets)
    for name in SECRET_ENV_VARS:
        value = os.environ.get(name)
        if value and value.strip():
            values.add(value)
    return sorted(values, key=len, reverse=True)  # longest first, so a prefix cannot split one


def _reduce_url(match: re.Match[str]) -> str:
    url = match.group(0)
    scheme, _, rest = url.partition("://")
    authority = re.split(r"[/?#]", rest, maxsplit=1)[0]
    host = authority.rpartition("@")[2]
    if host.startswith("["):  # IPv6 literal
        host = host.partition("]")[0] + "]" if "]" in host else host
    else:
        host = host.partition(":")[0]
    return f"{scheme}://{host}"


def _reduce_urls(text: str) -> str:
    out: list[str] = []
    pos = 0
    for match in _URL.finditer(text):
        out.append(text[pos : match.start()])
        end = match.end()
        if (
            text[end : end + 1] == "["
        ):  # an unclosed bracket: not a URL we can reduce, so fail closed
            scheme = match.group(0).partition("://")[0]
            out.append(f"{scheme}://{REDACTED}")
            return "".join(out)
        out.append(_reduce_url(match))
        pos = end
    out.append(text[pos:])
    return "".join(out)


def _scrub(text: str) -> str:
    for secret in _known_secrets():
        text = text.replace(secret, REDACTED)
    text = _reduce_urls(text)
    text = _SENSITIVE.sub(REDACTED, text)
    text = _BASE64_PADDED.sub(REDACTED, text)
    return _OPAQUE.sub(REDACTED, text)


def _raw_cap(text: str) -> str:
    """Cut raw text before any pattern runs, dropping a trailing partial word."""
    if len(text) <= RAW_LINK_CHARS:
        return text
    head = text[:RAW_LINK_CHARS]
    cut = max(head.rfind(" "), head.rfind("\n"), head.rfind("\t"))
    return (head[:cut] if cut > 0 else "") + TRUNCATION_MARKER


def _cap(text: str) -> str:
    if len(text) <= MAX_CHARS:
        return text
    return text[: MAX_CHARS - len(TRUNCATION_MARKER)] + TRUNCATION_MARKER


def _type_name(exc: object) -> str:
    try:
        return type(exc).__name__
    except Exception:
        return "Exception"


def _link_text(exc: BaseException) -> str:
    name = _type_name(exc)
    try:
        detail = str(exc)
    except Exception:
        return name
    detail = _raw_cap(detail)
    return f"{name}: {detail}" if detail else name


def _chain(exc: BaseException) -> list[BaseException]:
    """The exception and its causes and contexts, each once, at most MAX_CHAIN_DEPTH links."""
    seen: set[int] = set()
    found: list[BaseException] = []
    queue = [exc]
    while queue and len(found) < MAX_CHAIN_DEPTH:
        current = queue.pop(0)
        if id(current) in seen:
            continue
        seen.add(id(current))
        found.append(current)
        for linked in (current.__cause__, current.__context__):
            if isinstance(linked, BaseException):
                queue.append(linked)
    return found


def _frames(exc: BaseException) -> str:
    lines = []
    for frame, lineno in traceback.walk_tb(exc.__traceback__):
        path = frame.f_code.co_filename.replace("\\", "/").split("/")
        lines.append(f"{'/'.join(path[-2:])}:{lineno} in {frame.f_code.co_name}")
    return "\n".join(lines[-MAX_FRAMES:])


def scrub_exception(exc: BaseException) -> ScrubbedException:
    """The export-safe type name, message (chain included), and frame summary of an exception.

    Never raises: anything unexpected yields the exception type name alone.
    """
    name = _type_name(exc)
    try:
        message = _cap(_scrub(" <- ".join(_link_text(link) for link in _chain(exc))))
    except Exception:
        message = name
    try:
        stacktrace = _cap(_scrub(_frames(exc)))
    except Exception:
        stacktrace = ""
    try:
        name = _scrub(name)
    except Exception:
        pass
    return ScrubbedException(name, message, stacktrace)
