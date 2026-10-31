"""OpenTelemetry setup: traces and metrics for the server (US-36a).

Everything recorded here is chosen by hand: span names are `METHOD route-template`, attributes are
method, route template, status code, Feed ids, and counts. Request and response bodies, headers,
query strings, and feed URLs are never recorded (spec decision #27), which is why the HTTP spans
come from `TelemetryMiddleware` below rather than from a library that records `http.url`.

Providers are owned by this module (never the OpenTelemetry globals), so tests can swap in
in-memory ones with `install`. With no `OTEL_EXPORTER_OTLP_ENDPOINT` the providers have no
exporter: spans still get ids (so logs carry a `trace_id`) but nothing leaves the process.
"""

import logging
import time
from collections.abc import Awaitable, Callable, MutableMapping
from typing import Any

from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import MetricReader, PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import SpanKind, Status, StatusCode
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

logger = logging.getLogger(__name__)

SCOPE = "podclatch_server"
UNTRACED_PATHS = frozenset({"/healthz"})
EXPORT_TIMEOUT_SECONDS = 5
METRIC_EXPORT_INTERVAL_MS = 15_000

Scope = MutableMapping[str, Any]
Message = MutableMapping[str, Any]
Receive = Callable[[], Awaitable[Message]]
Send = Callable[[Message], Awaitable[None]]

_propagator = TraceContextTextMapPropagator()


class _State:
    tracer_provider: TracerProvider | None = None
    meter_provider: MeterProvider | None = None
    tracer: trace.Tracer = trace.NoOpTracer()
    request_count: Any = None
    request_duration: Any = None
    refresh_runs: Any = None
    feeds_refreshed: Any = None


_state = _State()


def install(tracer_provider: TracerProvider, meter_provider: MeterProvider) -> None:
    """Make these providers the ones the server records into (tests pass in-memory ones)."""
    _state.tracer_provider = tracer_provider
    _state.meter_provider = meter_provider
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
    install(tracer_provider, MeterProvider(resource=resource, metric_readers=readers))
    if endpoint:
        logger.info("telemetry export enabled")


def shutdown() -> None:
    """Flush and stop the exporters (called when the app shuts down)."""
    for provider in (_state.tracer_provider, _state.meter_provider):
        if provider is not None:
            try:
                provider.shutdown()
            except Exception as exc:
                logger.warning("telemetry shutdown failed: %s", type(exc).__name__)
    _state.tracer_provider = _state.meter_provider = None
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
