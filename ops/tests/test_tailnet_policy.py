"""The ready-to-paste tailnet policy snippet for the on-call demo (issue #119).

Run from the repository root:
    uv run --no-project --with pytest pytest ops/tests/test_tailnet_policy.py
"""

import json
from pathlib import Path

POLICY = Path(__file__).resolve().parent.parent / "oncall" / "tailnet-policy.example.json"


def test_policy_allows_only_the_runner_to_reach_one_port_on_the_laptop():
    policy = json.loads(POLICY.read_text(encoding="utf-8"))
    assert set(policy) == {"tagOwners", "grants"}
    assert policy["grants"] == [
        {
            "src": ["tag:podclatch-runner"],
            "dst": ["tag:podclatch-laptop"],
            "ip": ["tcp:443"],
        }
    ]


def test_policy_has_no_wildcards_and_no_secret_shaped_text():
    text = POLICY.read_text(encoding="utf-8")
    assert '"*"' not in text
    assert "tskey" not in text
    assert "funnel" not in text.lower()
