# PodClatch: Peer Review Digest

For reviewers scoring this project against the course's 12 criteria. For each one, this digest says how the criterion is met and where to find the evidence. Paths are relative to the repository root. Everything here can be checked by reading files; running anything is optional.

**What PodClatch is.** A single-user podcast player: an Android app (Kotlin, Jetpack Compose, Media3) plus a small backend, PodClatch Server (FastAPI, Postgres). The whole project was built by a team of AI agents following a written process.

**Tracker:** GitHub Issues and milestones in `jdobbelaar/podclatch` (60 issues, milestones M0 to M10, all closed).

**Known gaps, stated up front:** both deployed servers (`dev` and `prod`) are deleted after grading, so their URLs will stop working. The lasting evidence for deployment is the README and the linked workflow runs.

## At a glance

| Criterion | Pts | Status | Start here |
|---|---|---|---|
| Problem Description | 2 | Met | `README.md` (Introduction, What it does) |
| Product Specification | 2 | Met | `_docs/podclatch-spec.md`, GitHub Issues |
| Agent Team Specification | 2 | Met | `_docs/process.md`, `_docs/team/` |
| Context Management | 2 | Met | `AGENTS.md` |
| Application | 2 | Met | `README.md` (Run it locally), `make up` |
| Testing | 2 | Met | `README.md` (Run the tests) |
| Containerization | 2 | Met | `docker-compose.yaml`, `make up` |
| Deployment | 2 | Met | `README.md` (Deployment) |
| CI/CD | 3 | Met | `.github/workflows/`, Deploy run link below |
| Observability | 2 | Met | `README.md` (Telemetry), Grafana via `make up` |
| Alerting on User Impact | 1 | Met | `ops/grafana/provisioning/alerting/rules.yaml` |
| On-Call Agent | 2 | Met | `docs/oncall-demo.md`, PR #129 |

## 1. Problem Description

**Criterion:** the README clearly describes the problem, the system functionality, and expected behavior.

**How it is met:** the README opens with the problem being solved, then the two parts (app and server), the features, screenshots, a demo recording, run steps, and known limitations.

**Evidence:** `README.md` sections "Introduction" (including "The problem being solved"), "What it does", "Screenshots" and "Demo" (assets in `docs/screenshots/` and `docs/demo/`), and "Known limitations".

## 2. Product Specification

**Criterion:** a specification exists, there is a publicly accessible task tracker, tasks have acceptance criteria, and agents used the tracker.

**How it is met:**
- The specification is `_docs/podclatch-spec.md` (decisions, data model, business logic, milestones, user stories, non-goals).
- The tracker is GitHub Issues. Each user story is one issue with an acceptance-criteria checklist (shape defined in `_docs/task-template.md`).
- `_docs/process.md` requires the PM, engineer, QA, and orchestrator each to comment on every issue, and every pull request to say `Closes #n`.

**Evidence:** open any closed issue and look for the four role comments and the linked PR. Issues #35, #119, and #136 are good examples. The tracker is only visible to you if the repository is public (it is meant to be at submission).

## 3. Agent Team Specification

**Criterion:** the project defines the agent team and how they work together.

**How it is met:** four roles (orchestrator, PM, software engineer, QA engineer) with a defined task lifecycle, a three-agent parallelism limit, rules for the shared emulator and shared files, a three-round limit on QA failures, and human checkpoints.

**Evidence:** `_docs/process.md` (the process) and `_docs/team/pm.md`, `_docs/team/software-engineer.md`, and `_docs/team/qa-engineer.md` (the roles). The process in action is visible in the issue comments (criterion 2).

## 4. Context Management

**Criterion:** `AGENTS.md` exists and points to modular docs.

**How it is met:** `AGENTS.md` gives the project summary, repository layout, Android and server commands, and rules, then links to the spec, process, team roles, task template, and scoring criteria. `CLAUDE.md` imports it.

**Evidence:** `AGENTS.md` (the "Documents" section lists the linked docs) and `CLAUDE.md`.

## 5. Application

**Criterion:** a full-stack application works end to end (frontend, backend, database) and runs locally with a single command.

**How it is met:** the Android app is the frontend, PodClatch Server is the backend, and Postgres is the database. `make up` starts the server and database (plus telemetry); the app is installed with `./gradlew installDebug`. An Android emulator cannot run inside Docker (no hardware virtualization on Docker Desktop for Windows or Mac), so the app is the one part not started by `make up`.

**Evidence:**
- `README.md` "Run it locally" and "Running the server with Docker Compose".
- The end-to-end test (`make test-e2e`, `.github/workflows/e2e.yml`, `android/tools/e2e.py`) drives the real app on an emulator against the real server: connect, subscribe, download, play, mark Played, and check the state reached the server.
- `docs/demo/` and `docs/screenshots/` show it working.
- `openapi.yaml` is the contract between app and server.

## 6. Testing

**Criterion:** unit and integration tests exist, run with a single command, and pass.

**How it is met:** unit tests come with each story, on both sides. Integration tests run the server and Postgres from Compose with a fake feed host, over HTTP.

**Evidence:**
- `README.md` "Run the tests" lists every command, what it covers, and what it needs.
- Unit: `server/tests/` (`uv run pytest`), `android/app/src/test/` (`./gradlew build test`), and instrumented tests in `android/app/src/androidTest/`.
- Integration: `server/tests_integration/` (`make test-integration`).
- Also: `ops/tests/` (alerting and on-call), `infra/tests/` (templates).
- Passing results: the green checks on any merged PR, or the Actions tab (Server CI and Android CI).

## 7. Containerization

**Criterion:** the full system, including the database, runs via Docker Compose and starts with a single command.

**How it is met:** `make up` (which is `docker compose up -d --build --wait`) starts the server, Postgres, and the Grafana telemetry stack and waits until all are healthy. The Android app is a phone app and is not containerized.

**Evidence:** `docker-compose.yaml`, `server/Dockerfile`, `Makefile`, and the README "Running the server with Docker Compose" section, which lists the three `curl` checks and the expected responses. Server CI also builds the image and smoke-tests the stack on every PR.

## 8. Deployment

**Criterion:** the application is deployed and reachable at a public URL, or there is a clear demo of the deployment.

**How it is met:** the server runs on AWS (ECS Express Mode with RDS Postgres) behind public HTTPS addresses, as two stacks from one template: `dev` (deployed 2026-11-08) and `prod` (promoted 2026-11-10).
- `dev`: <https://po-aac25f44f7414515a82e9ca2fcf84e02.ecs.us-east-1.on.aws>
- `prod`: <https://po-a11dc2f8b0df4fe58166373d7fbffc26.ecs.us-east-1.on.aws>

On `dev`, `/healthz` returns 200, and `/v1/feeds` returns 401 without the token and 200 with it. The Android app was connected to `dev` on an emulator: Test connection succeeded and a Sync ran.

**The URLs are temporary.** Both stacks are deleted after grading. If they no longer answer, rely on the written record and the workflow runs below.

**Evidence:**
- `README.md` "Deployment" (address, the checks, and the emulator test, with times).
- Deploy workflow runs: <https://github.com/jdobbelaar/podclatch/actions/runs/42533916732>, plus later runs 42537801196 and 42938007292 (see criterion 9).
- Promote workflow runs to `prod`: <https://github.com/jdobbelaar/podclatch/actions/runs/42939304989> and <https://github.com/jdobbelaar/podclatch/actions/runs/42940858744>.
- `infra/bootstrap.yaml` and `infra/app.yaml` (the templates) and `infra/README.md` (how it was deployed).
- Physical phone: the author tried the app on a physical Android phone against the `prod` server on 2026-11-10, and Test connection succeeded. The first Sync replaced the phone's feeds with the server's (none), as the README says a server change does. The author then added feeds and ran a Refresh, which pulled episodes. After switching the app back to the laptop server over Tailscale, Sync restored the feeds that server holds. This is the author's report, with no recording. The emulator test above is the detailed evidence.

## 9. CI/CD

**Criterion:** CI runs tests, CD deploys to dev, and there is a manual deploy to production.

**How it is met:**
- **CI:** runs on every pull request. Android CI, Server CI (SQLite, Postgres, Compose smoke test, integration tests), OpenAPI CI, Infra CI, Scripts CI, and Ops CI. The end-to-end emulator test runs on pushes to `main`.
- **CD to dev:** Deploy runs after Server CI succeeds on a push to `main`. It logs in with GitHub OIDC (no stored AWS keys), pushes the image to ECR, updates the ECS service, waits for the rollout, and checks `/healthz`.
- **Manual production deploy:** Promote runs by hand only. It moves the `prod` tag to the image currently tagged `dev` (no rebuild), points the prod service at it, waits for the rollout, and checks `/healthz`.

**Evidence:** `.github/workflows/` (`android-ci.yml`, `server-ci.yml`, `deploy.yml`, `promote.yml`, `e2e.yml`, and others) and the table in `README.md` "CI/CD". Look at the Actions tab for the runs:
- **Deploy to dev:** 42533916732 (the first attempt failed on the OIDC subject format; PR #138 fixed that and the retry passed), then 42537801196 and 42938007292, which deployed from pushes to `main` without intervention.
- **Promote to prod:** run 42939304989 was a real promotion (moved the `prod` tag, updated the service, waited, checked health), and run 42940858744 repeated it for the same image. The very first Promote run (42936622544) failed because the `prod` tag already pointed at the image; PR #140 fixed that case.

**Limit:** Promote has no required-reviewer gate, because environment reviewers need a public repository (or a paid plan). Running it by hand is the gate.

## 10. Observability

**Criterion:** a key user journey is instrumented with metrics, traces, and structured logs sharing a trace ID; no customer data or credentials reach telemetry; the signals are collected, stored, and viewable in a UI.

**How it is met:** the key journey is Refresh and Sync (app, server, feed host). The app sends a W3C `traceparent` header so a client request and its server work share a trace. The server exports traces, metrics, and JSON logs (with `trace_id` and `span_id`) over OpenTelemetry to the `grafana/otel-lgtm` container (Collector, Tempo, Loki, Prometheus, Grafana). Feed URLs can carry private tokens, so telemetry strips them, and exception messages are scrubbed before export.

**Evidence:**
- `README.md` "Telemetry" under "Running the server with Docker Compose" (how to read traces, logs, and errors).
- Server code: `server/podclatch_server/telemetry.py` and `server/podclatch_server/scrub.py`.
- Tests: `server/tests/test_telemetry.py`, `server/tests/test_scrub.py`, and `server/tests_integration/test_secrecy.py` (checks that secrets do not reach telemetry).
- To see it live: `make up`, make a request, wait about 30 seconds, then open Grafana at <http://localhost:3180> (no login). Search Tempo for service `podclatch-server`, and Loki with `{service_name="podclatch-server"}`.
- Documented limit: the scrubber cannot recognize episode titles, so a parser error that quotes feed text could reach Loki (up to 1000 characters). The README explains the trade-off (issue #123).

## 11. Alerting on User Impact

**Criterion:** at least one alert exists.

**How it is met:** the Grafana alert "PodClatch Server 5xx ratio high" fires when more than 5% of requests over a 5-minute window are answered with a 5xx, sustained for 5 minutes. It is defined as code, not clicked in the UI, and sends a webhook to a local receiver and a dispatch to GitHub.

**Evidence:** `ops/grafana/provisioning/alerting/` (`rules.yaml`, `contact-points.yaml`, `notification-policies.yaml`, `templates.yaml`), `ops/alert-receiver/receiver.py`, `ops/tests/test_alerting.py`, and the README section on the alert (which records one observed run: Firing about 7 minutes after the first failure). To see it: `make up`, then Grafana, Alerting, Alert rules, folder `PodClatch`.

## 12. On-Call Agent

**Criterion:** an agent wakes up on an alert and fixes the issue.

**How it is met:** the alert dispatches to GitHub, which starts the `oncall.yml` workflow. A guard checks the payload, and Claude Code runs headlessly. It reads telemetry through a read-only gateway, reproduces the fault with a failing test, and opens a pull request with the fix and passing tests. A human merges it.

**Evidence:**
- `docs/oncall-demo.md` is the demo record: a seeded fault made the server answer 500, the alert fired, the workflow ran, and the agent opened a fix PR.
- Workflow run: <https://github.com/jdobbelaar/podclatch/actions/runs/42441206602>.
- The agent's PR: <https://github.com/jdobbelaar/podclatch/pull/129>. From alert to PR took 3 minutes 13 seconds.
- Code: `.github/workflows/oncall.yml`, `ops/oncall/` (`guard.py`, `prompt.md`, `run_agent.py`, `validate_patch.py`), and `ops/telemetry-gateway/gateway.py`. Tests: `ops/tests/test_oncall.py`, `ops/tests/test_telemetry_gateway.py`.

**Limits (stated in the README):** the fault was seeded on purpose, and the demo ran against the author's laptop over a private Tailscale tunnel, not against the AWS deployment. CI did not run on the agent's PR because GitHub holds workflow runs on bot PRs until a person approves them; the agent ran the server tests itself.

## Quick reviewer path (about 15 minutes, no setup)

1. Read the README's Introduction and "What it does", and watch the demo in `docs/demo/` (criterion 1).
2. Skim `AGENTS.md`, `_docs/process.md`, and one issue with its four role comments (criteria 2 to 4).
3. Open the Actions tab: a green Server CI run, the Deploy run, and the on-call run (criteria 6, 8, 9, 12).
4. Read `docs/oncall-demo.md` and PR #129 (criterion 12).
5. Read `ops/grafana/provisioning/alerting/rules.yaml` (criterion 11).

To run it yourself, follow `README.md` "Run it locally" (Docker, a `.env` with a token and a database password, then `make up`).
