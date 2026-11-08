# On-call agent demo: evidence

Run of 2026-11-08 (UTC), issues #35 and #119. A seeded fault made the server answer 500; the Grafana alert fired; the dispatch started the on-call workflow; the agent opened a fix pull request; the human merged it. Setup and design are in the README ("On-call agent" and "On-call agent demo").

## Links

| What | Link |
|---|---|
| Alert rule "PodClatch Server 5xx ratio high" | <http://127.0.0.1:3180/alerting/list> (local: only works on the laptop while the stack runs) |
| Workflow run that produced the fix | <https://github.com/jdobbelaar/podclatch/actions/runs/42441206602> |
| The agent's pull request (merged by the human) | <https://github.com/jdobbelaar/podclatch/pull/129> |
| Connectivity check through the tunnel | <https://github.com/jdobbelaar/podclatch/actions/runs/42437649239> |
| Issues | #35 (workflow), #119 (tunnel and live demo), #118 (seeded fault) |

## Timeline of the successful run (UTC)

| Time | Event |
|---|---|
| 02:58:56 | Trigger script sends the first `POST /v1/feeds`; the server answers 500 every 15 seconds. |
| 03:05:13 | Grafana alert Firing; `podclatch-alert` dispatch reaches GitHub. |
| 03:05:15 | Workflow starts (`gate`, then `diagnose` at 03:05:26). The guard passes and the runner joins the tailnet. |
| 03:08:26 | `open-pr` opens pull request #129 against `demo/seeded-fault`, label `oncall-agent`. |
| 03:21:39 | The human merges #129 (squash). |

From the alert to the pull request: 3 minutes 13 seconds. The agent run took 17 turns, 142 seconds of model time, and $0.17 (`run-stats.json`).

## What the agent did

It read the logs through the telemetry gateway and found the failing route, `POST /v1/feeds`, and the exception, `ValueError: itunes:duration with fractional seconds is not handled (shape H:MM:SS.fff, ...)` in `_parse_duration` (the scrubbed exception message from #123 was what made this possible). It reproduced the fault with a test, removed the raise so a bad duration returns `None` and the episode is kept, and rewrote the seeded-fault test as regression tests. The change is 2 files, +8 and −23 lines. CI did not run on the pull request: GitHub holds workflow runs on bot pull requests until a person approves them, and nobody did before the merge. The agent ran the server tests itself.

## What went wrong on the way (before the successful run)

1. **First dispatch, run 42438602025:** the `gate` job failed closed ("could not read GitHub"). `ops/oncall/guard.py` called `gh api .../runs/<id>/jobs -f per_page=100` without `-X GET`, which `gh` turns into a POST (404). It only appears when another on-call run exists in the last 24 hours, so the mocked tests never saw it. Fixed in #128 with a test that fails on the old code.
2. **Second dispatch, run 42440188742:** the agent ran (10 turns, $0.15) but made no change. The workflow checked out `main`, where the seeded fault does not exist, so the agent read the logs, could not find the faulty code, and wrote "a wrong fix is worse than none" in its report. Setting the repository variable `ONCALL_BASE_BRANCH` to `demo/seeded-fault` fixed it.
3. **Compose project name:** `docker-compose.yaml` fixes `name: podclatch`, so `make up` in a demo checkout would run the fault server on the real Postgres volume. The demo ran as `docker compose -p podclatch-demo up`, with its own volumes; the README now says so.

## Safety notes

- The tunnel exposed only the `telemetry-gateway` (read-only queries behind a bearer token), tailnet only, never Funnel. The gateway answered 200 with the token and 401 without it (run 42437649239).
- The agent never held a write token: `diagnose` has `contents: read`; only `open-pr` can push, and it has no agent or API key.
- No secret, token, or full feed URL appears in this document.
