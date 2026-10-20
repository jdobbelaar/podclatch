.PHONY: up down logs test test-integration clean

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

clean:
	docker compose down -v --remove-orphans
