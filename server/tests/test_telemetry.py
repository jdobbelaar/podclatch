"""Traces, metrics, and JSON logs (US-36a, issue #33). No network: in-memory exporters only."""

import io
import json
import logging
import time

import pytest
from fastapi.testclient import TestClient
from opentelemetry.sdk._logs import LoggerProvider
from opentelemetry.sdk._logs.export import InMemoryLogRecordExporter, SimpleLogRecordProcessor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import InMemoryMetricReader
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from us25_helpers import AUTH, SECRET_PARTS, SECRET_URL, TOKEN, StubFetcher, ep, feed_of

from podclatch_server import telemetry
from podclatch_server.app import create_app
from podclatch_server.config import Settings, load_settings
from podclatch_server.feeds import FeedUnreachableError, ParsedEpisode
from podclatch_server.logging_setup import ACCESS_LOGGER, JsonFormatter, RedactQueryFilter
from podclatch_server.routes.feeds import get_fetcher
from podclatch_server.scheduler import RefreshScheduler
from podclatch_server.store import store_for_app

TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
TRACE_ID = "0af7651916cd43dd8448eb211c80319c"
URL_A = "https://example.com/a.xml"
TITLE = "Zanzibar Chronicles Episode Title"
DESCRIPTION = "Marmalade Hippopotamus show notes"
STATE_FIELDS = (
    "tracer_provider",
    "meter_provider",
    "tracer",
    "request_count",
    "request_duration",
    "refresh_runs",
    "feeds_refreshed",
    "logger_provider",
    "log_handler",
)


class Capture:
    def __init__(self) -> None:
        self.spans = InMemorySpanExporter()
        self.reader = InMemoryMetricReader()
        self.log = io.StringIO()
        self.logs = InMemoryLogRecordExporter()

    def exported_logs(self) -> list:
        return [r.log_record for r in self.logs.get_finished_logs()]

    def finished(self, name: str | None = None):
        spans = self.spans.get_finished_spans()
        return [s for s in spans if name is None or s.name == name]

    def lines(self) -> list[dict]:
        return [json.loads(line) for line in self.log.getvalue().splitlines() if line]

    def points(self, metric_name: str) -> list:
        data = self.reader.get_metrics_data()
        found = []
        for resource in data.resource_metrics if data else []:
            for scope in resource.scope_metrics:
                for metric in scope.metrics:
                    if metric.name == metric_name:
                        found.extend(metric.data.data_points)
        return found

    def everything_exported(self) -> str:
        """All exported spans, metrics, and log lines as one searchable string."""
        spans = "".join(s.to_json() for s in self.spans.get_finished_spans())
        data = self.reader.get_metrics_data()
        metrics = data.to_json() if data else ""
        logs = "".join(r.to_json() for r in self.logs.get_finished_logs())
        return spans + metrics + logs + self.log.getvalue()


@pytest.fixture
def cap(monkeypatch) -> Capture:
    for name in STATE_FIELDS:  # restored at teardown, so other tests keep their providers
        monkeypatch.setattr(telemetry._state, name, getattr(telemetry._state, name))
    capture = Capture()
    tracer_provider = TracerProvider()
    tracer_provider.add_span_processor(SimpleSpanProcessor(capture.spans))
    logger_provider = LoggerProvider()
    logger_provider.add_log_record_processor(SimpleLogRecordProcessor(capture.logs))
    telemetry.install(
        tracer_provider, MeterProvider(metric_readers=[capture.reader]), logger_provider
    )
    handler = logging.StreamHandler(capture.log)
    handler.setFormatter(JsonFormatter())
    logger = logging.getLogger("podclatch_server")
    logger.addHandler(handler)
    access = logging.getLogger(ACCESS_LOGGER)
    access.addHandler(handler)
    old_level = access.level
    access.setLevel(logging.INFO)
    yield capture
    telemetry._detach_log_handler()
    access.setLevel(old_level)
    access.removeHandler(handler)
    logger.removeHandler(handler)


@pytest.fixture
def fetcher() -> StubFetcher:
    return StubFetcher()


@pytest.fixture
def app(cap, fetcher):
    app = create_app(Settings(token=TOKEN, test_mode=True, log_level="DEBUG"))
    app.dependency_overrides[get_fetcher] = lambda: fetcher
    return app


@pytest.fixture
def client(app) -> TestClient:
    return TestClient(app, headers=AUTH)


# --- traces --------------------------------------------------------------------------------------


def test_request_produces_a_server_span_with_the_route_template(cap, client):
    feed_id = client.post("/v1/feeds", json={"feedUrl": URL_A}).json()["id"]
    cap.spans.clear()
    assert client.get(f"/v1/feeds/{feed_id}/episodes").status_code == 200
    (span,) = cap.finished()
    assert span.name == "GET /v1/feeds/{id}/episodes"
    assert span.attributes["http.route"] == "/v1/feeds/{id}/episodes"
    assert span.attributes["http.response.status_code"] == 200
    assert span.parent is None


def test_incoming_traceparent_is_continued(cap, client):
    client.get("/v1/feeds", headers={"traceparent": TRACEPARENT})
    (span,) = cap.finished()
    assert format(span.context.trace_id, "032x") == TRACE_ID
    assert format(span.parent.span_id, "016x") == "b7ad6b7169203331"


def test_no_traceparent_starts_a_new_trace_and_garbage_is_ignored(cap, client):
    client.get("/v1/feeds")
    client.get("/v1/feeds", headers={"traceparent": "not-a-header"})
    first, second = cap.finished()
    assert first.parent is None and second.parent is None
    assert first.context.trace_id != second.context.trace_id


def test_healthz_is_not_traced_or_counted(cap, client):
    assert client.get("/healthz").status_code == 200
    assert cap.finished() == []
    assert cap.points("podclatch.http.server.requests") == []


def test_error_responses_and_unknown_paths_use_bounded_route_labels(cap, client):
    client.get("/no/such/path/CANARY")
    client.get("/v1/feeds/999/episodes")
    names = {s.name for s in cap.finished()}
    assert "GET unmatched" in names
    assert "CANARY" not in cap.everything_exported()


def test_refresh_has_child_spans_per_feed_fetch(cap, client, fetcher):
    fetcher.results[URL_A] = feed_of(ep(1))
    client.post("/v1/feeds", json={"feedUrl": URL_A})
    cap.spans.clear()
    client.post("/v1/refresh", headers={"traceparent": TRACEPARENT})
    request = cap.finished("POST /v1/refresh")[0]
    (run,) = cap.finished("refresh")
    (fetch,) = cap.finished("fetch feed")
    assert run.parent.span_id == request.context.span_id
    assert fetch.parent.span_id == run.context.span_id
    assert {s.context.trace_id for s in (request, run, fetch)} == {request.context.trace_id}
    assert format(request.context.trace_id, "032x") == TRACE_ID
    assert set(fetch.attributes) <= {"feed.id", "error.type"}


def test_failed_fetch_marks_the_span_with_the_exception_type_only(cap, client, fetcher):
    client.post("/v1/feeds", json={"feedUrl": URL_A})
    fetcher.results[URL_A] = FeedUnreachableError(f"could not reach {SECRET_URL}")
    cap.spans.clear()
    client.post("/v1/refresh")
    (fetch,) = cap.finished("fetch feed")
    assert fetch.attributes["error.type"] == "FeedUnreachableError"
    assert fetch.status.status_code.name == "ERROR"
    assert not fetch.events


# --- metrics -------------------------------------------------------------------------------------


def test_request_counter_and_latency_histogram_are_labelled(cap, client):
    client.get("/v1/feeds")
    client.get("/v1/feeds")
    client.get("/v1/feeds/999/episodes")
    counts = {
        (p.attributes["http.route"], p.attributes["http.response.status_class"]): p.value
        for p in cap.points("podclatch.http.server.requests")
    }
    assert counts == {("/v1/feeds", "2xx"): 2, ("/v1/feeds/{id}/episodes", "4xx"): 1}
    histogram = cap.points("podclatch.http.server.duration")
    assert sum(p.count for p in histogram) == 3
    assert all(p.attributes["http.request.method"] == "GET" for p in histogram)


def test_refresh_counters_for_a_successful_and_a_failing_refresh(cap, client, fetcher):
    fetcher.results[URL_A] = feed_of(ep(1))
    client.post("/v1/feeds", json={"feedUrl": URL_A})
    client.post("/v1/refresh")
    runs = {p.attributes["result"]: p.value for p in cap.points("podclatch.refresh.runs")}
    assert runs == {"success": 1}
    assert [p.value for p in cap.points("podclatch.refresh.feeds")] == [1]

    fetcher.results[URL_A] = FeedUnreachableError("down")
    client.post("/v1/refresh")
    runs = {p.attributes["result"]: p.value for p in cap.points("podclatch.refresh.runs")}
    assert runs == {"success": 1, "failure": 1}
    assert [p.value for p in cap.points("podclatch.refresh.feeds")] == [1]  # unchanged


# --- logs ----------------------------------------------------------------------------------------


def test_json_log_line_and_span_share_the_trace_id(cap, client, fetcher):
    client.post("/v1/feeds", json={"feedUrl": URL_A})
    cap.log.truncate(0)
    cap.log.seek(0)
    client.post("/v1/refresh", headers={"traceparent": TRACEPARENT})
    lines = [line for line in cap.lines() if line["logger"].startswith("podclatch_server")]
    assert lines
    for line in lines:
        assert {"timestamp", "level", "message", "logger"} <= set(line)
    refreshed = [line for line in lines if line["message"].startswith("refreshed feed")]
    manual = [line for line in lines if line["message"].startswith("manual refresh")]
    assert refreshed and manual  # one from the worker thread, one from the request thread
    server_span = cap.finished("POST /v1/refresh")[0]
    for line in refreshed + manual:
        assert line["trace_id"] == TRACE_ID == format(server_span.context.trace_id, "032x")
    spans_by_id = {format(s.context.span_id, "016x"): s for s in cap.finished()}
    assert spans_by_id[manual[0]["span_id"]].name == "POST /v1/refresh"
    assert spans_by_id[refreshed[0]["span_id"]].name == "refresh"


def test_log_outside_a_span_has_no_trace_id(cap):
    logging.getLogger("podclatch_server.x").warning("plain")
    (line,) = cap.lines()
    assert line["message"] == "plain" and line["level"] == "WARNING"
    assert "trace_id" not in line and "span_id" not in line


def test_exception_text_is_never_logged(cap):
    try:
        raise ValueError(f"DETAIL: Key (feed_url)=({SECRET_URL}) already exists")
    except ValueError:
        logging.getLogger("podclatch_server.x").exception("boom")
    (line,) = cap.lines()
    assert line["exception_type"] == "ValueError"
    assert "SECRET123" not in cap.log.getvalue()


def test_uvicorn_access_handlers_get_the_json_format():
    access = logging.getLogger(ACCESS_LOGGER)
    handler = logging.StreamHandler(io.StringIO())
    access.addHandler(handler)
    try:
        create_app(Settings(token=TOKEN, test_mode=True))
        assert isinstance(handler.formatter, JsonFormatter)
        assert any(isinstance(f, RedactQueryFilter) for f in access.filters)
    finally:
        access.removeHandler(handler)


# --- scheduler -----------------------------------------------------------------------------------


def test_scheduled_refresh_runs_in_a_trace(cap, app, client, fetcher):
    fetcher.results[URL_A] = feed_of(ep(1))
    client.post("/v1/feeds", json={"feedUrl": URL_A})
    cap.spans.clear()
    cap.log.truncate(0)
    cap.log.seek(0)
    RefreshScheduler(store_for_app(app), fetcher, 1000).run_once()
    (root,) = cap.finished("scheduled refresh")
    (run,) = cap.finished("refresh")
    assert root.parent is None and run.parent.span_id == root.context.span_id
    trace_id = format(root.context.trace_id, "032x")
    messages = {
        line["message"].split(":")[0]: line["trace_id"]
        for line in cap.lines()
        if line["logger"] == "podclatch_server.scheduler"
    }
    assert messages["scheduled refresh"] == trace_id
    assert [p.value for p in cap.points("podclatch.refresh.feeds")] == [1]


# --- redaction (mandatory) -----------------------------------------------------------------------


def test_no_secret_reaches_any_span_metric_or_log_line(cap, app, client, fetcher):
    secret_url = SECRET_URL + "&q=zebra-search-term"
    episode = ParsedEpisode(
        guid="g-1",
        title=TITLE,
        description=DESCRIPTION,
        audio_url="https://example.com/audio/1.mp3?sig=AUDIOSECRET",
        published_at=1_700_000_000_000,
        duration_sec=60,
        artwork_url=None,
    )
    fetcher.results[secret_url] = feed_of(episode)
    failing = "https://example.com/down.xml?token=FAILSECRET"
    fetcher.results[failing] = FeedUnreachableError(f"could not reach {failing} {TITLE}")

    created = client.post("/v1/feeds", json={"feedUrl": secret_url})
    assert created.status_code == 201
    client.post("/v1/feeds", json={"feedUrl": failing})
    feed_id = created.json()["id"]
    assert client.post("/v1/refresh", headers={"traceparent": TRACEPARENT}).status_code == 200
    client.post(f"/v1/feeds/{feed_id}/refresh")
    client.get(f"/v1/feeds/{feed_id}/episodes?token=QUERYSECRET")
    client.get("/v1/search", params={"term": "zebra-search-term"})
    client.get("/v1/feeds", headers={"Authorization": "Bearer wrong-token-xyz"})
    RefreshScheduler(store_for_app(app), fetcher, 1000).run_once()
    # What uvicorn would log for a request, through the real filter and format.
    logging.getLogger(ACCESS_LOGGER).info(
        '%s - "%s %s HTTP/%s" %d', "c:1", "GET", "/v1/search?term=zebra-search-term", "1.1", 200
    )

    exported = cap.everything_exported()
    assert "POST /v1/refresh" in exported and "podclatch.refresh.runs" in exported
    forbidden = [
        TOKEN,
        "wrong-token-xyz",
        "Bearer",
        secret_url,
        SECRET_URL,
        *SECRET_PARTS,
        "zebra-search",
        "QUERYSECRET",
        "FAILSECRET",
        "AUDIOSECRET",
        TITLE,
        DESCRIPTION,
        "Zanzibar",
        "Marmalade",
    ]
    for text in forbidden:
        assert text not in exported, text
    assert any("refreshed feed" in r.body for r in cap.exported_logs())
    for record in cap.exported_logs():
        assert set(record.attributes) <= ALLOWED_LOG_ATTRIBUTE_KEYS
    for span in cap.finished():
        assert all(
            key not in span.attributes
            for key in (
                "http.url",
                "url.full",
                "url.query",
                "http.target",
                "http.request.header.authorization",
            )
        )


# --- logs over OTLP (US-36d) ---------------------------------------------------------------------
ALLOWED_LOG_ATTRIBUTE_KEYS = {"logger.name", "exception.type"}


def exported_handlers() -> list:
    return [
        h
        for name in telemetry.EXPORTED_LOGGERS
        for h in logging.getLogger(name).handlers
        if isinstance(h, telemetry.OtlpLogHandler)
    ]


@pytest.fixture
def app_log_level():
    logger = logging.getLogger("podclatch_server")
    old = logger.level
    yield
    logger.setLevel(old)


def test_exported_log_record_has_message_severity_and_logger_name(cap):
    logging.getLogger("podclatch_server.x").warning("hello %s", "world")
    (record,) = cap.exported_logs()
    assert record.body == "hello world"
    assert record.severity_text == "WARN" and record.severity_number.name == "WARN"
    assert record.attributes["logger.name"] == "podclatch_server.x"


def test_record_inside_a_request_span_has_the_same_ids_as_the_stderr_line(cap, client):
    client.post("/v1/feeds", json={"feedUrl": URL_A})
    cap.log.truncate(0)
    cap.log.seek(0)
    cap.logs.clear()
    client.post("/v1/refresh", headers={"traceparent": TRACEPARENT})
    stderr = [x for x in cap.lines() if x["logger"].startswith("podclatch_server")]
    exported = [r for r in cap.exported_logs() if r.attributes["logger.name"] != ACCESS_LOGGER]
    assert stderr and len(exported) == len(stderr)
    key = lambda x: x["message"] if isinstance(x, dict) else x.body  # noqa: E731
    for line, record in zip(sorted(stderr, key=key), sorted(exported, key=key), strict=True):
        assert line["message"] == record.body
        assert format(record.trace_id, "032x") == line["trace_id"] == TRACE_ID
        assert format(record.span_id, "016x") == line["span_id"]


def test_record_outside_a_span_is_exported_without_ids(cap):
    logging.getLogger("podclatch_server.x").warning("startup")
    (record,) = cap.exported_logs()
    assert not record.trace_id and not record.span_id


def test_only_the_app_and_access_loggers_are_exported(cap):
    logging.getLogger("podclatch_server.deep.x").warning("app")
    logging.getLogger(ACCESS_LOGGER).info("access")
    logging.getLogger("opentelemetry.sdk.x").warning("sdk")
    logging.getLogger("httpx").warning("lib")
    logging.getLogger().warning("root")
    assert [r.body for r in cap.exported_logs()] == ["app", "access"]


def test_log_level_follows_log_level_setting(cap, app_log_level):
    create_app(Settings(token=TOKEN, test_mode=True, log_level="WARNING"))
    log = logging.getLogger("podclatch_server.x")
    log.info("quiet")
    log.warning("loud")
    assert [r.body for r in cap.exported_logs()] == ["loud"]
    assert [x["message"] for x in cap.lines()] == ["loud"]
    cap.logs.clear()
    create_app(Settings(token=TOKEN, test_mode=True, log_level="DEBUG"))
    log.debug("detail")
    (record,) = cap.exported_logs()
    assert record.body == "detail" and record.severity_text == "DEBUG"


def test_access_record_is_exported_without_the_query_string(cap, app):
    logging.getLogger(ACCESS_LOGGER).info(
        '%s - "%s %s HTTP/%s" %d', "c:1", "GET", "/v1/search?q=SECRET123", "1.1", 200
    )
    (record,) = cap.exported_logs()
    assert "/v1/search" in record.body and "SECRET123" not in record.body


def test_exception_text_is_not_exported(cap):
    try:
        raise ValueError(f"DETAIL: Key (feed_url)=({SECRET_URL}) already exists")
    except ValueError:
        logging.getLogger("podclatch_server.x").exception("boom")
    (record,) = cap.exported_logs()
    assert record.attributes["exception.type"] == "ValueError"
    assert "exception.message" not in record.attributes
    assert "exception.stacktrace" not in record.attributes
    assert "SECRET123" not in cap.everything_exported()


def test_args_and_extra_are_not_exported_as_attributes(cap):
    logging.getLogger("podclatch_server.x").warning("a %s", "b", extra={"token": "EXTRASECRET"})
    (record,) = cap.exported_logs()
    assert set(record.attributes) <= ALLOWED_LOG_ATTRIBUTE_KEYS
    assert set(telemetry.LOG_ATTRIBUTE_KEYS) == ALLOWED_LOG_ATTRIBUTE_KEYS
    assert "EXTRASECRET" not in cap.everything_exported()


def test_configure_with_endpoint_exports_to_v1_logs_and_shutdown_flushes(
    monkeypatch, app_log_level
):
    logging.getLogger("podclatch_server").setLevel(logging.INFO)
    for name in STATE_FIELDS:
        monkeypatch.setattr(telemetry._state, name, None)
    memory = InMemoryLogRecordExporter()
    made = {}

    def fake_exporter(**kwargs):
        made.update(kwargs)
        return memory

    monkeypatch.setattr(telemetry, "OTLPLogExporter", fake_exporter)
    try:
        telemetry.configure("svc", "http://collector:4318/")
        assert made == {"endpoint": "http://collector:4318/v1/logs", "timeout": 5}
        telemetry.configure("svc", "http://collector:4318/")  # second call keeps the first
        assert len(exported_handlers()) == 2  # one per exported logger, not four
        logging.getLogger("podclatch_server.x").warning("buffered")
        assert memory.get_finished_logs() == ()  # batched, not yet sent
        resource = telemetry._state.logger_provider.resource
        assert resource.attributes["service.name"] == "svc"
    finally:
        telemetry.shutdown()
    assert exported_handlers() == []
    bodies = [r.log_record.body for r in memory.get_finished_logs()]
    assert bodies.count("buffered") == 1 and "telemetry export enabled" in bodies


def test_install_twice_exports_a_record_once(cap):
    other = InMemoryLogRecordExporter()
    provider = LoggerProvider()
    provider.add_log_record_processor(SimpleLogRecordProcessor(other))
    telemetry.install(telemetry._state.tracer_provider, telemetry._state.meter_provider, provider)
    logging.getLogger("podclatch_server.x").warning("once")
    assert len(exported_handlers()) == 2
    assert len(other.get_finished_logs()) == 1 and cap.exported_logs() == []


def test_no_endpoint_adds_no_log_handler_or_exporter(monkeypatch):
    for name in STATE_FIELDS:
        monkeypatch.setattr(telemetry._state, name, None)
    before = {n: list(logging.getLogger(n).handlers) for n in telemetry.EXPORTED_LOGGERS}
    telemetry.configure("svc", None)
    try:
        assert telemetry._state.logger_provider is None and telemetry._state.log_handler is None
        after = {n: list(logging.getLogger(n).handlers) for n in telemetry.EXPORTED_LOGGERS}
        assert after == before
    finally:
        telemetry.shutdown()


# --- configuration -------------------------------------------------------------------------------


def test_settings_read_the_standard_otel_variables():
    default = load_settings({}, test_mode=True)
    assert default.otel_endpoint is None and default.otel_service_name == "podclatch-server"
    configured = load_settings(
        {"OTEL_EXPORTER_OTLP_ENDPOINT": " http://collector:4318 ", "OTEL_SERVICE_NAME": "pc"},
        test_mode=True,
    )
    assert configured.otel_endpoint == "http://collector:4318"
    assert configured.otel_service_name == "pc"


def test_no_endpoint_means_no_exporter_and_requests_still_work(monkeypatch):
    for name in STATE_FIELDS:
        monkeypatch.setattr(telemetry._state, name, None)
    app = create_app(Settings(token=TOKEN, test_mode=True))
    with TestClient(app, headers=AUTH) as client:
        assert client.get("/v1/feeds").status_code == 200
    assert telemetry._state.tracer_provider._active_span_processor._span_processors == ()


def test_unreachable_collector_does_not_slow_or_break_requests(monkeypatch):
    for name in STATE_FIELDS:
        monkeypatch.setattr(telemetry._state, name, None)
    app = create_app(Settings(token=TOKEN, test_mode=True, otel_endpoint="http://127.0.0.1:9"))
    client = TestClient(app, headers=AUTH)
    started = time.perf_counter()
    for _ in range(5):
        assert client.get("/v1/feeds").status_code == 200
    assert time.perf_counter() - started < 2.0
