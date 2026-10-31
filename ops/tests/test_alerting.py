"""Static checks of the Grafana alert provisioning files (no Docker, no network).

Run from the repository root:
    uv run --no-project --with pytest --with pyyaml pytest ops/tests
"""

import re
from pathlib import Path

import pytest
import yaml

OPS = Path(__file__).resolve().parent.parent
ROOT = OPS.parent
ALERTING = OPS / "grafana" / "provisioning" / "alerting"


def load(name):
    return yaml.safe_load((ALERTING / name).read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def rule():
    rules = [r for g in load("rules.yaml")["groups"] for r in g["rules"]]
    assert len(rules) >= 1
    return next(r for r in rules if r["uid"] == "podclatch-5xx-ratio")


def test_all_files_parse():
    files = sorted(ALERTING.glob("*.yaml"))
    assert {f.name for f in files} >= {"rules.yaml", "contact-points.yaml", "notification-policies.yaml"}
    for f in files:
        assert yaml.safe_load(f.read_text(encoding="utf-8"))["apiVersion"] == 1


def test_rule_pending_period_and_no_data(rule):
    assert rule["for"] == "5m"
    assert rule["noDataState"] == "OK"
    assert rule["condition"] == "C"


def test_rule_queries_5xx_ratio_with_minimum_requests(rule):
    query = next(d for d in rule["data"] if d["refId"] == "A")
    assert query["datasourceUid"] == "prometheus"
    expr = query["model"]["expr"]
    assert 'http_response_status_class="5xx"' in expr
    assert "podclatch_http_server_requests_total" in expr
    assert "[5m]" in expr
    assert ">= 3" in expr
    assert query["relativeTimeRange"]["from"] == 300


def test_rule_labels_and_annotations(rule):
    assert rule["labels"]["service"] == "podclatch-server"
    assert rule["labels"]["severity"]
    assert rule["annotations"]["summary"]
    description = rule["annotations"]["description"]
    assert "5 minutes" in description and "5%" in description


def test_contact_point_is_the_local_receiver():
    receivers = [r for cp in load("contact-points.yaml")["contactPoints"] for r in cp["receivers"]]
    assert [r["type"] for r in receivers] == ["webhook"]
    assert receivers[0]["settings"]["url"] == "http://alert-receiver:8080/"
    assert not receivers[0]["settings"].get("authorization_credentials")


def test_policy_routes_to_the_contact_point():
    policy = load("notification-policies.yaml")["policies"][0]
    names = [cp["name"] for cp in load("contact-points.yaml")["contactPoints"]]
    assert policy["receiver"] in names


def test_compose_mounts_provisioning_read_only_and_lgtm_does_not_wait_for_receiver():
    compose = yaml.safe_load((ROOT / "docker-compose.yaml").read_text(encoding="utf-8"))
    mounts = compose["services"]["lgtm"]["volumes"]
    assert any(
        m.startswith("./ops/grafana/provisioning/alerting:") and m.endswith("/provisioning/alerting:ro")
        for m in mounts
    )
    assert "depends_on" not in compose["services"]["lgtm"]
    assert compose["services"]["alert-receiver"]["ports"][0].startswith("127.0.0.1:")


TOKEN_LIKE = [
    re.compile(r"(?i)\b(bearer|authorization|api[_-]?key|password|secret|token)\b\s*[:=]\s*\S+"),
    re.compile(r"\b(ghp|github_pat|glsa|AKIA)[A-Za-z0-9_]{8,}"),
    re.compile(r"[A-Za-z0-9+/_-]{40,}"),
]


def test_no_token_like_values_or_feed_urls():
    for f in list(ALERTING.glob("*.yaml")) + [OPS / "alert-receiver" / "receiver.py"]:
        text = f.read_text(encoding="utf-8")
        for pattern in TOKEN_LIKE:
            assert not pattern.search(text), f"{f.name} matches {pattern.pattern}"
        assert not re.search(r"https?://(?!alert-receiver)\S+", text), f"{f.name} has an external URL"
