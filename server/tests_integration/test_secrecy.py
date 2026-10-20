"""Feed URL secrecy (decision #27): a query string never reaches the server logs."""

from __future__ import annotations

import uuid

import harness
import support
from support import feed_url, item


def test_query_string_never_reaches_the_server_logs(api, feedhost, unique_name, cleanup):
    canary = f"canary-{uuid.uuid4().hex}"  # made up by this test; not a secret
    url = f"{feed_url(unique_name)}?token={canary}"
    feedhost(unique_name, support.rss(f"Secrecy {unique_name}", [item(1), item(2)]))

    feed = support.subscribe(api, cleanup, url)
    assert api.refresh(f"/v1/feeds/{feed['id']}/refresh").json() == [
        {"feedId": feed["id"], "ok": True}
    ]
    assert api.refresh().status_code == 200
    assert api.delete(f"/v1/feeds/{feed['id']}").status_code == 204

    logs = harness.compose("logs", "--no-color", "server").stdout
    has_logs = bool(logs.strip())  # the check is only meaningful if the server logged something
    assert has_logs
    has_canary = canary in logs
    has_full_url = url in logs
    assert not has_canary
    assert not has_full_url
