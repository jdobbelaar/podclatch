# PodClatch

## Introduction

Podclatch is a personal, single-user podcast player for Android built around a queue that fills itself. The user subscribes to feeds, ranks them by priority, sets a listen order for each feed, and PodClatch downloads and queues episodes automatically.  

PodClatch has two parts:

- **PodClatch Android app** (`android/`): native Android (Kotlin, Jetpack Compose, Media3). It plays episodes, builds the queue, downloads audio directly from the podcasts' publishers, and works offline from a local cache. It also supports Android Auto.
- **PodClatch Server** (`server/`): a small single-user web service (Python, FastAPI, Postgres). It searches for podcasts, fetches and refreshes their RSS feeds on a schedule, and stores the user's subscriptions, feed priorities, listen orders, and played/new state, so that state survives and syncs. There are no user accounts, only one access token.

See `_docs/podclatch-spec.md` for the full product specification.

This app was developed partly as a means to pass a class, and its development process was crafted to align with [the class's document on scoring criteria](https://github.com/DataTalksClub/ai-dev-tools-zoomcamp/tree/main/project#criteria).  


### The problem being solved

There are a few problems being solved by the creation of this app:
- The user's current favorite podcast player is no longer maintained nor offered in the Google app store, so the user fears that it will likely become unusable at some point.  
- That prior app disappoints the user in a few ways, enumerated below, that the user hopes to improve upon.

Since one of the goals of this project was to displace the user's favorite podcast player, it should be noted that the user's current favorite podcast player (referred to herein as "the prior app") was not directly used as the basis of the specification for the new app, except that the behavior of the queue in the new app was indeed designed to mimic the behavior that the user liked in how the user's current favorite app managed its queue. Otherwise, the user simply asked the agent for a spec to build his own podcast player app, and then requested some changes to that generic specification. 


#### Disappointments in the other app to be improved upon
- When a new feed is subscribed, the prior app assigns it last priority. The user wants the opposite. When subscribing to a new feed, the user usually wants to hear episodes from that feed before any others, so the user wants it added with top priority.  
- Every time the prior app connects to Android Auto, it switches from the user's preferred and populated queue to another queue that the user never uses.  Consequently, nothing is queued when the episode in progress finishes, and the user has to manually switch back to the desired queue.  The new app has only the one desired queue.  
- Although the user prefers to listen at an accelerated playback speed, the user's wife dislikes the fast talking, and the prior app, in Android Auto mode, does not provide an easy way to change the playback speed to enable the user to accommodate his wife.  The user's new app has a convenient one-touch playback speed toggle to make it easy to satisfy the wife when she is in the car, and the user when she is not. 


## Running the server

Run these from `server/`:

1. Install [uv](https://docs.astral.sh/uv/). It provides Python 3.12 automatically.
2. `uv sync`
3. `cp .env.example .env`, then set `PODCLATCH_TOKEN` in `.env` to a token of your choosing. The server refuses to start without one.
4. Load the variables and start the server: `set -a; . ./.env; set +a; uv run uvicorn --factory podclatch_server.app:create_app`
5. Check it: `curl http://localhost:8000/healthz` returns `{"status":"ok"}`.

   The server keeps its data in a database and creates or upgrades the schema by itself at startup (no migration command). By default that is the SQLite file `server/podclatch.db` (git-ignored; run from `server/`, or the file is created in whatever directory you start from). To use Postgres, set `DATABASE_URL=postgresql://user:password@host:5432/dbname`. To also run the tests against Postgres, set `PODCLATCH_TEST_DATABASE_URL` to a running database before `uv run pytest`; each test uses its own temporary schema, and without the variable the Postgres tests are skipped.
6. Run the tests: `uv run pytest`
7. Run the linter: `uv run ruff check .`

## Known limitations

These are deliberate trade-offs for a single-user app, recorded so they are not mistaken for oversights.

- **`lastRefreshedAt` can be stale in Sync.** When the server refreshes a Feed and nothing else about it changed, it updates that Feed's `lastRefreshedAt` but does not count it as a change for Sync. Counting it would make every refresh look like a change to every Feed and resend the whole subscription list. The app does not display this field, and `GET /v1/feeds` always returns the current value, so the lag only shows in the phone's local cache. If a screen ever needs it, read it from `GET /v1/feeds` or send it in Sync only when something else changes.
- **Deletion records (tombstones) are never pruned.** Unsubscribing leaves a small record for the Feed and each of its Episodes, so a phone that was offline learns to delete its local copy and audio files. These records are tiny, and only a full re-sync from the beginning transfers all of them, so the cost is negligible at single-user scale. If it ever matters, delete tombstones older than a cutoff (for example 90 days) and make any phone offline longer than that do a full re-sync.

## Status

Pre-development: only the specification and the agent-team documents exist so far. The feature list, screenshots, and run steps will be added when the app and server exist.
