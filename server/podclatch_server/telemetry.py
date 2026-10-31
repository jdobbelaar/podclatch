"""OpenTelemetry setup: traces, metrics, and logs for the server (US-36a, US-36d).

Everything recorded here is chosen by hand: span names are `METHOD route-template`, attributes are
method, route template, status code, Feed ids, and counts. Request and response bodies, headers,
query strings, and feed URLs are never recorded (spec decision #27), which is why the HTTP spans
come from `TelemetryMiddleware` below rather than from a library that records `http.url`.

Providers are owned by this module (never the OpenTelemetry globals), so tests can swap in
in-memory ones with `install`. With no `OTEL_EXPORTER_OTLP_ENDPOINT` the providers have no
exporter: spans still get ids (so logs carry a `trace_id`) but nothing leaves the process.

Logs: with an endpoint, records of the `podclatch_server` and `uvicorn.access` loggers are also
exported over OTLP by `OtlpLogHandler`. The stock SDK `LoggingHandler` is not used because it
copies exception messages, stack traces, and every `extra` field (decision #27). The log API of
OpenTelemetry Python 1.45 still lives under the underscore modules `opentelemetry.sdk._logs` and
`opentelemetry._logs`; they are the only place it exists, and the pin in `pyproject.toml` is the
guard against a rename.
"""

import logging
import time
from collections.abc import Awaitable, Callable, MutableMapping
from typing import Any

from opentelemetry import trace
from opentelemetry._logs import SeverityNumber
from opentelemetry.context import get_current
from opentelemetry.exporter.otlp.proto.http._log_exporter import OTLPLogExporter
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk._logs import LoggerProvider
from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import MetricReader, PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import SpanKind, Status, StatusCode
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

logger = logging.getLogger(__name__)

SCOPE = "podclatch_server"
EXPORTED_LOGGERS = ("podclatch_server", "uvicorn.access")
LOG_ATTRIBUTE_KEYS = frozenset({"logger.name", "exception.type"})
UNTRACED_PATHS = frozenset({"/healthz"})
EXPORT_TIMEOUT_SECONDS = 5
METRIC_EXPORT_INTERVAL_MS = 15_000

Scope = MutableMapping[str, Any]
Message = MutableMapping[str, Any]
Receive = Callable[[], Awaitable[Message]]
Send = Callable[[Message], Awaitable[None]]

_propagator = TraceContextTextMapPropagator()

_SEVERITY = {
    logging.DEBUG: (SeverityNumber.DEBUG, "DEBUG"),
    logging.INFO: (SeverityNumber.INFO, "INFO"),
    logging.WARNING: (SeverityNumber.WARN, "WARN"),
    logging.ERROR: (SeverityNumber.ERROR, "ERROR"),
    logging.CRITICAL: (SeverityNumber.FATAL, "FATAL"),
}


class OtlpLogHandler(logging.Handler):
    """Hands a `logging` record to an OpenTelemetry LoggerProvider, copying only safe fields.

    Exported: the formatted message (the same text the stderr line has, after
    `RedactQueryFilter`), the severity, the active trace and span ids (taken from the current
    context, so nothing is invented outside a span), the logger name, and the exception type
    name. Never exported: exception message or stack trace, `args`, and `extra` fields.
    """

    def __init__(self, provider: LoggerProvider) -> None:
        super().__init__()
        self._provider = provider
        self._logger = provider.get_logger(SCOPE)

    def emit(self, record: logging.LogRecord) -> None:
        try:
            attributes: dict[str, str] = {"logger.name": record.name}
            if record.exc_info and record.exc_info[0] is not None:
                attributes["exception.type"] = record.exc_info[0].__name__
            number, text = _SEVERITY[min(max(record.levelno // 10 * 10, 10), 50)]
            self._logger.emit(
                timestamp=int(record.created * 1e9),
                context=get_current(),
                severity_number=number,
                severity_text=text,
                body=record.getMessage(),
                attributes=attributes,
            )
        except Exception:  # an export problem must never reach the app
            pass


class _State:
    tracer_provider: TracerProvider | None = None
    meter_provider: MeterProvider | None = None
    logger_provider: LoggerProvider | None = None
    log_handler: OtlpLogHandler | None = None
    tracer: trace.Tracer = trace.NoOpTracer()
    request_count: Any = None
    request_duration: Any = None
    refresh_runs: Any = None
    feeds_refreshed: Any = None


_state = _State()


def _detach_log_handler() -> None:
    handler = _state.log_handler
    if handler is not None:
        for name in EXPORTED_LOGGERS:
            logging.getLogger(name).removeHandler(handler)
    _state.log_handler = None


def install(
    tracer_provider: TracerProvider,
    meter_provider: MeterProvider,
    logger_provider: LoggerProvider | None = None,
) -> None:
    """Make these providers the ones the server records into (tests pass in-memory ones).

    With a `logger_provider`, one `OtlpLogHandler` is attached to the exported loggers
    (replacing any earlier one, so a record is never exported twice).
    """
    _state.tracer_provider = tracer_provider
    _state.meter_provider = meter_provider
    _detach_log_handler()
    _state.logger_provider = logger_provider
    if logger_provider is not None:
        _state.log_handler = OtlpLogHandler(logger_provider)
        for name in EXPORTED_LOGGERS:
            logging.getLogger(name).addHandler(_state.log_handler)
    _state.tracer = tracer_provider.get_tracer(SCOPE)
    meter = meter_provider.get_meter(SCOPE)
    _state.request_count = meter.create_counter(
        "podclatch.http.server.requests",
        unit="{request}",
        description="HTTP requests by method, route template, and status class",
    )
    _state.request_duration = meter.create_histogram(
        "podclatch.http.server.duration",
        unit="s",
        description="HTTP request latency by method, route template, and status class",
    )
    _state.refresh_runs = meter.create_counter(
        "podclatch.refresh.runs",
        unit="{run}",
        description="Refresh runs by result (success when no Feed failed, otherwise failure)",
    )
    _state.feeds_refreshed = meter.create_counter(
        "podclatch.refresh.feeds",
        unit="{feed}",
        description="Feeds refreshed successfully",
    )


def configure(service_name: str, endpoint: str | None) -> None:
    """Build and install providers; export over OTLP/HTTP only when `endpoint` is set.

    The exporters run on background threads with a short timeout, so an unreachable collector
    never slows a request or raises into the app. Calling this again keeps the first providers.
    """
    if _state.tracer_provider is not None:
        return
    resource = Resource.create({"service.name": service_name})
    tracer_provider = TracerProvider(resource=resource)
    readers: list[MetricReader] = []
    logger_provider: LoggerProvider | None = None
    if endpoint:
        base = endpoint.rstrip("/")
        tracer_provider.add_span_processor(
            BatchSpanProcessor(
                OTLPSpanExporter(endpoint=f"{base}/v1/traces", timeout=EXPORT_TIMEOUT_SECONDS)
            )
        )
        readers.append(
            PeriodicExportingMetricReader(
                OTLPMetricExporter(endpoint=f"{base}/v1/metrics", timeout=EXPORT_TIMEOUT_SECONDS),
                export_interval_millis=METRIC_EXPORT_INTERVAL_MS,
            )
        )
        logger_provider = LoggerProvider(resource=resource)
        logger_provider.add_log_record_processor(
            BatchLogRecordProcessor(
                OTLPLogExporter(endpoint=f"{base}/v1/logs", timeout=EXPORT_TIMEOUT_SECONDS)
            )
        )
    install(
        tracer_provider, MeterProvider(resource=resource, metric_readers=readers), logger_provider
    )
    if endpoint:
        logger.info("telemetry export enabled")


def shutdown() -> None:
    """Flush and stop the exporters (called when the app shuts down)."""
    _detach_log_handler()
    for provider in (_state.tracer_provider, _state.meter_provider, _state.logger_provider):
        if provider is not None:
            try:
                provider.shutdown()
            except Exception as exc:
                logger.warning("telemetry shutdown failed: %s", type(exc).__name__)
    _state.tracer_provider = _state.meter_provider = _state.logger_provider = None
    _state.tracer = trace.NoOpTracer()
    _state.request_count = _state.request_duration = None
    _state.refresh_runs = _state.feeds_refreshed = None


def get_tracer() -> trace.Tracer:
    return _state.tracer


def record_request(method: str, route: str, status: int, seconds: float) -> None:
    if _state.request_count is None:
        return
    attributes = {
        "http.request.method": method,
        "http.route": route,
        "http.response.status_class": f"{status // 100}xx",
    }
    _state.request_count.add(1, attributes)
    _state.request_duration.record(seconds, attributes)


def record_refresh(results: list[dict[str, Any]]) -> None:
    """Count one refresh run and the Feeds in it that refreshed."""
    if _state.refresh_runs is None:
        return
    ok = sum(1 for r in results if r["ok"])
    _state.refresh_runs.add(1, {"result": "success" if ok == len(results) else "failure"})
    if ok:
        _state.feeds_refreshed.add(ok)


class TelemetryMiddleware:
    """Pure ASGI middleware: one server span and the request metrics per HTTP request.

    The span continues the trace of an incoming W3C `traceparent` header, or starts a new one.
    Its name and `http.route` use the route template (`/v1/feeds/{id}`), never the raw path.
    `/healthz` is skipped entirely.
    """

    def __init__(self, app: Callable[[Scope, Receive, Send], Awaitable[None]]) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope.get("path") in UNTRACED_PATHS:
            await self.app(scope, receive, send)
            return
        method = scope["method"]
        carrier = {k.decode("latin-1").lower(): v.decode("latin-1") for k, v in scope["headers"]}
        parent = _propagator.extract(carrier)
        status = 500
        started = time.perf_counter()

        async def send_wrapper(message: Message) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
            await send(message)

        with get_tracer().start_as_current_span(
            method,
            context=parent,
            kind=SpanKind.SERVER,
            record_exception=False,
            set_status_on_exception=False,
        ) as span:
            try:
                await self.app(scope, receive, send_wrapper)
            except Exception as exc:
                span.set_attribute("error.type", type(exc).__name__)
                raise
            finally:
                route = getattr(scope.get("route"), "path", None) or "unmatched"
                span.update_name(f"{method} {route}")
                span.set_attribute("http.request.method", method)
                span.set_attribute("http.route", route)
                span.set_attribute("http.response.status_code", status)
                if status >= 500:
                    span.set_status(Status(StatusCode.ERROR))
                record_request(method, route, status, time.perf_counter() - started)
