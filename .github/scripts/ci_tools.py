#!/usr/bin/env python3
"""Helpers for the deploy and promote workflows. Standard library only.

Subcommands:

  health --url URL [--timeout S] [--interval S]
      Poll URL/healthz until it answers 200 with {"status":"ok"}. Exit 1 on timeout.

  wait-service --name NAME [--timeout S] [--interval S] [--grace S]
      Wait until the App Runner service NAME reports RUNNING (uses the `aws` CLI). It first waits up to
      --grace seconds for OPERATION_IN_PROGRESS, because App Runner starts its deployment a little after
      the image tag moves. Exit 1 on a failure status or on timeout.

  select-promotion [--sha SHA]
      Read `aws ecr describe-images` JSON on stdin and choose the image to promote: the one tagged with
      SHA (default: the SHA of the image currently tagged `dev`). Refuse an image that does not carry the
      tag `dev`. Prints `sha=...` and `digest=...` lines, ready for $GITHUB_OUTPUT.

Nothing here prints a secret; only service names, SHAs, digests, and the health URL without a query string.
"""

import argparse
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

SHA_RE = re.compile(r"^[0-9a-f]{40}$")
FAILED_STATUSES = {"CREATE_FAILED", "DELETE_FAILED", "DELETED", "PAUSED"}


class CiError(Exception):
    """A failure with a message that is safe to print."""


def _strip_query(url):
    return url.split("?", 1)[0].split("#", 1)[0]


def fetch_health(url, timeout=5):
    """Return (ok, description) for one GET of url."""
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:  # noqa: S310 - https URL from a variable
            body = response.read().decode("utf-8", "replace")
            if response.status != 200:
                return False, f"HTTP {response.status}"
    except urllib.error.HTTPError as error:
        error.close()
        return False, f"HTTP {error.code}"
    except (urllib.error.URLError, OSError) as error:
        return False, f"no answer ({type(error).__name__})"
    try:
        ok = json.loads(body) == {"status": "ok"}
    except ValueError:
        ok = False
    return (True, "ok") if ok else (False, "HTTP 200 but the body is not {\"status\":\"ok\"}")


def wait_for_health(base_url, timeout, interval, fetch=fetch_health, sleep=time.sleep, clock=time.monotonic, log=print):
    if not base_url or not base_url.startswith(("http://", "https://")):
        raise CiError("The service URL is empty or is not an http(s) URL; set the repository variable it comes from.")
    url = _strip_query(base_url).rstrip("/") + "/healthz"
    deadline = clock() + timeout
    attempt = 0
    while True:
        attempt += 1
        ok, description = fetch(url)
        log(f"health check {attempt}: {url} -> {description}")
        if ok:
            return
        if clock() + interval > deadline:
            raise CiError(f"Timed out after {timeout} s waiting for {url} to answer {{\"status\":\"ok\"}} (last: {description}).")
        sleep(interval)


def wait_for_service(name, get_status, timeout, interval, grace, sleep=time.sleep, clock=time.monotonic, log=print):
    """Wait for RUNNING after the deployment that a moved image tag triggers."""
    start = clock()
    deadline = start + timeout
    seen_in_progress = False
    while True:
        status = get_status(name)
        log(f"App Runner service {name}: {status}")
        if status is None:
            raise CiError(f"No App Runner service named {name} was found. Is the app stack deployed?")
        if status in FAILED_STATUSES:
            raise CiError(f"App Runner service {name} is {status}.")
        if status == "OPERATION_IN_PROGRESS":
            seen_in_progress = True
        elif status == "RUNNING" and (seen_in_progress or clock() - start >= grace):
            if not seen_in_progress:
                log(f"::warning::No deployment was seen starting within {grace} s; continuing with the health check.")
            return
        if clock() + interval > deadline:
            raise CiError(f"Timed out after {timeout} s waiting for {name} to be RUNNING (last: {status}).")
        sleep(interval)


def aws_service_status(name):
    query = f"ServiceSummaryList[?ServiceName=='{name}'].Status | [0]"
    result = subprocess.run(
        ["aws", "apprunner", "list-services", "--query", query, "--output", "text"],
        capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        raise CiError(f"aws apprunner list-services failed: {result.stderr.strip()[:300]}")
    value = result.stdout.strip()
    return None if value in ("", "None") else value


def select_promotion(describe_images, sha=""):
    """Pick the image to promote. describe_images is the parsed `aws ecr describe-images` output."""
    details = describe_images.get("imageDetails", [])
    sha = (sha or "").strip()
    if sha:
        if not SHA_RE.match(sha):
            raise CiError("The sha input must be the full 40-character lowercase commit SHA (or empty).")
        matches = [d for d in details if sha in d.get("imageTags", [])]
        if not matches:
            raise CiError(f"No image tagged {sha} exists in the ECR repository.")
        image = matches[0]
        if "dev" not in image.get("imageTags", []):
            raise CiError(f"Image {sha} is not the one tagged dev; only an image that is running on dev can be promoted.")
    else:
        dev = [d for d in details if "dev" in d.get("imageTags", [])]
        if not dev:
            raise CiError("No image is tagged dev yet, so there is nothing to promote.")
        image = dev[0]
        shas = [t for t in image.get("imageTags", []) if SHA_RE.match(t)]
        if not shas:
            raise CiError("The image tagged dev has no commit SHA tag; refusing to promote an untraceable image.")
        sha = shas[0]
    digest = image.get("imageDigest", "")
    if not digest.startswith("sha256:"):
        raise CiError("The chosen image has no digest.")
    return sha, digest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    health = sub.add_parser("health")
    health.add_argument("--url", required=True)
    health.add_argument("--timeout", type=float, default=600)
    health.add_argument("--interval", type=float, default=10)

    service = sub.add_parser("wait-service")
    service.add_argument("--name", required=True)
    service.add_argument("--timeout", type=float, default=900)
    service.add_argument("--interval", type=float, default=10)
    service.add_argument("--grace", type=float, default=120)

    promotion = sub.add_parser("select-promotion")
    promotion.add_argument("--sha", default="")

    args = parser.parse_args(argv)
    try:
        if args.command == "health":
            wait_for_health(args.url, args.timeout, args.interval)
        elif args.command == "wait-service":
            wait_for_service(args.name, aws_service_status, args.timeout, args.interval, args.grace)
        else:
            try:
                parsed = json.load(sys.stdin)
            except ValueError as error:
                raise CiError("stdin is not the JSON output of `aws ecr describe-images`.") from error
            sha, digest = select_promotion(parsed, args.sha)
            print(f"sha={sha}")
            print(f"digest={digest}")
    except CiError as error:
        print(f"::error::{error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
