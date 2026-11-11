# Project Scoring Criteria

This project is being developed partly as a means to pass a class.  As [the class's document on scoring criteria](https://github.com/DataTalksClub/ai-dev-tools-zoomcamp/tree/main/project#criteria) indicates, "There are 12 criteria worth 24 points. You need 10 points to pass."  That document enumerates the criteria and their point values.  

I have excerpted below the criteria that, if met in total, would earn the maximum score on this project.  I emphasize that it is not necessary for me to earn the maximum score.  However, I would like us to use this list as an outline of the optimal result.  I want you to consider how each criterion can be met in the course of developing the app indicated by the spec (`_docs/podclatch-spec.md`).  If any are inapplicable, I want you to make aware of these, explain why they are inapplicable, and suggest any reasonable adjustments that could be made to the spec to better align with the scoring criteria; it may also be reasonsable to accept that some criterion will not be met.  If any will require actions taken directly by me, I want you to bring those to my attention, and provide enabling information to help me fulfill those needs efficiently.  

The class also requires peer review: the author must evaluate three other projects on the course platform, or the project fails regardless of its score.

## Decision that shapes this plan

The original spec had no backend, which would have scored 0 on Application (the class scores a missing backend or database as 0) and left Containerization, Observability, Alerting, and the On-Call Agent without anything to apply to. On 2026-10-18 the spec was changed to add **PodClatch Server** (FastAPI and Postgres; see spec section 1), and OPML import/export (US-19) and the Storage screen (US-18) were deferred to keep the scope manageable. The plan for each criterion below assumes the server exists.

| Criterion | Points | Stories that deliver it |
|---|---|---|
| Problem Description | 2 | US-35 |
| Product Specification | 2 | spec section 2, process |
| Agent Team Specification | 2 | `process.md`, `team/` |
| Context Management | 2 | `AGENTS.md` |
| Application | 2 | M2-M8, US-32 |
| Testing | 2 | per-story tests, US-30, US-32 |
| Containerization | 2 | US-29 |
| Deployment | 2 | US-33, US-35 |
| CI/CD | 3 | US-01, US-21, US-34 |
| Observability | 2 | US-36 |
| Alerting on User Impact | 1 | US-37 |
| On-Call Agent | 2 | US-38 |


## Problem Description

> The README clearly describes the problem, the system functionality, and expected behavior.

DONE: see `README.md` at the repository root.  It describes the problem, the two parts (app and server), the features, screenshots and a demo recording, the run steps, and the known limitations (US-35).

## Product Specification

> A specification exists and there's a publicly accessible task tracker. The tasks define acceptance criteria and there's evidence that the agents used the tracker. 

DONE, pending the repository becoming public.
- Specification: see `_docs/podclatch-spec.md`.
- Tasks: one milestone per phase (M0-M10, all closed) and one GitHub issue per user story, with acceptance criteria as a checklist (60 issues, all closed).  The tracker is GitHub Issues in `jdobbelaar/podclatch`.
- Evidence of agent use: `process.md` requires the PM, engineer, QA, and orchestrator each to leave a comment on every issue, and each PR to reference its issue.  Those comments are the evidence.
- **Action for the human:** the repository is private now.  It must be made **public before submission**, since the tracker has to be publicly accessible.  The git history and the tracker text were scanned for secrets and for feed URLs with embedded tokens on 2026-11-08, and none were found.  The commit history does contain the author's email addresses.

## Agent Team Specification

>The project defines the agent team and process of how they work together.

DONE: see `process.md` and `_docs/team/`.  The orchestrator, PM, engineer, and QA roles are defined, with a lifecycle, a three-agent parallelism limit, rules for the emulator and shared files, a three-round limit on QA failures, and human checkpoints.  The process was used for the whole project.

## Context Management

> AGENTS.md exists and it also points to modular docs (process, teams, etc.).

DONE: see `AGENTS.md`.  It describes the project, the repository layout, Android and server commands, the rules, and links to the spec, process, team, and template docs.

## Application

> Full-stack application works end to end: frontend talks to backend, data persists in a database. Application runs locally with a single command. 

DONE.  Satisfied by the PodClatch Server (the backend, with Postgres as the database) and the Android app (the frontend).  An Android emulator inside Docker would not work for reviewers: the emulator needs hardware virtualization (KVM) that Docker Desktop on Windows and Mac does not provide.  Instead the "single command" is `make up`, which starts the server and Postgres, and the app is installed with `./gradlew installDebug` (both documented in the README).  An automated end-to-end test (US-32) drives the real app on an emulator in CI against the real server, which demonstrates the whole chain.

## Testing

> Both unit and integration tests exist, run with a single command, and pass.

DONE.  Unit tests come with each story: Android JVM tests (`./gradlew test`) and server tests (`uv run pytest`).  Integration tests are:
- server integration tests against the Docker Compose stack with a fake feed host (US-30): subscribe, refresh, sync, conflicting state, restart persistence, and authentication
- the end-to-end test driving the Android app against that stack (US-32)

`make test` runs all of the automated tests that need no emulator, and `make test-integration` runs the Compose-stack tests.

## Containerization

> The full system, including the database, runs via Docker or Docker Compose and starts with a single command. 

DONE, **US-29**.  "The full system" is the server plus Postgres (plus the telemetry stack from US-36).  The Android app is a phone app, not a container; see Application above.

## Deployment

> The application is deployed and reachable at a public URL, or there's a clear demo with the deployment.

DONE for `dev`, **US-33** and **US-35** (checked from a laptop and from the app on an emulator; a physical phone has not been tried).  The server was deployed on 2026-11-08 to the author's AWS account on ECS Express Mode with RDS (issue #136; App Runner was the first choice, but AWS no longer lets new accounts create it).  It answers at a public HTTPS address, listed in the README under "Deployment": `/healthz` returns 200, `/v1/feeds` returns 401 without the token and 200 with it.  The address is a throw-away demo that is deleted after grading, so the README and the Deploy run below are the lasting evidence.  On the emulator, the app's Test connection succeeded against it and a Sync ran (see the README under "Deployment").

## CI/CD

> CI runs tests, CD deploys to dev, and there's a manual deploy to production. 

DONE, **US-01**, **US-21**, and **US-34**.  CI runs on every pull request.  The automatic deploy to `dev` has run against the real account: [Deploy run 42533916732](https://github.com/jdobbelaar/podclatch/actions/runs/42533916732) logged in with GitHub OIDC (no stored AWS keys), pushed the image, pointed the ECS service at it, waited for the rollout, and checked `/healthz`.  The first attempt failed because the deploy role trusted the wrong OIDC subject format; that was fixed in PR #138 and the run passed on retry, and later pushes to `main` deployed on their own.  The manual promotion to `prod` has run for real against a deployed `prod` stack: [Promote run 42939304989](https://github.com/jdobbelaar/podclatch/actions/runs/42939304989) moved the `prod` tag to the image on `dev`, updated the prod service, waited, and checked health, and [run 42940858744](https://github.com/jdobbelaar/podclatch/actions/runs/42940858744) repeated it for the same image.  The very first Promote run failed on a case the workflow did not handle (the tag already pointing at the image); PR #140 fixed it.  Promote has no required-reviewer gate, because the repository is private; running it by hand is the gate.

**Your setup list (AWS and GitHub)**, in the order it is needed:
1. AWS account: enable MFA on the root user, do not use root day to day, and create an admin user or SSO login for yourself.
2. A billing budget alert, since an always-on server plus database costs real money.  Plan to tear it down after grading.
3. Install the AWS CLI and run `aws configure sso`.
4. When US-33 arrives, run the bootstrap CloudFormation template once with admin credentials; it creates the GitHub OIDC provider and the deploy role.
5. In GitHub, create `dev` and `production` environments.  Required reviewers on `production` only work free for public repositories; until the repo is public, the manual `workflow_dispatch` promotion is the gate.
6. Add the Anthropic API key as a GitHub secret `ANTHROPIC_API_KEY` for the on-call agent (US-38).
7. Install Android Studio and the Android SDK.  Google Play is not needed.
8. Create the Android release signing key and store it as a GitHub secret when US-34 asks for it.

## Observability

> A key user journey is instrumented with metrics, traces, and structured logs that share a trace ID; no customer data or credentials are sent to telemetry; and the signals are collected, stored, and viewable in a UI. 

DONE, **US-36**.  The key journey is Refresh/Sync (app, then server, then feed host).  The app sends a W3C `traceparent` header so a client request and its server work share a trace.  Feed URLs can contain private tokens, so they are stripped from all telemetry (spec decision 27) and a test checks this.  Signals go to an OpenTelemetry Collector and a Grafana UI in the Compose stack.

## Alerting on User Impact

> At least one alert exists.

DONE, **US-37**: an alert on the server's 5xx rate or the feed-refresh failure ratio, defined as code, sending a webhook.

## On-Call Agent

> An agent wakes up on an alert and fixes the issue. 

DONE, **US-38**: the alert webhook triggers a GitHub Actions workflow that runs Claude Code headlessly to diagnose the problem and open a PR with a fix and a failing-then-passing test.  A human merges it.  It was demonstrated live on 2026-11-08 with a deliberately seeded fault; the evidence is in `docs/oncall-demo.md`.
