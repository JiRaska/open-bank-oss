BASE_DIR := $(shell dirname $(realpath $(firstword $(MAKEFILE_LIST))))
COMPOSE := docker compose -f $(BASE_DIR)/docker-compose.yml
COMPOSE_PARALLEL_LIMIT ?= 2
APP_SERVICES := account-service ledger-service transaction-service pid-service consent-service sca-service psd2-service tpp-registry-service balance-service agent-service party-service notification-service audit-service kyc-service sepa-payment domestic-payment aml-service api-gateway admin-ui

.PHONY: up up-infra up-all down down-v logs logs-app ps restart status kafka-topics db-connect vault-status health health-all build clean

up: up-infra

up-infra:
	$(COMPOSE) up -d --wait postgres kafka schema-registry valkey vault keycloak prometheus grafana loki tempo kafka-ui

up-all:
	@for service in $(APP_SERVICES); do \
		echo "=== Building $$service ==="; \
		$(COMPOSE) build $$service || exit $$?; \
	done
	$(COMPOSE) up -d --wait

build:
	@for service in $(APP_SERVICES); do \
		echo "=== Building $$service ==="; \
		$(COMPOSE) build $$service || exit $$?; \
	done

down:
	$(COMPOSE) down

down-v:
	$(COMPOSE) down -v

logs:
	$(COMPOSE) logs -f

logs-app:
	$(COMPOSE) logs -f account-service ledger-service transaction-service pid-service consent-service sca-service psd2-service tpp-registry-service balance-service agent-service party-service notification-service audit-service kyc-service sepa-payment domestic-payment aml-service api-gateway admin-ui

ps:
	$(COMPOSE) ps

restart: down up

status:
	$(COMPOSE) ps

kafka-topics:
	$(COMPOSE) exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

db-connect:
	$(COMPOSE) exec postgres psql -U openbank

vault-status:
	@curl -s http://localhost:8200/v1/sys/health | python3 -m json.tool

health:
	@echo "=== PostgreSQL ===" && $(COMPOSE) exec postgres pg_isready -U openbank && echo "OK"
	@echo "=== Kafka ===" && docker inspect openbank-kafka --format='{{.State.Health.Status}}' | grep -q healthy && echo "OK"
	@echo "=== Schema Registry ===" && curl -sf http://localhost:8081/apis/registry/v2/search/artifacts && echo "OK"
	@echo "=== Valkey ===" && docker exec openbank-valkey valkey-cli -a $${VALKEY_PASSWORD} ping
	@echo "=== Vault ===" && curl -sf http://localhost:8200/v1/sys/health | python3 -c "import sys,json; d=json.load(sys.stdin); print('OK' if not d.get('sealed') else 'SEALED')"
	@echo "=== Keycloak ===" && curl -sf http://localhost:8080/realms/master > /dev/null && echo "OK"
	@echo "=== Prometheus ===" && curl -sf http://localhost:9090/-/healthy && echo "OK"
	@echo "=== Grafana ===" && curl -sf http://localhost:3001/api/health && echo "OK"

health-all: health
	@echo "=== Account Service ===" && curl -sf http://localhost:8100/q/health/ready && echo "OK"
	@echo "=== Ledger Service ===" && curl -sf http://localhost:8101/q/health/ready && echo "OK"
	@echo "=== Transaction Service ===" && curl -sf http://localhost:8102/q/health/ready && echo "OK"
	@echo "=== Balance Service ===" && curl -sf http://localhost:8103/q/health/ready && echo "OK"
	@echo "=== PID Service ===" && curl -sf http://localhost:8105/q/health/ready && echo "OK"
	@echo "=== Consent Service ===" && curl -sf http://localhost:8106/q/health/ready && echo "OK"
	@echo "=== PSD2 Service ===" && curl -sf http://localhost:8107/q/health/ready && echo "OK"
	@echo "=== TPP Registry ===" && curl -sf http://localhost:8108/q/health/ready && echo "OK"
	@echo "=== Agent Service (MCP) ===" && curl -sf http://localhost:8109/q/health/ready && echo "OK"
	@echo "=== SCA Service ===" && curl -sf http://localhost:8110/q/health/ready && echo "OK"
	@echo "=== Party Service ===" && curl -sf http://localhost:8111/q/health/ready && echo "OK"
	@echo "=== Notification Service ===" && curl -sf http://localhost:8112/q/health/ready && echo "OK"
	@echo "=== Audit Service ===" && curl -sf http://localhost:8113/q/health/ready && echo "OK"
	@echo "=== KYC Service ===" && curl -sf http://localhost:8114/q/health/ready && echo "OK"
	@echo "=== SEPA Payment ===" && curl -sf http://localhost:8115/q/health/ready && echo "OK"
	@echo "=== Domestic Payment ===" && curl -sf http://localhost:8116/q/health/ready && echo "OK"
	@echo "=== AML Service ===" && curl -sf http://localhost:8117/q/health/ready && echo "OK"
	@echo "=== API Gateway Admin ===" && curl -sf http://localhost:8001/status > /dev/null && echo "OK"
	@echo "=== API Gateway Proxy (SEPA Health) ===" && curl -sf http://localhost:8000/health/sepa/ready && echo "OK"
	@echo "=== Gatus Status Page ===" && curl -sf http://localhost:8091/health && echo "OK"
	@echo "=== Admin UI ===" && curl -sf http://localhost:3000/ > /dev/null && echo "OK"
	@echo ""
	@echo "Status dashboard: http://localhost:8091"

clean: down-v
	docker system prune -f
