"""Runs the end-to-end test (US-32) against a real server stack. Standard library only.

    make test-e2e          (or: python -I android/tools/e2e.py [--logs-dir DIR] [--remove-image])

What it does, in order, and always cleans up, pass or fail:

 1. Picks the one device: refuses unless `ANDROID_SERIAL` is set or exactly one device is attached. Every
    adb call names that serial.
 2. Writes a scratch env file (random token and password) in a temporary folder and starts the stack as the
    Compose project `podclatch-e2e` (docker-compose.yaml + docker-compose.integration.yaml +
    docker-compose.e2e.yaml): image `podclatch-server:e2e`, host port 18180 (PODCLATCH_E2E_PORT overrides it).
    Before starting anything it checks the resolved Compose configuration and refuses if it would use the
    dev stack's project name, port, image, or volume.
 3. Seeds a fake feed of three short tone Episodes on the fake feed host (seed_feed.py) and starts
    audio_fixture.py on 127.0.0.1:8191.
 4. Runs `adb reverse` for the server port and the audio port, then `./gradlew connectedDebugAndroidTest`
    filtered to com.podclatch.EndToEndTest, passing the stack parameters as instrumentation arguments.
 5. Removes only what it created: the Compose project `podclatch-e2e` with its volume, the audio fixture
    process (by PID), and the `adb reverse` rules it added. `--remove-image` also removes the image
    `podclatch-server:e2e`.

It never touches the dev stack (project `podclatch`, port 8180, its volume, or the image tagged `:local`),
and never starts or stops the adb server. `--logs-dir DIR` writes the containers' logs and the audio
fixture's log there (for CI to upload). The token and password exist only in the scratch file, in the
Gradle command line, and in the app's test arguments; every line this script prints is redacted.
"""

from __future__ import annotations

import argparse
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parents[1]
sys.path.insert(0, str(TOOLS))

import seed_feed  # noqa: E402

PROJECT = "podclatch-e2e"
IMAGE = "podclatch-server:e2e"
DEFAULT_PORT = 18180
AUDIO_PORT = 8191
HOST = "127.0.0.1"
FEED_NAME = seed_feed.DEFAULT_NAME
COMPOSE_FILES = ("docker-compose.yaml", "docker-compose.integration.yaml", "docker-compose.e2e.yaml")
TEST_CLASS = "com.podclatch.EndToEndTest"
# The dev stack's host port, refused. (The guard also requires the project name, image, and volumes of this test.)
FORBIDDEN_PORT = "8180"


class Refused(Exception):
    """A precondition failed; nothing was started."""


# --- devices -------------------------------------------------------------------------------------


def find_adb() -> str:
    found = shutil.which("adb")
    if found:
        return found
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(variable)
        if root:
            for name in ("adb.exe", "adb"):
                candidate = Path(root) / "platform-tools" / name
                if candidate.is_file():
                    return str(candidate)
    raise Refused("adb was not found: put it on PATH or set ANDROID_HOME. Nothing was started.")


def parse_devices(output: str) -> list[str]:
    """The serials of the devices in state `device` from `adb devices` output."""
    serials = []
    for line in output.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def choose_device(devices: list[str], requested: str | None) -> str:
    if requested:
        if requested not in devices:
            raise Refused("ANDROID_SERIAL is set but that device is not attached and ready. Nothing was started.")
        return requested
    if not devices:
        raise Refused("No device or emulator is attached. Start an emulator and run again. Nothing was started.")
    if len(devices) > 1:
        raise Refused(
            "%d devices are attached and ANDROID_SERIAL is not set. Set ANDROID_SERIAL to the emulator "
            "(for example emulator-5554) so a real phone is never used. Nothing was started." % len(devices)
        )
    return devices[0]


# --- the Compose guard ---------------------------------------------------------------------------


def check_compose_config(config: dict, port: str) -> None:
    """Refuses unless the resolved configuration is the e2e project's and nothing of the dev stack's."""
    if config.get("name") != PROJECT:
        raise Refused("The Compose project resolves to the wrong name. Nothing was started.")
    server = (config.get("services") or {}).get("server") or {}
    if server.get("image") != IMAGE:
        raise Refused("The server image does not resolve to %s. Nothing was started." % IMAGE)
    published = [str(p.get("published")) for p in server.get("ports") or []]
    if published != [port]:
        raise Refused("The server must publish exactly host port %s. Nothing was started." % port)
    if port == FORBIDDEN_PORT:
        raise Refused("Host port %s belongs to the dev stack. Nothing was started." % FORBIDDEN_PORT)
    for name, volume in (config.get("volumes") or {}).items():
        resolved = volume.get("name", "")
        if not resolved.startswith(PROJECT + "_"):
            raise Refused("Volume %s does not belong to the %s project. Nothing was started." % (name, PROJECT))


# --- helpers -------------------------------------------------------------------------------------


class Run:
    """Holds the secrets so that everything printed can be redacted."""

    def __init__(self, token: str, password: str) -> None:
        self.token, self.password = token, password

    def redact(self, text: str) -> str:
        for secret in (self.token, self.password):
            if secret:
                text = text.replace(secret, "***")
        return text

    def say(self, message: str) -> None:
        print("[e2e] " + self.redact(message), flush=True)

    def stream(self, command: list[str], cwd: Path, env: dict[str, str]) -> int:
        """Runs a command, printing its output redacted and line by line."""
        process = subprocess.Popen(
            command, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            encoding="utf-8", errors="replace",
        )
        assert process.stdout is not None
        for line in process.stdout:
            print(self.redact(line.rstrip("\n")), flush=True)
        return process.wait()


def port_in_use(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(1.0)
        if probe.connect_ex((HOST, port)) == 0:
            return True
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind((HOST, port))
        except OSError:
            return True
    return False


def wait_for_port(port: int, seconds: float) -> bool:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(1.0)
            if probe.connect_ex((HOST, port)) == 0:
                return True
        time.sleep(0.2)
    return False


def gradle_wrapper() -> str:
    return str(ROOT / "android" / ("gradlew.bat" if os.name == "nt" else "gradlew"))


# --- main ----------------------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the end-to-end test against its own Compose stack.")
    parser.add_argument("--logs-dir", type=Path, help="write the container and fixture logs here")
    parser.add_argument("--remove-image", action="store_true", help="also remove the image %s afterwards" % IMAGE)
    args = parser.parse_args(argv)

    try:
        port = os.environ.get("PODCLATCH_E2E_PORT") or str(DEFAULT_PORT)
        if not port.isdigit() or port == FORBIDDEN_PORT:
            raise Refused("PODCLATCH_E2E_PORT must be a number other than %s. Nothing was started." % FORBIDDEN_PORT)
        adb = find_adb()
        listing = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=30)
        serial = choose_device(parse_devices(listing.stdout), os.environ.get("ANDROID_SERIAL") or None)
        for number in (int(port), AUDIO_PORT):
            if port_in_use(number):
                raise Refused("Host port %d is already in use. Nothing was started." % number)
        reverse_before = subprocess.run([adb, "-s", serial, "reverse", "--list"], capture_output=True, text=True, timeout=30)
        for number in (int(port), AUDIO_PORT):
            if "tcp:%d" % number in reverse_before.stdout:
                raise Refused("The device already has an adb reverse rule for tcp:%d. Nothing was started." % number)
    except Refused as refusal:
        print("[e2e] REFUSED: %s" % refusal, file=sys.stderr)
        return 2

    token, password = secrets.token_hex(16), secrets.token_hex(16)
    run = Run(token, password)
    if os.environ.get("GITHUB_ACTIONS") == "true":
        # Masks the throwaway values in the workflow log before anything can print them.
        print("::add-mask::" + token, flush=True)
        print("::add-mask::" + password, flush=True)
    scratch = Path(tempfile.mkdtemp(prefix="podclatch-e2e-"))
    env_file = scratch / "e2e.env"
    env_file.write_text(
        "PODCLATCH_TOKEN=%s\nPOSTGRES_PASSWORD=%s\nPODCLATCH_PORT=%s\n" % (token, password, port), encoding="utf-8"
    )
    # The process environment wins over --env-file in Compose, so set the same values there too; that keeps
    # a PODCLATCH_PORT or token exported in the caller's shell from leaking into this stack.
    env = dict(os.environ)
    env.update(PODCLATCH_TOKEN=token, POSTGRES_PASSWORD=password, PODCLATCH_PORT=port, PODCLATCH_BIND_ADDRESS=HOST)
    for name in ("POSTGRES_USER", "POSTGRES_DB", "ITUNES_SEARCH_URL", "REFRESH_INTERVAL_HOURS", "LOG_LEVEL", "COMPOSE_PROJECT_NAME"):
        env.pop(name, None)
    compose = ["docker", "compose", "-p", PROJECT, "--env-file", str(env_file)]
    for name in COMPOSE_FILES:
        compose += ["-f", str(ROOT / name)]

    fixture: subprocess.Popen | None = None
    fixture_log: list[str] = []
    reversed_ports: list[int] = []
    started_stack = False
    result = 1
    try:
        config = subprocess.run(compose + ["config", "--format", "json"], capture_output=True, text=True, env=env, cwd=ROOT, timeout=120)
        if config.returncode != 0:
            raise Refused("docker compose could not resolve the configuration: " + run.redact(config.stderr.strip()[:500]))
        check_compose_config(json.loads(config.stdout), port)

        run.say("starting the stack (project %s, port %s, image %s)" % (PROJECT, port, IMAGE))
        started_stack = True
        if run.stream(compose + ["up", "-d", "--build", "--wait"], ROOT, env) != 0:
            raise RuntimeError("the stack did not become healthy")

        mapped = subprocess.run(compose + ["port", "feedhost", "8080"], capture_output=True, text=True, env=env, cwd=ROOT, timeout=60)
        feedhost_port = mapped.stdout.strip().rsplit(":", 1)[-1]
        if mapped.returncode != 0 or not feedhost_port.isdigit():
            raise RuntimeError("could not find the fake feed host's port")
        seed_feed.put_feed("http://%s:%s" % (HOST, feedhost_port), FEED_NAME, seed_feed.build_feed("http://%s:%d" % (HOST, AUDIO_PORT)))

        fixture = subprocess.Popen(
            [sys.executable, "-I", str(TOOLS / "audio_fixture.py"), "--port", str(AUDIO_PORT)],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env,
        )
        threading.Thread(target=lambda: [fixture_log.append(line.rstrip()) for line in fixture.stdout], daemon=True).start()
        if not wait_for_port(AUDIO_PORT, 15):
            raise RuntimeError("the audio fixture did not start")

        for number in (int(port), AUDIO_PORT):
            added = subprocess.run([adb, "-s", serial, "reverse", "tcp:%d" % number, "tcp:%d" % number], capture_output=True, text=True, timeout=30)
            if added.returncode != 0:
                raise RuntimeError("adb reverse failed for tcp:%d" % number)
            reversed_ports.append(number)

        prefix = "-Pandroid.testInstrumentationRunnerArguments."
        gradle = [
            gradle_wrapper(), "connectedDebugAndroidTest", "--console=plain",
            prefix + "class=" + TEST_CLASS,
            prefix + "serverUrl=http://%s:%s" % (HOST, port),
            prefix + "token=" + token,
            prefix + "feedUrl=http://feedhost:8080/feeds/" + FEED_NAME,
            prefix + "audioBase=http://%s:%d" % (HOST, AUDIO_PORT),
        ]
        genv = dict(env, ANDROID_SERIAL=serial)
        run.say("running the end-to-end test on %s" % serial)
        result = run.stream(gradle, ROOT / "android", genv)
        run.say("the end-to-end test %s" % ("PASSED" if result == 0 else "FAILED (gradle exit %d)" % result))
    except Refused as refusal:
        print("[e2e] REFUSED: %s" % run.redact(str(refusal)), file=sys.stderr)
        result = 2
    except Exception as error:  # noqa: BLE001 - report and fall through to the cleanup
        run.say("ERROR: %s" % error)
        result = 1
    finally:
        if args.logs_dir:
            args.logs_dir.mkdir(parents=True, exist_ok=True)
            if started_stack:
                logs = subprocess.run(compose + ["logs", "--no-color"], capture_output=True, text=True, env=env, cwd=ROOT, timeout=120)
                (args.logs_dir / "containers.log").write_text(run.redact(logs.stdout + logs.stderr), encoding="utf-8")
            (args.logs_dir / "audio-fixture.log").write_text(run.redact("\n".join(fixture_log)), encoding="utf-8")
        for number in reversed_ports:
            subprocess.run([adb, "-s", serial, "reverse", "--remove", "tcp:%d" % number], capture_output=True, timeout=30)
        if fixture is not None:
            fixture.terminate()
            try:
                fixture.wait(10)
            except subprocess.TimeoutExpired:
                fixture.kill()
        if started_stack:
            run.say("removing the stack %s with its volume" % PROJECT)
            subprocess.run(compose + ["down", "-v", "--remove-orphans"], capture_output=True, env=env, cwd=ROOT, timeout=300)
            if args.remove_image:
                subprocess.run(["docker", "image", "rm", IMAGE], capture_output=True, timeout=120)
        shutil.rmtree(scratch, ignore_errors=True)
    return result


if __name__ == "__main__":
    sys.exit(main())
