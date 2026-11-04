#!/usr/bin/env python3
"""Check the agent's patch and report before a pull request is opened (US-38, issue #35).

Fails closed: any problem exits 1 and the job stops. Checks:
  - the patch only touches files under server/podclatch_server/ and server/tests/;
  - no renames, copies, mode changes, symlinks, or binary files, and no path tricks (`..`);
  - at most MAX_CHANGED_LINES added plus removed lines;
  - neither the patch nor the report holds a secret value (named by --secret-env) or a common
    token shape.

Usage:
    python ops/oncall/validate_patch.py --patch out/patch.diff --report out/report.md \
        [--secret-env ANTHROPIC_API_KEY --secret-env TELEMETRY_GATEWAY_TOKEN]
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

ALLOWED_PREFIXES = ("server/podclatch_server/", "server/tests/")
# Dependency and tool-configuration files are never allowed, even if a future prefix covered them.
FORBIDDEN_NAMES = {
    "pyproject.toml",
    "uv.lock",
    "requirements.txt",
    "setup.py",
    "setup.cfg",
    "conftest.py.bak",
    ".env",
}
MAX_CHANGED_LINES = 300
MIN_SECRET_LENGTH = 8

TOKEN_SHAPES = [
    ("Anthropic key", re.compile(r"sk-ant-[A-Za-z0-9_-]{8,}")),
    ("OpenAI-style key", re.compile(r"\bsk-[A-Za-z0-9]{20,}")),
    ("GitHub token", re.compile(r"\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}")),
    ("GitHub fine-grained token", re.compile(r"github_pat_[A-Za-z0-9_]{20,}")),
    ("AWS access key", re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
    ("Slack token", re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}")),
    ("JSON web token", re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+")),
    ("private key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("bearer token", re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{20,}")),
    ("long hex secret", re.compile(r"(?i)\b(?:token|secret|password|api[_-]?key)\w*\s*[:=]\s*['\"]?[0-9a-f]{32,}")),
]


def scan_text(text: str, secret_values: list[str]) -> list[str]:
    """Return a list of problems (never the matched text itself, so the log stays clean)."""
    problems = []
    for value in secret_values:
        if len(value) >= MIN_SECRET_LENGTH and value in text:
            problems.append("a secret value appears in the text")
    for label, pattern in TOKEN_SHAPES:
        if pattern.search(text):
            problems.append(f"text matches a {label} shape")
    return problems


def check_patch(patch: str) -> list[str]:
    """Return the problems with a unified diff, empty when it is acceptable."""
    problems = []
    if not patch.strip():
        return ["the patch is empty"]
    changed = 0
    paths: set[str] = set()
    for line in patch.splitlines():
        if line.startswith("diff --git "):
            match = re.fullmatch(r"diff --git a/(\S+) b/(\S+)", line)
            if not match or match.group(1) != match.group(2):
                problems.append("a diff header is not a plain same-path change")
                continue
            paths.add(match.group(2))
        elif re.match(r"(rename|copy|similarity|old mode|new mode|deleted file mode) ", line):
            problems.append(f"unsupported change: {line.split(' ')[0]}")
        elif line.startswith("new file mode") and line.split()[-1] == "120000":
            problems.append("symlinks are not allowed")
        elif line.startswith("GIT binary patch") or line.startswith("Binary files "):
            problems.append("binary changes are not allowed")
        elif line.startswith(("+++ ", "--- ")):
            continue
        elif line.startswith(("+", "-")):
            changed += 1
    if not paths:
        problems.append("the patch changes no file")
    for path in sorted(paths):
        parts = path.split("/")
        if ".." in parts or path.startswith("/") or "\\" in path:
            problems.append(f"path escapes the repository: {path!r}")
        elif not path.startswith(ALLOWED_PREFIXES):
            problems.append(f"path is outside the allowed folders: {path}")
        elif parts[-1] in FORBIDDEN_NAMES or parts[-1].startswith(".env"):
            problems.append(f"path is a forbidden file: {path}")
    if changed > MAX_CHANGED_LINES:
        problems.append(f"patch changes {changed} lines, the limit is {MAX_CHANGED_LINES}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--patch", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--secret-env", action="append", default=[], help="env var holding a secret")
    args = parser.parse_args()

    patch = Path(args.patch).read_text(encoding="utf-8", errors="replace")
    report = Path(args.report).read_text(encoding="utf-8", errors="replace")
    secrets = [os.environ.get(name, "") for name in args.secret_env]

    problems = check_patch(patch)
    problems += [f"patch: {p}" for p in scan_text(patch, secrets)]
    problems += [f"report: {p}" for p in scan_text(report, secrets)]
    if problems:
        for problem in dict.fromkeys(problems):
            print(f"validate_patch: FAIL: {problem}")
        return 1
    print("validate_patch: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
