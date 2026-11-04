"""The exception scrubber (issue #123): no secret survives, ordinary messages do. No network."""

import inspect

import pytest

from podclatch_server import scrub, telemetry
from podclatch_server.scrub import (
    MAX_CHAIN_DEPTH,
    MAX_CHARS,
    MAX_FRAMES,
    OPAQUE_MIN_CHARS,
    REDACTED,
    TRUNCATION_MARKER,
    scrub_exception,
)

FAKE_SERVER_TOKEN = "fake-server-token-9x"
FAKE_GATEWAY_TOKEN = "fake-gateway-token-7y"


@pytest.fixture(autouse=True)
def configured_tokens(monkeypatch):
    monkeypatch.setenv("PODCLATCH_TOKEN", FAKE_SERVER_TOKEN)
    monkeypatch.setenv("TELEMETRY_GATEWAY_TOKEN", FAKE_GATEWAY_TOKEN)
    monkeypatch.setattr(scrub, "_extra_secrets", set())


def message(exc: BaseException) -> str:
    return scrub_exception(exc).message


@pytest.mark.parametrize(
    ("text", "secret"),
    [
        ("could not reach https://example.com/feed.xml?token=SECRET123", "SECRET123"),
        ("could not reach https://user:pass@example.com/x", "pass"),
        ("could not reach https://example.com/private/SECRETPATH/feed.xml", "SECRETPATH"),
        ("got feed://example.com:8080/a/b#SECRETFRAG", "SECRETFRAG"),
        ("bad 'http://example.com/a?k=SECRET123' value", "SECRET123"),
        ("bad (file:///home/me/SECRETDIR/feed.xml) value", "SECRETDIR"),
        ("bad [https://example.com/SECRET123] value", "SECRET123"),
        ("sent Authorization: Token SECRET123 upstream", "SECRET123"),
        ("sent authorization=Digest SECRET123", "SECRET123"),
        ("header Bearer SECRET123 rejected", "SECRET123"),
        ("header Basic dXNlcjpwYXNz rejected", "dXNlcjpwYXNz"),
        (f"the token {FAKE_SERVER_TOKEN} was bad", FAKE_SERVER_TOKEN),
        (f"the gateway {FAKE_GATEWAY_TOKEN} was bad", FAKE_GATEWAY_TOKEN),
        (f"inside a longer string abc{FAKE_SERVER_TOKEN}def", FAKE_SERVER_TOKEN),
        ("key a1B2c3D4e5F6g7H8i9J0k1L2m3N4 leaked", "a1B2c3D4e5F6g7H8i9J0k1L2m3N4"),
        ("hex 0123456789abcdef0123456789abcdef leaked", "0123456789abcdef0123456789abcdef"),
        ("b64 c2VjcmV0LXZhbHVlLWhlcmU= leaked", "c2VjcmV0LXZhbHVlLWhlcmU="),
    ],
)
def test_secret_is_absent_from_the_message(text, secret):
    assert secret not in message(ValueError(text))
    assert secret not in scrub_exception(ValueError(text)).stacktrace


def test_url_is_reduced_to_scheme_and_host():
    out = message(ValueError("fetch https://user:pw@example.com:8443/a/b?token=SECRET123#f failed"))
    assert out == "ValueError: fetch https://example.com failed"
    assert message(ValueError("x feed://host.example/p")) == "ValueError: x feed://host.example"
    assert message(ValueError("x file:///etc/passwd")) == "ValueError: x file://"


def test_secret_in_a_cause_and_in_a_separate_context_is_not_exported():
    cause = RuntimeError("cause https://example.com/c?token=CAUSESECRET")
    context = KeyError("context https://example.com/d?token=CONTEXTSECRET")
    try:
        try:
            raise context
        except KeyError:
            raise ValueError("outer") from cause
    except ValueError as exc:
        out = message(exc)
    assert "CAUSESECRET" not in out and "CONTEXTSECRET" not in out
    assert "RuntimeError: cause https://example.com" in out
    assert "KeyError" in out


def test_secret_in_the_configured_token_nested_deep_in_the_chain_is_redacted():
    inner = ValueError(f"deep {FAKE_SERVER_TOKEN}")
    outer = RuntimeError("outer")
    outer.__cause__ = inner
    assert FAKE_SERVER_TOKEN not in message(outer)
    assert REDACTED in message(outer)


def test_registered_secret_is_redacted():
    scrub.register_secrets("tok-registered", None, "", "  ")
    assert "tok-registered" not in message(ValueError("x tok-registered y"))


def test_empty_or_unset_token_does_not_match_everything(monkeypatch):
    monkeypatch.setenv("PODCLATCH_TOKEN", "")
    monkeypatch.delenv("TELEMETRY_GATEWAY_TOKEN")
    assert message(ValueError("plain words here")) == "ValueError: plain words here"


def test_opaque_threshold_has_a_test_on_both_sides():
    below = "a1" * (OPAQUE_MIN_CHARS // 2) + "x"
    below = below[: OPAQUE_MIN_CHARS - 1]
    at = below + "z"
    assert len(below) == OPAQUE_MIN_CHARS - 1 and len(at) == OPAQUE_MIN_CHARS
    assert below in message(ValueError(f"id {below}"))
    assert at not in message(ValueError(f"id {at}"))


def test_ordinary_message_passes_through_unchanged():
    text = (
        "invalid <itunes:duration> value '1:2:3:4': expected SS, MM:SS, or H:MM:SS.fff "
        "(episode guid field missing)"
    )
    assert message(ValueError(text)) == f"ValueError: {text}"


def test_message_is_capped_with_a_visible_marker():
    out = message(ValueError("word " * 1000))
    assert len(out) == MAX_CHARS and out.endswith(TRUNCATION_MARKER)
    exact = ("w" * 9 + " ") * 200
    exact = exact[: MAX_CHARS - len("ValueError: ")]
    assert message(ValueError(exact)) == f"ValueError: {exact}"[:MAX_CHARS]


def test_cap_is_applied_after_scrubbing():
    out = message(ValueError(("https://example.com/p?x=1 ") * 200))
    assert len(out) <= MAX_CHARS
    assert "?x=1" not in out


def test_cyclic_chain_does_not_raise_or_loop():
    a, b = ValueError("a https://h.example/s?t=SECRET123"), KeyError("b")
    a.__cause__, b.__context__ = b, a
    out = message(a)
    assert "SECRET123" not in out and out.count("ValueError") == 1


def test_deep_chain_is_cut_at_the_depth_limit():
    exc = ValueError("link-0")
    for i in range(1, 50):
        nxt = ValueError(f"link-{i}")
        nxt.__cause__ = exc
        exc = nxt
    out = message(exc)
    assert out.count("ValueError") == MAX_CHAIN_DEPTH


def test_raising_str_and_odd_arguments_do_not_raise():
    class Bad(Exception):
        def __str__(self) -> str:
            raise RuntimeError("no")

    assert scrub_exception(Bad()).message == "Bad"
    assert scrub_exception(Bad()).type == "Bad"
    assert message(ValueError(b"bytes", None, 3)).startswith("ValueError")
    assert message(ValueError()) == "ValueError"
    inner = Bad()
    outer = ValueError("outer")
    outer.__cause__ = inner
    assert message(outer) == "ValueError: outer <- Bad"


def test_an_internal_failure_falls_back_to_the_type_name(monkeypatch):
    monkeypatch.setattr(scrub, "_scrub", lambda text: 1 / 0)
    result = scrub_exception(ValueError("x https://e.example/?t=SECRET123"))
    assert result.message == "ValueError" and result.stacktrace == ""


def raiser(depth: int) -> None:
    secret_local = "LOCALSECRET123"  # noqa: F841 - must never be exported
    if depth:
        raiser(depth - 1)
    raise ValueError("boom")


def test_stack_summary_is_module_function_and_line_only():
    try:
        raiser(2)
    except ValueError as exc:
        stack = scrub_exception(exc).stacktrace
    lines = stack.splitlines()
    assert lines[-1].startswith("tests/test_scrub.py:") and lines[-1].endswith(" in raiser")
    assert "LOCALSECRET123" not in stack and "secret_local" not in stack
    assert "raise ValueError" not in stack  # no source lines
    assert all(" in " in line and ":" in line for line in lines)


def test_stack_summary_keeps_only_the_innermost_frames():
    try:
        raiser(MAX_FRAMES + 10)
    except ValueError as exc:
        lines = scrub_exception(exc).stacktrace.splitlines()
    assert len(lines) == MAX_FRAMES and lines[-1].endswith(" in raiser")


def test_single_public_scrubbing_function_and_every_export_path_calls_it():
    public = [
        name
        for name, obj in vars(scrub).items()
        if inspect.isfunction(obj) and obj.__module__ == scrub.__name__ and not name.startswith("_")
    ]
    assert sorted(public) == ["register_secrets", "scrub_exception"]
    telemetry_source = inspect.getsource(telemetry)
    assert "scrub_exception(" in inspect.getsource(telemetry.OtlpLogHandler.emit)
    assert "scrub_exception(" in inspect.getsource(telemetry.mark_span_error)
    assert "record_exception=False" in telemetry_source
    from podclatch_server import refresh

    refresh_source = inspect.getsource(refresh)
    assert "mark_span_error(span, exc)" in refresh_source
    assert "add_event" not in refresh_source
