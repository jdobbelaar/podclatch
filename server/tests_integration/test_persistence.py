"""Data survives a server restart and a full down/up of the stack (no volume removal)."""

from __future__ import annotations

import harness
import support
from support import T1, item


def _build_data(api, new_feed) -> list[int]:
    """Two Feeds (subscribed one after the other, so the order changed), one Feed unsubscribed
    (so tombstones exist), and one Episode PLAYED with a position. Returns every id used."""
    first, _, _ = new_feed([item(1), item(2)])
    second, _, _ = new_feed([item(1), item(2), item(3)])
    doomed, _, _ = new_feed([item(1), item(2)])
    assert api.delete(f"/v1/feeds/{doomed['id']}").status_code == 204
    episode = support.episodes_of(api, first["id"])[0]
    support.push(api, [support.state_item(episode["id"], "PLAYED", T1, 900)])
    return [first["id"], second["id"], doomed["id"]]


def _assert_empty_after(api, snap) -> None:
    since = snap["sync"]["nextRev"]
    page = support.sync(api, since)
    assert page["feeds"] == page["episodes"] == page["tombstones"] == []
    assert page["hasMore"] is False
    assert page["nextRev"] == since


def _assert_new_ids_continue(api, new_feed, snap, used_ids) -> None:
    fresh, _, _ = new_feed([item(1)])
    known = [f["id"] for f in snap["feeds"]] + used_ids
    assert fresh["id"] > max(known)
    assert fresh["rev"] > snap["sync"]["nextRev"]


def test_restart_keeps_all_data(api, new_feed):
    used = _build_data(api, new_feed)
    snap = support.snapshot(api)
    assert len(snap["feeds"]) >= 2

    harness.compose("restart", "server")
    api.wait_healthy(60)

    assert support.snapshot(api) == snap
    _assert_empty_after(api, snap)
    _assert_new_ids_continue(api, new_feed, snap, used)


def test_data_survives_down_and_up(api, new_feed, feedhost):
    used = _build_data(api, new_feed)
    snap = support.snapshot(api)

    harness.compose("down")  # no -v: the Postgres volume stays
    harness.invalidate_feedhost_port()
    harness.compose("up", "-d", "--wait")
    api.wait_healthy(60)

    assert support.snapshot(api) == snap
    _assert_empty_after(api, snap)

    # The suite keeps working against the new stack; the fake host has a new port and no feeds.
    _assert_new_ids_continue(api, new_feed, snap, used)
