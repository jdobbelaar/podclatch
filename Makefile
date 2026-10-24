.PHONY: up down logs test test-integration test-e2e clean

up:
	docker compose up -d --build --wait

down:
	docker compose down

logs:
	docker compose logs -f server postgres

test:
	docker compose -f docker-compose.test.yaml run --rm --build test

test-integration:
	uv run --directory server pytest tests_integration

# End-to-end test (US-32): the real app on the attached emulator against the real server. Needs exactly one
# device attached, or ANDROID_SERIAL set (use the emulator, never a real phone). Starts and removes its own
# Compose project `podclatch-e2e` (port 18180, image podclatch-server:e2e, a scratch token), seeds a fake
# feed and audio, and never touches the stack that `make up` starts. Extra flags: E2E_ARGS="--logs-dir logs".
test-e2e:
	python3 -I android/tools/e2e.py $(E2E_ARGS)

clean:
	docker compose down -v --remove-orphans
