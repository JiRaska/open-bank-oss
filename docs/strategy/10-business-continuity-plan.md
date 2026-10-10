# Business Continuity Plan (BCP)

> **Document ID:** OB-BCP-001  
> **Version:** 1.0  
> **Last updated:** 2026-10-10
> **Status:** Reference runbook — historical Docker exercise; current deployment recovery evidence required
> **Owner:** Platform Engineering  
> **Review cycle:** Quarterly internal review, and after material changes or incidents

---

## Regulatory Basis

Applicability depends on the legal entity, service and deployment. This historical Docker
runbook is not an approved legal mapping or evidence of production compliance. For pension deployments, track configurable jurisdiction-specific requirements in
[#12475](https://github.com/JiRaska/open-bank-oss/issues/12475).

Where applicable, [DORA](https://eur-lex.europa.eu/eli/reg/2022/2554/oj) covers continuity and
response/recovery in Article 11, backup/restoration in Article 12, incident management in
Article 17, classification in Article 18 and reporting in Article 19.
[GDPR Article 32](https://eur-lex.europa.eu/eli/reg/2016/679/oj/) addresses security and resilience
of personal-data processing. Banking, payment and card-specific obligations need their own
applicability assessment; the presence of a service in this stack does not establish one.

---

## 1. Scope

This document covers the **OpenBank Docker stack** — all 39 containerized services constituting the banking platform. It defines:

1. **Service criticality tiers** — regulatory and business priority classification
2. **Startup dependency graph** — verified actual dependencies (not theoretical)
3. **Prioritized recovery sequence** — 5 tiers, maximum parallelism within each tier
4. **RTO/RPO targets** — per tier, aligned to `05-resilience-design.md`
5. **Operational runbooks** — concrete `docker compose` commands for each scenario

---

## 2. Service Criticality Classification

### Tier 0 — Critical Infrastructure (Prerequisites)
*Must be healthy before ANY application service starts. No business function is possible without these.*

| Service | Container | Port | Control purpose (not a legal mapping) |
|---|---|---|---|
| PostgreSQL | `openbank-postgres` | 5432 | All services — data persistence |
| Apache Kafka | `openbank-kafka` | 9092 | event durability |
| Keycloak (IAM) | `openbank-keycloak` | 8080 | authentication |
| HashiCorp Vault | `openbank-vault` | 8200 | key management |
| Valkey (Redis) | `openbank-valkey` | 6379 | Idempotency — PSD2 duplicate prevention |
| Schema Registry | `openbank-schema-registry` | 8081 | Kafka schema validation |

**Design note:** Tier 0 provides shared dependencies. Confirm the actual critical-function dependency graph and restoration order for the selected deployment.

---

### Tier 1 — Core Ledger & Identity
*Double-entry accounting integrity and customer identity. No payment can be processed without these.*

| Service | Container | Port | Depends on | Control purpose (not a legal mapping) |
|---|---|---|---|---|
| Account Service | `openbank-account-service` | 8100 | Postgres, Kafka, Keycloak, Vault | CNB § 4 — account management |
| Ledger Service | `openbank-ledger-service` | 8101 | Postgres, Kafka, Keycloak, Vault | CNB § 4 — double-entry ledger |
| Transaction Service | `openbank-transaction-service` | 8102 | Postgres, Kafka, Keycloak, Vault, Valkey | transaction logging |
| Party Service | `openbank-party-service` | — | Postgres, Kafka, Keycloak | customer data |
| Audit Service | `openbank-audit-service` | — | Postgres, Kafka | immutable audit trail |

**Design note:** Tier 1 groups ledger integrity and incident evidence. Validate their dependencies and recovery order in the business-impact assessment.

**Parallelism:** All 5 services start simultaneously — no inter-dependencies within this tier.

---

### Tier 2 — Balance, Compliance & Risk
*Real-time balance projection and regulatory compliance screening. Required before any payment initiation.*

| Service | Container | Port | Depends on | Control purpose (not a legal mapping) |
|---|---|---|---|---|
| Balance Service | `openbank-balance-service` | 8103 | Postgres, Kafka, Keycloak, Valkey | CNB — real-time balance |
| AML Service | `openbank-aml-service` | — | Postgres, Kafka, Keycloak, Valkey | transaction monitoring |
| Sanctions Service | `openbank-sanctions-service` | — | Postgres, Kafka, Keycloak, Valkey | sanctions screening |
| KYC Service | `openbank-kyc-service` | — | Postgres, Kafka, Keycloak | customer due diligence |
| Security Scanner | `openbank-security-scanner` | — | Keycloak | continuous security testing |
| Notification Service | `openbank-notification-service` | — | Postgres, Kafka, Keycloak, Valkey | breach notification |

**Design note:** This stack orders screening and monitoring before dependent payment flows. Validate the actual enforcement and approved degraded-mode policy; this ordering is not a general statutory startup gate.

**Parallelism:** All 6 services start simultaneously.

---

### Tier 3 — PSD2 Open Banking & SCA
*Strong Customer Authentication and Open Banking APIs. Required for any customer-initiated payment.*

| Service | Container | Port | Depends on | Control purpose (not a legal mapping) |
|---|---|---|---|---|
| SCA Service | `openbank-sca-service` | 8110 | Postgres, Kafka, Keycloak, Valkey | strong customer authentication |
| Consent Service | `openbank-consent-service` | 8106 | Postgres, Kafka, Keycloak, Valkey | payment consent |
| TPP Registry | `openbank-tpp-registry-service` | 8108 | Postgres, Keycloak, Valkey | third-party provider validation |
| PSD2 Service | `openbank-psd2-service` | 8107 | Kafka, Keycloak, Valkey, Consent | AISP/PISP APIs |
| PID Service | `openbank-pid-service` | — | Postgres, Keycloak | eIDAS 2.0 — payment instrument directory |

**Design note:** Apply the authentication/consent requirements of the selected payment flow and its approved exemptions. Verify the deployed dependency checks; this Docker sequence does not prove that every payment requires or receives SCA.

**Parallelism:** SCA, Consent, TPP Registry, PID start simultaneously. PSD2 Service starts after Consent Service is healthy.

---

### Tier 4 — Payment Processing
*Actual payment execution. All compliance gates (AML, Sanctions, SCA, Consent) must be healthy first.*

| Service | Container | Port | Depends on | Control purpose (not a legal mapping) |
|---|---|---|---|---|
| Domestic Payment | `openbank-domestic-payment` | — | Postgres, Kafka, Keycloak, Valkey | CNB — domestic payment rails |
| SEPA Payment | `openbank-sepa-payment` | — | Postgres, Kafka, Keycloak, Valkey | PSD2 — SEPA Credit Transfer |
| SEPA Instant | `openbank-sepa-instant-service` | — | Postgres, Kafka, Keycloak, Valkey | PSD2 — SEPA Instant (SCT Inst) |
| SWIFT Service | `openbank-swift-service` | — | Postgres, Kafka, Keycloak, Valkey | CNB — international payments |
| FX Service | `openbank-fx-service` | — | Postgres, Kafka, Keycloak, Valkey | CNB — foreign exchange |
| Clearing Service | `openbank-clearing-service` | — | Postgres, Kafka, Keycloak, Valkey | CNB — clearing & settlement |
| Standing Order | `openbank-standing-order-service` | — | Postgres, Kafka, Keycloak, Valkey | PSD2 — recurring payments |
| Card Issuance | `openbank-card-issuance-service` | — | Postgres (`openbank_cards`), Kafka, Keycloak, Valkey | card data |

**Operational dependency policy:** this runbook places AML/Sanctions dependencies before payment
services. Validate that dependency and its enforcement for the selected payment flow and approved
risk policy; the startup sequence is not itself a general statutory requirement. Where DORA applies,
Article 12(6) requires recovery objectives to reflect function criticality and agreed service levels;
it does not set a universal 15–30 minute RTO for payment services.
[Source: DORA Article 12](https://eur-lex.europa.eu/eli/reg/2022/2554/oj).

**Parallelism:** All 8 services start simultaneously.

---

### Tier 5 — Customer Operations & Observability
*Dispute management, interest calculation, and operator tooling. Non-blocking for payment processing.*

| Service | Container | Port | Depends on | Control purpose (not a legal mapping) |
|---|---|---|---|---|
| Dispute Service | `openbank-dispute-service` | — | Postgres, Kafka, Keycloak, Valkey | chargeback handling |
| Interest Service | `openbank-interest-service` | — | Postgres, Kafka, Keycloak, Valkey | CNB — interest accrual |
| Admin UI | `openbank-admin-ui` | 3000 | Keycloak | Operator console |
| Grafana | `openbank-grafana` | 3001 | Prometheus, Loki, Tempo | monitoring |
| Prometheus | `openbank-prometheus` | 9090 | — | metrics |
| Loki | `openbank-loki` | — | — | log retention |
| Tempo | `openbank-tempo` | — | — | distributed tracing |
| Kafka UI | `openbank-kafka-ui` | 8090 | Kafka | Operational tooling |
| Mailhog | `openbank-mailhog` | 8025 | — | Dev/test email capture |

**Parallelism:** All services start simultaneously.

---

## 3. Startup Dependency Graph

```
TIER 0 (Prerequisites — must be healthy first)
├── postgres ──────────────────────────────────────────────────────────┐
├── kafka ─────────────────────────────────────────────────────────────┤
├── keycloak (depends: postgres) ──────────────────────────────────────┤
├── vault (depends: postgres) ─────────────────────────────────────────┤
├── valkey ─────────────────────────────────────────────────────────────┤
└── schema-registry (depends: kafka) ────────────────────────────────────┘
                                                                        │
TIER 1 (Core Ledger — all parallel)                                     ▼
├── account-service ─────────────────────────────────────────────────────
├── ledger-service ──────────────────────────────────────────────────────
├── transaction-service ─────────────────────────────────────────────────
├── party-service ───────────────────────────────────────────────────────
└── audit-service ───────────────────────────────────────────────────────
                                                                        │
TIER 2 (Compliance Gate — all parallel)                                 ▼
├── balance-service ──────────────────────────────────────────────────────
├── aml-service ─────────────────────────────────────────────────────────
├── sanctions-service ───────────────────────────────────────────────────
├── kyc-service ─────────────────────────────────────────────────────────
├── security-scanner ────────────────────────────────────────────────────
└── notification-service ────────────────────────────────────────────────
                                                                        │
TIER 3 (PSD2 / SCA — mostly parallel)                                  ▼
├── sca-service ──────────────────────────────────────────────────────────
├── consent-service ─────────────────────────────────────────────────────
├── tpp-registry-service ────────────────────────────────────────────────
├── pid-service ─────────────────────────────────────────────────────────
└── psd2-service (depends: consent-service healthy) ────────────────────
                                                                        │
TIER 4 (Payment Processing — all parallel)                              ▼
├── domestic-payment ──────────────────────────────────────────────────────
├── sepa-payment ────────────────────────────────────────────────────────
├── sepa-instant-service ────────────────────────────────────────────────
├── swift-service ───────────────────────────────────────────────────────
├── fx-service ──────────────────────────────────────────────────────────
├── clearing-service ────────────────────────────────────────────────────
├── standing-order-service ──────────────────────────────────────────────
└── card-issuance-service ───────────────────────────────────────────────
                                                                        │
TIER 5 (Operations — all parallel)                                      ▼
├── dispute-service
├── interest-service
├── admin-ui
├── grafana / prometheus / loki / tempo
└── kafka-ui / mailhog
```

---

## 4. RTO / RPO Targets

The table below records planning targets, not demonstrated or contractual recovery performance.
Read it with [ADR-0186](../adr/0186-single-region-deployment-and-disaster-recovery-posture.md),
which records the sandbox's single-region posture and aspirational recovery objectives. Reconcile
the chosen service tier, business-impact assessment and tested deployment before agreeing an SLA;
do not combine targets from different environments into a promise. Pension-specific measured
restore and data reconciliation verification remain
[#12479](https://github.com/JiRaska/open-bank-oss/issues/12479). A historical Docker exercise does
not establish recovery of the current pension database, workflows or external payments.

| Tier | Services | RTO | RPO | Recovery Priority |
|---|---|---|---|---|
| **Tier 0** | postgres, kafka, keycloak, vault, valkey | **5 min** | **0** (zero data loss) | P0 — immediate |
| **Tier 1** | account, ledger, transaction, party, audit | **15 min** | **< 1 min** | P1 — critical |
| **Tier 2** | balance, aml, sanctions, kyc, security-scanner, notification | **20 min** | **< 5 min** | P1 — critical (regulatory gate) |
| **Tier 3** | sca, consent, tpp-registry, psd2, pid | **30 min** | **< 5 min** | P2 — high |
| **Tier 4** | all payment services, card-issuance | **30 min** | **< 1 min** | P2 — high |
| **Tier 5** | dispute, interest, admin-ui, observability | **60 min** | **< 15 min** | P3 — standard |

**Total full-stack cold-start target: < 60 minutes** (verified: ~8 minutes on Docker Desktop with pre-built images)

---

## 5. Operational Runbooks

### 5.1 Full Cold Start (Complete Outage Recovery)

```bash
cd "$(git rev-parse --show-toplevel)"/openbank-infra

# TIER 0 — Infrastructure prerequisites
docker compose up -d --no-build postgres valkey kafka
# Wait for postgres + kafka healthy
until docker inspect openbank-postgres --format='{{.State.Health.Status}}' | grep -q healthy && \
      docker inspect openbank-kafka --format='{{.State.Health.Status}}' | grep -q healthy; do
  echo "Waiting for postgres + kafka..."; sleep 5
done

docker compose up -d --no-build keycloak vault schema-registry
# Wait for keycloak + vault healthy
until docker inspect openbank-keycloak --format='{{.State.Health.Status}}' | grep -q healthy && \
      docker inspect openbank-vault --format='{{.State.Health.Status}}' | grep -q healthy; do
  echo "Waiting for keycloak + vault..."; sleep 5
done
echo "✅ TIER 0 healthy"

# TIER 1 — Core Ledger (all parallel)
docker compose up -d --no-build \
  account-service ledger-service transaction-service party-service audit-service
echo "⏳ TIER 1 starting..."
sleep 60

# TIER 2 — Compliance Gate (all parallel)
docker compose up -d --no-build \
  balance-service aml-service sanctions-service kyc-service \
  security-scanner notification-service
echo "⏳ TIER 2 starting..."
sleep 60

# TIER 3 — PSD2 / SCA
docker compose up -d --no-build \
  sca-service consent-service tpp-registry-service pid-service
# psd2-service depends on consent-service healthy
until docker inspect openbank-consent-service --format='{{.State.Health.Status}}' | grep -q healthy; do
  echo "Waiting for consent-service..."; sleep 5
done
docker compose up -d --no-build psd2-service
echo "⏳ TIER 3 starting..."
sleep 60

# TIER 4 — Payment Processing (all parallel)
docker compose up -d --no-build \
  domestic-payment sepa-payment sepa-instant-service swift-service \
  fx-service clearing-service standing-order-service card-issuance-service
echo "⏳ TIER 4 starting..."
sleep 60

# TIER 5 — Operations
docker compose up -d --no-build \
  dispute-service interest-service admin-ui \
  grafana prometheus loki tempo kafka-ui mailhog
echo "✅ TIER 5 started"

# Verify
echo "=== HEALTH SUMMARY ==="
docker ps --format '{{.Names}}\t{{.Status}}' | grep openbank | sort
```

---

### 5.2 Partial Recovery — Payment Services Only

*Use when Tier 0-2 are healthy but payment services need restart (e.g., after config change).*

```bash
cd "$(git rev-parse --show-toplevel)"/openbank-infra

# Verify compliance gate is healthy before starting payments
for svc in openbank-aml-service openbank-sanctions-service openbank-balance-service; do
  status=$(docker inspect $svc --format='{{.State.Health.Status}}' 2>/dev/null)
  if [ "$status" != "healthy" ]; then
    echo "❌ BLOCKED: $svc is $status — AML/Sanctions gate not healthy. Cannot start payments."
    exit 1
  fi
done
echo "✅ Compliance gate healthy — proceeding with payment services"

docker compose up -d --no-build \
  domestic-payment sepa-payment sepa-instant-service swift-service \
  fx-service clearing-service standing-order-service card-issuance-service
```

---

### 5.3 Tier 0 Infrastructure Restart (e.g., after Docker Desktop restart)

```bash
cd "$(git rev-parse --show-toplevel)"/openbank-infra

# Step 1: Start stateful services first
docker compose up -d --no-build postgres valkey kafka

# Step 2: Wait for postgres (Keycloak and Vault need it)
until docker inspect openbank-postgres --format='{{.State.Health.Status}}' | grep -q healthy; do
  echo "Waiting for postgres..."; sleep 3
done

# Step 3: Start IAM and secrets
docker compose up -d --no-build keycloak vault schema-registry

# Step 4: Verify all Tier 0 healthy
echo "Waiting for full Tier 0..."
sleep 30
docker ps --format '{{.Names}}\t{{.Status}}' | grep -E "postgres|kafka|keycloak|vault|valkey|schema"
```

---

### 5.4 Single Service Recovery

```bash
# Generic pattern for any service
SERVICE=<service-name>  # e.g., transaction-service

cd "$(git rev-parse --show-toplevel)"/openbank-infra

# Check logs for root cause
docker logs openbank-${SERVICE} 2>&1 | grep -E "Caused by|FATAL|ERROR" | tail -20

# Restart with current image
docker compose up -d --no-build --force-recreate ${SERVICE}

# Monitor health
watch -n 5 "docker ps --format '{{.Names}}\t{{.Status}}' | grep ${SERVICE}"
```

---

### 5.5 Database Recovery (Missing DB)

*Triggered when a service fails with `database "openbank_X" does not exist`.*

```bash
# Identify missing database from logs
DB_NAME=<openbank_xxx>  # e.g., openbank_cards

docker exec openbank-postgres psql -U openbank -d postgres \
  -c "CREATE DATABASE ${DB_NAME};"

docker exec openbank-postgres psql -U openbank -d ${DB_NAME} \
  -c "CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\";
      CREATE EXTENSION IF NOT EXISTS \"pgcrypto\";
      GRANT ALL ON SCHEMA public TO openbank;"

# Restart the affected service
docker compose up -d --no-build --force-recreate <service-name>
```

---

### 5.6 Health Verification Script

```bash
#!/bin/bash
# BCP Health Check — run after any recovery procedure
# Usage: bash bcp-health-check.sh

echo "=== OpenBank BCP Health Check ==="
echo "Timestamp: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo ""

HEALTHY=0
UNHEALTHY=0
STARTING=0

while IFS=$'\t' read -r name status; do
  if echo "$status" | grep -q "(healthy)"; then
    echo "✅ $name"
    ((HEALTHY++))
  elif echo "$status" | grep -q "starting"; then
    echo "⏳ $name — $status"
    ((STARTING++))
  elif echo "$status" | grep -q "Restarting"; then
    echo "❌ $name — RESTARTING (check logs)"
    ((UNHEALTHY++))
  elif echo "$status" | grep -q "unhealthy"; then
    echo "❌ $name — UNHEALTHY (check logs)"
    ((UNHEALTHY++))
  else
    echo "ℹ️  $name — $status (no healthcheck)"
  fi
done < <(docker ps --format '{{.Names}}\t{{.Status}}' | grep openbank | sort)

echo ""
echo "=== SUMMARY ==="
echo "Healthy:   $HEALTHY"
echo "Starting:  $STARTING"
echo "Unhealthy: $UNHEALTHY"

if [ $UNHEALTHY -gt 0 ]; then
  echo ""
  echo "⚠️  BCP STATUS: DEGRADED — $UNHEALTHY service(s) require attention"
  exit 1
elif [ $STARTING -gt 0 ]; then
  echo ""
  echo "⏳ BCP STATUS: RECOVERING — $STARTING service(s) still starting"
  exit 2
else
  echo ""
  echo "✅ BCP STATUS: NOMINAL — all services healthy"
  exit 0
fi
```

---

## 6. Known Operational Dependencies & Gotchas

These were discovered during live stack operation and are critical for recovery:

### 6.1 Redis/Valkey Authentication
All services using Redis **must** have:
```yaml
QUARKUS_REDIS_HOSTS: redis://:${VALKEY_PASSWORD}@valkey:6379
```
Without the password, the service starts but health check fails with `Connection refused: localhost/127.0.0.1:6379` (Quarkus falls back to localhost when auth fails).

**Affected services:** account, transaction, balance, aml, sanctions, sca, consent, tpp-registry, psd2, domestic-payment, sepa-payment, sepa-instant, swift, fx, clearing, standing-order, card-issuance, dispute, interest

### 6.2 Bulkhead Configuration
All `OutboxDispatcher` classes must have `@Bulkhead(value = 1, waitingTaskQueue = 1)`.  
`waitingTaskQueue = 0` causes `FaultToleranceDefinitionException` at startup — service will not start.

**Fixed in:** transaction-service, domestic-payment, sepa-payment  
**Pattern to check:** `grep -r "waitingTaskQueue=0" --include="*.kt"`

### 6.3 REST Client Timeout Format
Quarkus REST client timeouts (`quarkus.rest-client.*.connect-timeout`) expect **milliseconds (long)**, not duration strings.

```yaml
# WRONG — causes SRCFG00030 conversion error
connect-timeout: 2S

# CORRECT
connect-timeout: 2000
```

**Fixed in:** consent-service (`application.yaml`), psd2-service (`application.yaml`)

### 6.4 Database Names vs Service Expectations
The init SQL creates databases with specific names. Services expect exact names:

| Service | Expected DB | Notes |
|---|---|---|
| card-issuance-service | `openbank_cards` | NOT `openbank_card_issuance` |
| sepa-instant-service | `openbank_sepa_instant` | |
| standing-order-service | `openbank_standing_orders` | Plural |
| All others | `openbank_<service>` | Standard pattern |

### 6.5 Flyway Generated Columns
PostgreSQL `GENERATED ALWAYS AS (...) STORED` requires the expression to be **immutable**.  
`TIMESTAMPTZ + INTERVAL` is not immutable (timezone-dependent).

**Fix pattern:** Replace with plain column + `BEFORE INSERT` trigger.  
**Fixed in:** audit-service `V2__compliance_fields.sql`

### 6.6 Vault Initialization
Vault requires these env vars to be present in `docker-compose.yml` for `init.sh` to succeed:
- `VAULT_DEV_ROOT_TOKEN`
- `POSTGRES_PASSWORD`
- `JWT_SECRET`
- `VALKEY_PASSWORD`

Missing any of these causes `exit code 2` during init.

### 6.7 PSD2 Service Startup Order
`psd2-service` has a `depends_on: consent-service: condition: service_healthy`.  
Do NOT start psd2-service before consent-service is healthy — it will fail to connect to the SCA client.

---

## 7. Internal incident severity and regulatory classification

These are internal response targets, not regulatory classification or reporting deadlines.

| Severity | Criteria | Internal response target | Escalation |
|---|---|---|---|
| **P0 — Critical** | Critical business processing unavailable or integrity at risk | Immediate | Incident commander and compliance |
| **P1 — High** | Required control unavailable or material degradation | < 15 min | Operations and compliance assessment |
| **P2 — Medium** | Partial degradation | < 30 min | Operations; assess business impact |
| **P3 — Low** | Tooling issue without identified customer impact | < 2h | Internal tracking; reassess if impact changes |

Assess reportability separately using the applicable entity-specific policy. For DORA, classification
criteria are in [Delegated Regulation 2024/1772](https://eur-lex.europa.eu/eli/reg_del/2024/1772/oj)
and reporting content/time limits in [Delegated Regulation 2025/301](https://eur-lex.europa.eu/eli/reg_del/2025/301/oj).
Record awareness and classification times, the relevant notification/update deadlines, recipient,
responsible person and acknowledgement. Do not use a blanket 24-hour timer from this runbook.
Pension incident exercises and the approved reporting policy are acceptance evidence under #12479.

---

## 8. Internal BCP test schedule

| Test Type | Frequency | Scope | Owner |
|---|---|---|---|
| Cold start drill | **Monthly** | Full stack from zero | Platform Engineering |
| Tier 0 failover | **Quarterly** | Postgres/Kafka restart with data | Platform Engineering |
| Payment service recovery | **Quarterly** | Tier 4 restart with active transactions | Payments Team |
| Full DR simulation | **Semi-annually** | Complete environment rebuild | All teams |
| Independent security exercise | **Annually proposed** | Agreed test scope | Security |

This is a proposed internal schedule, not a universal statutory cadence. Determine whether formal
TLPT applies and agree its scope/frequency under DORA Article 26 and the competent authority's
requirements. Evidence retention follows the approved legal/contractual retention schedule; this
runbook does not derive a universal five-year period from Article 11(6). Record completed drills
and findings separately from planned dates.

---

## 9. Quick Reference Card

```
STARTUP ORDER (fastest path to payments online):
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
T+0:00  postgres, kafka, valkey          (parallel)
T+0:30  keycloak, vault, schema-registry (after postgres healthy)
T+2:00  account, ledger, transaction,    (after keycloak+vault healthy)
        party, audit                     (parallel)
T+3:00  balance, aml, sanctions, kyc,   (parallel)
        security-scanner, notification
T+4:00  sca, consent, tpp-registry, pid (parallel)
T+4:30  psd2-service                    (after consent healthy)
T+5:00  domestic, sepa, sepa-instant,   (parallel — PAYMENTS ONLINE)
        swift, fx, clearing,
        standing-order, card-issuance
T+6:00  dispute, interest, admin-ui,    (parallel)
        grafana, prometheus, loki, tempo

TOTAL: ~6-8 minutes (pre-built images, Docker Desktop)

COMPLIANCE GATE CHECK (before starting payments):
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
docker inspect openbank-aml-service openbank-sanctions-service \
  openbank-balance-service openbank-sca-service \
  --format='{{.Name}}: {{.State.Health.Status}}'
# All must show: healthy

EMERGENCY CONTACTS:
━━━━━━━━━━━━━━━━━━
Admin UI:    http://localhost:3000  (admin@openbank.local / Admin1234!)
Keycloak:    http://localhost:8080
Grafana:     http://localhost:3001
Kafka UI:    http://localhost:8090
Vault:       http://localhost:8200  (token: see .env VAULT_DEV_ROOT_TOKEN)
```

---

*Review this reference after material incidents, infrastructure changes and drills. Keep the applicable legal review/testing obligations and measured deployment evidence in the approved control register.*
