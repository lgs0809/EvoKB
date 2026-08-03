MVN ?= mvn
NPM ?= npm
NODE ?= node
DOCKER ?= docker
COMPOSE ?= docker compose
COMPOSE_FILE ?= deploy/docker-compose.yml
COMPOSE_ENV_FILE ?= .env
IMAGE ?= evokb:local

.PHONY: verify backend frontend security compose-config docker-build up stop logs

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
	$(COMPOSE) --env-file .env.example -f $(COMPOSE_FILE) config -q

docker-build:
	$(DOCKER) build -f deploy/Dockerfile -t $(IMAGE) .

up:
	$(COMPOSE) --env-file $(COMPOSE_ENV_FILE) -f $(COMPOSE_FILE) up -d --build

stop:
	$(COMPOSE) --env-file $(COMPOSE_ENV_FILE) -f $(COMPOSE_FILE) stop

logs:
	$(COMPOSE) --env-file $(COMPOSE_ENV_FILE) -f $(COMPOSE_FILE) logs -f app
