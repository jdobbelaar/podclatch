"""Static checks of the Grafana alert provisioning files (no Docker, no network), plus one optional
live dry run of the GitHub dispatch payload against a local stack.

Run from the repository root:
    uv run --no-project --with pytest --with pyyaml pytest ops/tests
"""

import base64
import json
import os
import re
import subprocess
import urllib.request
from pathlib import Path

import pytest
import yaml

OPS = Path(__file__).resolve().parent.parent
ROOT = OPS.parent
ALERTING = OPS / "grafana" / "provisioning" / "alerting"
WORKFLOW = ROOT / ".github" / "workflows" / "alert-dispatch.yml"
DISPATCH_URL = "https://api.github.com/repos/jdobbelaar/podclatch/dispatches"


def load(name):
    return yaml.safe_load((ALERTING / name).read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def rule():
    rules = [r for g in load("rules.yaml")["groups"] for r in g["rules"]]
    assert len(rules) >= 1
    return next(r for r in rules if r["uid"] == "podclatch-5xx-ratio")


def test_all_files_parse():
    files = sorted(ALERTING.glob("*.yaml"))
    assert {f.name for f in files} >= {
        "rules.yaml",
        "contact-points.yaml",
        "notification-policies.yaml",
        "templates.yaml",
    }
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


def receivers_by_name():
    return {cp["name"]: cp["receivers"][0] for cp in load("contact-points.yaml")["contactPoints"]}


def test_local_receiver_contact_point_is_kept():
    receiver = receivers_by_name()["podclatch-webhook"]
    assert receiver["type"] == "webhook"
    assert receiver["settings"]["url"] == "http://alert-receiver:8080/"
    assert not receiver["settings"].get("authorization_credentials")


def test_dispatch_contact_point():
    receiver = receivers_by_name()["podclatch-github-dispatch"]
    settings = receiver["settings"]
    assert receiver["type"] == "webhook"
    assert settings["url"] == DISPATCH_URL
    assert settings["httpMethod"] == "POST"
    assert settings["authorization_scheme"] == "Bearer"
    # The credential is an environment reference, never a literal value.
    assert settings["authorization_credentials"] == "$__env{GITHUB_DISPATCH_TOKEN}"
    assert settings["headers"]["Accept"] == "application/vnd.github+json"
    assert settings["headers"]["X-GitHub-Api-Version"]
    assert settings["payload"]["template"] == '{{ template "podclatch.dispatch" . }}'


def test_dryrun_contact_point_uses_the_same_template_locally():
    dispatch = receivers_by_name()["podclatch-github-dispatch"]["settings"]
    dryrun = receivers_by_name()["podclatch-dispatch-dryrun"]["settings"]
    assert dryrun["url"] == "http://alert-receiver:8080/"
    assert dryrun["payload"] == dispatch["payload"]
    assert not dryrun.get("authorization_credentials")


def test_policy_routes_to_both_contact_points():
    policy = load("notification-policies.yaml")["policies"][0]
    names = {cp["name"] for cp in load("contact-points.yaml")["contactPoints"]}
    assert policy["receiver"] in names
    routes = policy["routes"]
    assert [r["receiver"] for r in routes] == ["podclatch-webhook", "podclatch-github-dispatch"]
    assert routes[0].get("continue") is True  # otherwise the dispatch route is never reached
    assert not routes[1].get("continue")
    # The dry-run contact point is test-only and must never receive real alerts.
    assert "podclatch-dispatch-dryrun" not in [policy["receiver"]] + [r["receiver"] for r in routes]


def dispatch_template():
    templates = load("templates.yaml")["templates"]
    assert [t["name"] for t in templates] == ["podclatch-dispatch"]
    return templates[0]["template"]


def test_payload_template_shape():
    template = dispatch_template()
    assert 'define "podclatch.dispatch"' in template
    assert '"event_type" "podclatch-alert"' in template
    assert '"client_payload"' in template
    # The body is serialized by data.ToJSON, so quotes and newlines in values cannot break the JSON.
    assert "data.ToJSON" in template
    # The summary is cut to 300 characters and loses any query string.
    assert 'printf "%.300s"' in template
    assert "reReplaceAll" in template


def test_payload_has_at_most_ten_client_payload_properties():
    template = dispatch_template()
    inner = re.search(r"\$payload := coll\.Dict (.*?) -\}\}", template, re.S).group(1)
    keys = re.findall(r'"([a-z_]+)" (?=[.$(])', inner)
    assert keys == ["status", "alertname", "severity", "service", "summary", "started_at", "rule_uid"]
    assert len(keys) <= 10


def test_payload_template_carries_no_secret():
    template = dispatch_template().lower()
    for word in ("token", "authorization", "bearer", "password", "secret", "__env"):
        assert word not in template


def test_compose_mounts_provisioning_read_only_and_lgtm_does_not_wait_for_receiver():
    compose = yaml.safe_load((ROOT / "docker-compose.yaml").read_text(encoding="utf-8"))
    mounts = compose["services"]["lgtm"]["volumes"]
    assert any(
        m.startswith("./ops/grafana/provisioning/alerting:") and m.endswith("/provisioning/alerting:ro")
        for m in mounts
    )
    assert "depends_on" not in compose["services"]["lgtm"]
    assert compose["services"]["alert-receiver"]["ports"][0].startswith("127.0.0.1:")


def test_compose_passes_the_token_only_to_lgtm_and_it_is_optional():
    compose = yaml.safe_load((ROOT / "docker-compose.yaml").read_text(encoding="utf-8"))
    # ${VAR:-} gives an empty default, so `make up` works when the variable is unset.
    assert compose["services"]["lgtm"]["environment"]["GITHUB_DISPATCH_TOKEN"] == "${GITHUB_DISPATCH_TOKEN:-}"
    for name, service in compose["services"].items():
        if name != "lgtm":
            assert "GITHUB_DISPATCH_TOKEN" not in str(service), name


def test_env_example_has_only_a_commented_placeholder():
    text = (ROOT / ".env.example").read_text(encoding="utf-8")
    lines = [ln for ln in text.splitlines() if "GITHUB_DISPATCH_TOKEN" in ln]
    assert lines and all(ln.startswith("#") for ln in lines)
    assert any(ln.rstrip().endswith("GITHUB_DISPATCH_TOKEN=") for ln in lines)


def test_workflow_stub_is_least_privilege_and_prints_only_event_and_alert_name():
    text = WORKFLOW.read_text(encoding="utf-8")
    workflow = yaml.safe_load(text)
    triggers = workflow.get("on", workflow.get(True))
    assert triggers == {"repository_dispatch": {"types": ["podclatch-alert"]}}
    assert workflow["permissions"] == {}
    assert "toJson(github.event.client_payload)" not in text
    assert "client_payload.alertname" in text
    assert "secrets." not in text


TOKEN_LIKE = [
    re.compile(r"(?i)\b(bearer|authorization|api[_-]?key|password|secret|token)\b\s*[:=]\s*\S+"),
    re.compile(r"\b(ghp|github_pat|glsa|AKIA)[A-Za-z0-9_]{8,}"),
    re.compile(r"[A-Za-z0-9+/_-]{40,}"),
]


def test_no_token_like_values_or_feed_urls():
    for f in list(ALERTING.glob("*.yaml")) + [OPS / "alert-receiver" / "receiver.py"]:
        text = f.read_text(encoding="utf-8")
        for pattern in TOKEN_LIKE:
            # The one allowed public URL is long enough to look like a key, so it is removed first.
            assert not pattern.search(text.replace(DISPATCH_URL, "")), f"{f.name} matches {pattern.pattern}"
        external = re.findall(r"https?://(?!alert-receiver)\S+", text)
        expected = [DISPATCH_URL] if f.name == "contact-points.yaml" else []
        assert external == expected, f"{f.name} has an unexpected external URL"


def test_no_github_token_pattern_is_committed():
    """Scan every tracked or untracked-but-not-ignored file for GitHub token prefixes."""
    listing = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout
    # Built from pieces so this file does not match its own pattern.
    pattern = re.compile("(github" + "_pat_|gh[pousr]" + "_)[A-Za-z0-9_]{10,}")
    for name in filter(None, listing.split("\0")):
        path = ROOT / name
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        assert not pattern.search(text), f"{name} looks like it contains a GitHub token"


@pytest.mark.skipif(
    not os.environ.get("PODCLATCH_DRYRUN_GRAFANA") or not os.environ.get("PODCLATCH_DRYRUN_RECEIVER"),
    reason="live dry run: set PODCLATCH_DRYRUN_GRAFANA and PODCLATCH_DRYRUN_RECEIVER (see README Alerting)",
)
@pytest.mark.parametrize(
    "summary",
    [
        "short",
        'has "quotes", a \\ backslash,\nand a newline',
        "x" * 2000,
        "see https://example.test/feed?token=SECRETVALUE now",
        "",
    ],
)
def test_live_dry_run_payload(summary):
    """Render the dry-run contact point through a real Grafana into the local receiver (not GitHub)."""
    grafana = os.environ["PODCLATCH_DRYRUN_GRAFANA"].rstrip("/")
    receiver = os.environ["PODCLATCH_DRYRUN_RECEIVER"].rstrip("/")
    name = base64.urlsafe_b64encode(b"podclatch-dispatch-dryrun").decode().rstrip("=")
    body = {
        "integration": {
            "type": "webhook",
            "uid": "podclatch-dispatch-dryrun-receiver",
            "settings": {
                "url": "http://alert-receiver:8080/",
                "httpMethod": "POST",
                "payload": {"template": '{{ template "podclatch.dispatch" . }}'},
            },
        },
        "alert": {
            "annotations": {"summary": summary},
            "labels": {
                "alertname": "PodClatch Server 5xx ratio high",
                "service": "podclatch-server",
                "severity": "critical",
            },
        },
    }
    request = urllib.request.Request(
        f"{grafana}/apis/notifications.alerting.grafana.app/v1beta1/namespaces/default/receivers/{name}/test",
        data=json.dumps(body).encode(),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    assert json.load(urllib.request.urlopen(request))["status"] == "success"
    payload = json.load(urllib.request.urlopen(f"{receiver}/last"))
    assert set(payload) == {"event_type", "client_payload"}
    assert payload["event_type"] == "podclatch-alert"
    client = payload["client_payload"]
    assert len(client) <= 10
    assert client["status"] == "firing"
    assert len(client["summary"]) <= 300
    assert "SECRETVALUE" not in json.dumps(payload)
    if "newline" in summary:
        assert client["summary"] == summary
