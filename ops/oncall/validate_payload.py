#!/usr/bin/env python3
"""Validate the alert payload that starts the on-call workflow (US-38, issue #35).

The payload is untrusted input. It is read from the environment variable PAYLOAD_JSON (never
interpolated into a shell script), checked against strict patterns, and only a clean, re-serialised
copy of the known fields is written out. A resolved, unknown, or malformed payload is "skipped",
which is a clean exit (0), not a failure: no agent step runs for it.

Usage:
    PAYLOAD_JSON='{"status": ...}' python ops/oncall/validate_payload.py --out out/payload.json

Writes `result=ok|skipped`, `reason=<text>`, and `slug=<alert slug>` to $GITHUB_OUTPUT when set.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

EXPECTED_ALERTNAME = "PodClatch Server 5xx ratio high"
EXPECTED_SERVICE = "podclatch-server"
SLUG = "podclatch-server-5xx-ratio-high"
MAX_SUMMARY = 300
MAX_PAYLOAD_BYTES = 8192

RULE_UID_RE = re.compile(r"[A-Za-z0-9_-]{1,40}")
STARTED_AT_RE = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})")
SEVERITY_RE = re.compile(r"[a-z]{1,20}")
# Summary is free text, so it is length-limited and may not hold control characters.
CONTROL_RE = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")

FIELDS = ("status", "alertname", "severity", "service", "summary", "started_at", "rule_uid")


def validate(raw: str) -> tuple[dict | None, str]:
    """Return (clean payload, "") when the alert should start the agent, else (None, reason)."""
    if len(raw.encode("utf-8")) > MAX_PAYLOAD_BYTES:
        return None, "payload too large"
    try:
        data = json.loads(raw)
    except ValueError:
        return None, "payload is not JSON"
    if not isinstance(data, dict):
        return None, "payload is not an object"
    for key in FIELDS:
        if not isinstance(data.get(key, ""), str):
            return None, f"field {key} is not a string"
    if data.get("status") != "firing":
        return None, "status is not firing"
    if data.get("alertname") != EXPECTED_ALERTNAME:
        return None, "unknown alertname"
    if data.get("service") != EXPECTED_SERVICE:
        return None, "unknown service"
    if not RULE_UID_RE.fullmatch(data.get("rule_uid", "")):
        return None, "rule_uid has an unexpected shape"
    if not STARTED_AT_RE.fullmatch(data.get("started_at", "")):
        return None, "started_at has an unexpected shape"
    severity = data.get("severity", "")
    if severity and not SEVERITY_RE.fullmatch(severity):
        return None, "severity has an unexpected shape"
    summary = data.get("summary", "")
    if len(summary) > MAX_SUMMARY:
        return None, "summary is longer than 300 characters"
    if CONTROL_RE.search(summary):
        return None, "summary holds control characters"
    return {key: data.get(key, "") for key in FIELDS}, ""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, help="where to write the clean payload JSON")
    args = parser.parse_args()

    clean, reason = validate(os.environ.get("PAYLOAD_JSON", ""))
    outputs = {"result": "ok" if clean else "skipped", "reason": reason or "valid", "slug": SLUG}
    if clean:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(clean, indent=2) + "\n", encoding="utf-8")
    print(f"validate: {outputs['result']} ({outputs['reason']})")
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as handle:
            for key, value in outputs.items():
                handle.write(f"{key}={value}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
