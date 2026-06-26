BASE_DIR := $(shell dirname $(realpath $(firstword $(MAKEFILE_LIST))))
ENV_FILE := $(BASE_DIR)/.env
COMPOSE := docker compose --env-file $(ENV_FILE) -f $(BASE_DIR)/docker-compose.yml

.PHONY: init-env config up down restart logs ps admin status smoke

init-env:
	@test -f $(ENV_FILE) || cp $(BASE_DIR)/.env.example $(ENV_FILE)

config: init-env
	$(COMPOSE) config

up: init-env
	$(COMPOSE) up -d

down:
	$(COMPOSE) down

restart: down up

logs:
	$(COMPOSE) logs -f kong

ps:
	$(COMPOSE) ps

admin:
	@curl -sf http://localhost:$${KONG_ADMIN_PORT:-8001}/status | python3 -m json.tool

status: admin

smoke:
	@curl -sf http://localhost:$${KONG_ADMIN_PORT:-8001}/status > /dev/null && echo "Kong Admin API OK"
	@status=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$${KONG_PROXY_PORT:-8000}/health/account/ready); \
	if [ "$$status" = "200" ]; then \
		echo "Account health route OK"; \
	else \
		echo "WARNING: Account health route returned HTTP $$status (downstream service may be unavailable in partial-stack mode)"; \
	fi
