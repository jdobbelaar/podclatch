# PodClatch

A personal, single-user podcast player: a native Android app (Kotlin, Jetpack Compose, Media3) plus a small Python web service, **PodClatch Server** (FastAPI, Postgres), that holds the user's subscriptions and state. The Android app caches everything for offline use, downloads audio directly from publishers, and does all playback. The product spec is the source of truth; if code and spec disagree, follow the spec or raise it on the issue.

Use the spec's vocabulary in code, UI, and docs: Feed, Episode, Queue, Feed Priority, Listen Order, New / Played, Sync, Refresh.

## Repository layout

- `android/` - the Android app (Gradle, version catalog at `android/gradle/libs.versions.toml`)
- `server/` - PodClatch Server (uv, `server/pyproject.toml`)
- `openapi.yaml` - the HTTP contract between app and server (change it before the code that uses it)
- `docker-compose.yaml`, `Makefile` - run the whole system locally
- `infra/` - AWS CloudFormation
- `_docs/` - specification, process, and team docs

## Commands

Android (run from `android/`):

- `./gradlew build test` - build and run the JVM unit tests
- `./gradlew test --tests "com.podclatch.SomeTest"` - one test class
- `./gradlew lint` - Android lint
- `./gradlew connectedDebugAndroidTest` - instrumented tests; needs an emulator or device, so never run it in parallel with another agent

Server (run from `server/`):

- `uv sync` - install dependencies
- `uv run pytest` - the whole suite
- `uv run pytest tests/test_feeds.py` - one test file
- `uv run ruff check .` - lint

Whole system (run from the repository root; created by US-29, so they do not exist before that story):

- `make up` / `make down` - start and stop the server and Postgres
- `make test` - all automated tests
- `make test-integration` - server integration tests against the Compose stack

## Rules

- Follow `_docs/podclatch-spec.md`. Do not build anything in its section 8 (Non-Goals).
- Dependencies and tools: choose well-maintained, widely used libraries with permissive licenses (Apache-2.0, MIT, BSD) when they save real work, and prefer the standard AndroidX/Jetpack and official libraries over hand-rolled equivalents. The human has delegated these choices, so do not stop to ask. Record every dependency or tool you add in the PR description under "Dependencies added", with a one-line reason, so the human can review it later. Still ask the human first for anything that costs money, needs an account or API key, sends data to a third party, or conflicts with the spec's section 8 non-goals (for example analytics or crash-reporting SDKs). Android dependencies go in the version catalog; server dependencies go in `server/pyproject.toml`.
- One branch and one pull request per issue, named `us-xx-short-name`, with `Closes #n` in the PR. Never commit directly to `main`. Commit regularly.
- Changing the API means changing `openapi.yaml` in the same pull request, before the code that uses it.
- On Android, only repositories may call `PodClatchApi`; never a ViewModel or UI code.
- Never commit secrets: tokens, passwords, keystores, `.env` files, or AWS credentials. Commit `.env.example` only.
- Feed URLs can contain secrets. Never write a full feed URL to logs, traces, metrics, or issue comments; drop the query string.
- The server never fetches, stores, or serves podcast audio.
- Definition of Done for every issue is in spec section 2. Comment on the issue as described in `_docs/process.md`.
- If an acceptance criterion is wrong, impossible, or contradicts another, say so in an issue comment instead of working around it.

## Documents

- `_docs/podclatch-spec.md` - what to build: decisions, data model, business logic, milestones, and user stories
- `_docs/process.md` - how work is organized: the orchestrator, the lifecycle, and the human checkpoints
- `_docs/team/pm.md`, `_docs/team/software-engineer.md`, `_docs/team/qa-engineer.md` - the role instructions
- `_docs/task-template.md` - the shape of every groomed issue
- `_docs/full-score-criteria.md` - the course scoring criteria and the plan for meeting each; it explains why some stories (CI/CD, observability, alerting, the on-call agent) exist
