#!/usr/bin/env python3
"""Duplicate and rate guards for the on-call workflow (US-38, issue #35).

Skips the agent run when:
  - an open pull request labelled `oncall-agent` already exists for the same alert (its branch is
    `oncall/<slug>-<run id>`), or
  - MAX_AGENT_RUNS or more earlier runs of this workflow reached the agent step in the last 24 hours.

Reads GitHub through the `gh` CLI (GH_TOKEN comes from the environment; this job only reads).
Writes `skip=true|false` and `skip_reason=<text>` to $GITHUB_OUTPUT, and always exits 0 unless the
GitHub API could not be read (then it exits 1: fail closed rather than spend money blindly).

Usage:
    python ops/oncall/guard.py --repo owner/name --slug <alert slug> --run-id <current run id>
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import sys

LABEL = "oncall-agent"
MAX_AGENT_RUNS = 3
WINDOW_HOURS = 24
WORKFLOW_FILE = "oncall.yml"
AGENT_STEP_NAME = "Run the agent"
DIAGNOSE_JOB_NAME = "diagnose"


def has_open_pr(prs: list[dict], slug: str) -> bool:
    """True when an open agent PR exists for this alert (matched on its branch name)."""
    return any(str(pr.get("headRefName", "")).startswith(f"oncall/{slug}-") for pr in prs)


def reached_agent(jobs: list[dict]) -> bool:
    """True when a prior run used an agent slot. Unknown counts as used (fail closed).

    GitHub's jobs API can return an empty or truncated step list for a finished job (runs 42444647265
    and 42444853675: empty, then a partial list with no "Run the agent" entry), which made the limit
    fail open. So a run counts when:
      - a step list shows the agent step ran to a result (success or failure), OR
      - the `diagnose` job finished with success or failure and its step list does not show the agent
        step at all (empty, missing, or truncated): unknown, so counted.
    A run does not count when `diagnose` was skipped or cancelled (kill switch, duplicate, rate limit,
    resolved), or when the step list shows the agent step skipped or cancelled (dry_run, check_only).
    Consequence of failing closed: a dry_run or check_only run whose step list comes back without the
    agent step is counted too.
    """
    for job in jobs:
        agent_steps = [s for s in job.get("steps") or [] if s.get("name") == AGENT_STEP_NAME]
        if agent_steps:
            if any(s.get("conclusion") in ("success", "failure") for s in agent_steps):
                return True
        elif job.get("name") == DIAGNOSE_JOB_NAME and job.get("conclusion") in ("success", "failure"):
            return True
    return False


def decide(prs: list[dict], jobs_per_run: list[list[dict]], slug: str) -> tuple[bool, str]:
    """Return (skip, reason)."""
    if has_open_pr(prs, slug):
        return True, "skipped: duplicate (an open oncall-agent pull request exists for this alert)"
    used = sum(1 for jobs in jobs_per_run if reached_agent(jobs))
    if used >= MAX_AGENT_RUNS:
        return True, f"skipped: rate limit ({used} agent runs in the last {WINDOW_HOURS} hours)"
    return False, ""


def gh_json(*args: str) -> object:
    result = subprocess.run(["gh", *args], capture_output=True, text=True, check=True)
    return json.loads(result.stdout or "null")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--slug", required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()

    try:
        prs = gh_json("pr", "list", "--repo", args.repo, "--label", LABEL, "--state", "open",
                      "--json", "headRefName")  # fmt: skip
        since = (dt.datetime.now(dt.UTC) - dt.timedelta(hours=WINDOW_HOURS)).strftime("%Y-%m-%dT%H:%M:%SZ")
        runs = gh_json("api", "-X", "GET", f"repos/{args.repo}/actions/workflows/{WORKFLOW_FILE}/runs",
                       "-f", f"created=>={since}", "-f", "per_page=100")  # fmt: skip
        jobs_per_run = []
        for run in runs.get("workflow_runs", []):
            if str(run["id"]) == args.run_id:
                continue
            # -X GET is required: `gh api` turns any call with -f into a POST, and GitHub answers 404.
            jobs = gh_json("api", "-X", "GET", f"repos/{args.repo}/actions/runs/{run['id']}/jobs",
                           "-f", "per_page=100")  # fmt: skip
            jobs_per_run.append(jobs.get("jobs", []))
    except (subprocess.CalledProcessError, ValueError, KeyError, AttributeError) as error:
        print(f"guard: could not read GitHub ({type(error).__name__}); failing closed")
        return 1

    skip, reason = decide(prs, jobs_per_run, args.slug)
    print(reason or "guard: no duplicate, under the rate limit")
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as handle:
            handle.write(f"skip={'true' if skip else 'false'}\nskip_reason={reason}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
