MVN ?= mvn
NPM ?= npm
NODE ?= node
DOCKER ?= docker
COMPOSE ?= docker compose

.PHONY: verify backend frontend security compose-config docker-build

verify: backend frontend security compose-config

backend:
	$(MVN) -f java/pom.xml clean verify

frontend:
	cd web && $(NPM) ci
	cd web && $(NPM) run lint
	cd web && $(NPM) run typecheck
	cd web && $(NPM) run build
	cd web && $(NPM) audit --audit-level=low

security:
	$(NODE) scripts/release-hygiene.mjs

compose-config:
	$(COMPOSE) --env-file .env.example -f deploy/docker-compose.yml config -q

docker-build:
	$(DOCKER) build -f deploy/Dockerfile -t evokb:local .
