"""Configuration, read from environment variables (spec decision #25)."""

import math
import os
from collections.abc import Mapping
from dataclasses import dataclass, field

from podclatch_server.db import SUPPORTED_SCHEMES, is_supported_url

DEFAULT_DATABASE_URL = "sqlite:///./podclatch.db"
DEFAULT_REFRESH_INTERVAL_HOURS = 3.0
DEFAULT_LOG_LEVEL = "INFO"
DEFAULT_ITUNES_SEARCH_URL = "https://itunes.apple.com/search"
DEFAULT_SERVICE_NAME = "podclatch-server"
LOG_LEVELS = ("CRITICAL", "ERROR", "WARNING", "INFO", "DEBUG")
TRUE_VALUES = ("1", "true", "yes", "on")
FALSE_VALUES = ("", "0", "false", "no", "off")


class ConfigError(ValueError):
    """Invalid configuration. Messages name the variable, never its value."""


@dataclass(frozen=True)
class Settings:
    database_url: str = field(default=DEFAULT_DATABASE_URL, repr=False)
    token: str | None = field(default=None, repr=False)
    refresh_interval_hours: float = DEFAULT_REFRESH_INTERVAL_HOURS
    log_level: str = DEFAULT_LOG_LEVEL
    test_mode: bool = False
    itunes_search_url: str = field(default=DEFAULT_ITUNES_SEARCH_URL, repr=False)
    # Telemetry (standard OpenTelemetry variables). No endpoint means nothing is exported.
    otel_endpoint: str | None = None
    otel_service_name: str = DEFAULT_SERVICE_NAME

    def __post_init__(self) -> None:
        if not self.database_url:
            raise ConfigError("DATABASE_URL must not be empty")
        if not is_supported_url(self.database_url):
            schemes = ", ".join(f"{scheme}://" for scheme in SUPPORTED_SCHEMES)
            raise ConfigError(f"DATABASE_URL must start with one of: {schemes}")
        if not math.isfinite(self.refresh_interval_hours) or self.refresh_interval_hours <= 0:
            raise ConfigError("REFRESH_INTERVAL_HOURS must be a positive number")
        if self.log_level not in LOG_LEVELS:
            raise ConfigError(f"LOG_LEVEL must be one of {', '.join(LOG_LEVELS)}")
        if not self.itunes_search_url.startswith(("http://", "https://")):
            raise ConfigError("ITUNES_SEARCH_URL must start with http:// or https://")
        if self.otel_endpoint and not self.otel_endpoint.startswith(("http://", "https://")):
            raise ConfigError("OTEL_EXPORTER_OTLP_ENDPOINT must start with http:// or https://")
        if not self.otel_service_name:
            raise ConfigError("OTEL_SERVICE_NAME must not be empty")
        if not self.test_mode and not self.token:
            raise ConfigError("PODCLATCH_TOKEN must be set (it is required outside test mode)")


def _parse_refresh_interval(raw: str) -> float:
    try:
        return float(raw)
    except ValueError:
        raise ConfigError("REFRESH_INTERVAL_HOURS must be a positive number") from None


def _parse_test_mode(raw: str) -> bool:
    value = raw.strip().lower()
    if value in TRUE_VALUES:
        return True
    if value in FALSE_VALUES:
        return False
    raise ConfigError("PODCLATCH_TEST_MODE must be true or false")


def load_settings(env: Mapping[str, str] | None = None, test_mode: bool | None = None) -> Settings:
    """Build Settings from environment variables.

    `test_mode`, when given, overrides PODCLATCH_TEST_MODE. Test mode is off by default.
    """
    source = os.environ if env is None else env
    if test_mode is None:
        test_mode = _parse_test_mode(source.get("PODCLATCH_TEST_MODE", ""))
    return Settings(
        database_url=source.get("DATABASE_URL", DEFAULT_DATABASE_URL),
        token=source.get("PODCLATCH_TOKEN") or None,
        refresh_interval_hours=_parse_refresh_interval(
            source.get("REFRESH_INTERVAL_HOURS", str(DEFAULT_REFRESH_INTERVAL_HOURS))
        ),
        log_level=source.get("LOG_LEVEL", DEFAULT_LOG_LEVEL).upper(),
        test_mode=test_mode,
        itunes_search_url=source.get("ITUNES_SEARCH_URL", "").strip() or DEFAULT_ITUNES_SEARCH_URL,
        otel_endpoint=source.get("OTEL_EXPORTER_OTLP_ENDPOINT", "").strip() or None,
        otel_service_name=source.get("OTEL_SERVICE_NAME", "").strip() or DEFAULT_SERVICE_NAME,
    )
