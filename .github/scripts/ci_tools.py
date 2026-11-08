#!/usr/bin/env python3
"""Helpers for the deploy and promote workflows. Standard library only.

Subcommands:

  health --url URL [--timeout S] [--interval S]
      Poll URL/healthz until it answers 200 with {"status":"ok"}. Exit 1 on timeout.

  update-service --name NAME --image IMAGE
      Point the ECS Express Mode service NAME (in the default cluster) at IMAGE (uses the `aws` CLI).
      Express Mode does not watch an image tag, so every deploy needs this explicit update; IMAGE should
      carry a tag that has not been used before (the commit SHA), so ECS starts a new deployment.

  wait-service --name NAME [--timeout S] [--interval S] [--settle S]
      Wait until the ECS service NAME has finished deploying: one deployment, completed, with all
      tasks running (uses the `aws` CLI). It first waits --settle seconds so the update that started
      the deployment is visible. Exit 1 on a failed deployment or on timeout.

  select-promotion [--sha SHA]
      Read `aws ecr describe-images` JSON on stdin and choose the image to promote: the one tagged with
      SHA (default: the SHA of the image currently tagged `dev`). Refuse an image that does not carry the
      tag `dev`. Prints `sha=...` and `digest=...` lines, ready for $GITHUB_OUTPUT.

Nothing here prints a secret; only service names, SHAs, digests, and the health URL without a query string.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

SHA_RE = re.compile(r"^[0-9a-f]{40}$")
FAILED_STATUSES = {"FAILED", "DRAINING", "INACTIVE"}
CLUSTER = "default"  # Express Mode services live in the default ECS cluster


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


def wait_for_service(name, get_status, timeout, interval, settle=0, sleep=time.sleep, clock=time.monotonic, log=print):
    """Wait for COMPLETED after the update that points the service at a new image."""
    deadline = clock() + timeout
    if settle:
        sleep(settle)
    while True:
        status = get_status(name)
        log(f"ECS service {name}: {status}")
        if status is None:
            raise CiError(f"No ECS service named {name} was found. Is the app stack deployed?")
        if status in FAILED_STATUSES:
            raise CiError(f"ECS service {name} is {status}.")
        if status == "COMPLETED":
            return
        if clock() + interval > deadline:
            raise CiError(f"Timed out after {timeout} s waiting for {name} to finish deploying (last: {status}).")
        sleep(interval)


def summarize_service(described):
    """Reduce `aws ecs describe-services` JSON to None (no such service), FAILED, a bad service status,
    IN_PROGRESS, or COMPLETED (a single deployment that finished, with every task running)."""
    services = described.get("services", [])
    if not services:
        return None
    service = services[0]
    if service.get("status") != "ACTIVE":
        return service.get("status") or "INACTIVE"
    deployments = service.get("deployments", [])
    primary = next((d for d in deployments if d.get("status") == "PRIMARY"), None)
    rollout = (primary or {}).get("rolloutState")
    if rollout == "FAILED":
        return "FAILED"
    finished = rollout in (None, "COMPLETED")
    settled = len(deployments) == 1 and service.get("runningCount") == service.get("desiredCount")
    return "COMPLETED" if finished and settled else "IN_PROGRESS"


def _aws(args):
    result = subprocess.run(["aws", *args], capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise CiError(f"aws {' '.join(args[:2])} failed: {result.stderr.strip()[:300]}")
    return result.stdout


def aws_service_status(name):
    return summarize_service(json.loads(_aws(["ecs", "describe-services", "--cluster", CLUSTER, "--services", name, "--output", "json"])))


def service_arn(name, account, region):
    return f"arn:aws:ecs:{region}:{account}:service/{CLUSTER}/{name}"


def update_service(name, image, run=_aws, log=print):
    """Start a deployment of IMAGE on the Express Mode service NAME."""
    region = os.environ.get("AWS_REGION") or os.environ.get("AWS_DEFAULT_REGION")
    if not region:
        raise CiError("AWS_REGION is not set.")
    account = run(["sts", "get-caller-identity", "--query", "Account", "--output", "text"]).strip()
    arn = service_arn(name, account, region)
    run([
        "ecs", "update-express-gateway-service", "--service-arn", arn,
        "--primary-container", json.dumps({"image": image}), "--output", "json",
    ])
    log(f"Started a deployment of {image} on {name}.")


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

    update = sub.add_parser("update-service")
    update.add_argument("--name", required=True)
    update.add_argument("--image", required=True)

    service = sub.add_parser("wait-service")
    service.add_argument("--name", required=True)
    service.add_argument("--timeout", type=float, default=900)
    service.add_argument("--interval", type=float, default=10)
    service.add_argument("--settle", type=float, default=20)

    promotion = sub.add_parser("select-promotion")
    promotion.add_argument("--sha", default="")

    args = parser.parse_args(argv)
    try:
        if args.command == "health":
            wait_for_health(args.url, args.timeout, args.interval)
        elif args.command == "update-service":
            update_service(args.name, args.image)
        elif args.command == "wait-service":
            wait_for_service(args.name, aws_service_status, args.timeout, args.interval, args.settle)
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
