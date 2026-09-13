# Operations

## Build & run

```bash
./gradlew :openbank-balance-service:quarkusBuild
./gradlew :openbank-balance-service:quarkusDev

docker compose -f openbank-infra/docker-compose.yml build balance-service
docker compose -f openbank-infra/docker-compose.yml up -d balance-service
```

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/balances/...` | 8103 | business REST |
| `/api/v1/info` | 8103 | ServiceInfo |
| `/q/openbank/docs` | 8103 | **Docs-as-Service** (this documentation) |
| `/q/openapi`, `/q/swagger-ui` | 8103 | API contract + browser UI |
| `/q/health/{live,ready}` | 8085 | probes (separate mgmt port) |
| `/q/metrics` | 8085 | Prometheus |

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `QUARKUS_DATASOURCE_REACTIVE_URL` | `postgresql://localhost:5432/openbank_balance` | DB |
| `QUARKUS_DATASOURCE_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — prod via Vault (ADR 0017) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka |
| `QUARKUS_REDIS_HOSTS` | `redis://localhost:6379` | idempotency |
| `OPENBANK_BALANCE_LOW_THRESHOLD_EUR` | `100.00` | triggers `balance.low.v1` |
| `OPENBANK_HOLDS_EXPIRY_INTERVAL` | `5m` | expired-holds scan |

⬜ **This does not happen.** No `BootstrapVerifier` exists (`git grep BootstrapVerifier -- '*.kt'` returns 0; ADR-0017 prescribes one and its own delivery note records it was never shipped), so the `CHANGE_ME_LOCAL_DEV_ONLY` above does not abort startup in prod. In the deployment, `balance-service.yaml` takes credentials through `secretKeyRef` from ESO/OpenBao (ADR-0007) and carries no placeholder — the property is held by configuration, not by a boot-time control (#8426).

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target |
|---|---|
| Availability | 99.95% |
| `GET /balances/{id}` p95 | < 50 ms |
| `POST /holds` p95 | < 100 ms |
| Kafka consumer lag | < 1 s p99 |
| Outbox lag | < 2 s p99 |

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC
- **Readiness:** `/q/health/ready` — DB pool + Kafka consumer + producer + Redis

## Runbooks

### `optimistic-lock-conflict` rate rising

1. Check Prometheus: `rate(http_server_requests_seconds_count{status="409", code="optimistic-lock-conflict"}[5m])`
2. If > 5 req/s → card authorisations are hammering the same account. Possible scenarios:
   - two card terminals at once → expected, client retries
   - bot fraud testing → contact fraud-detection
3. Mitigation: widen retry with exponential backoff in the transaction-service consumer

### Booked vs ledger divergence

A daily recon job (see `04-data.md`) emits a `balance.reconciliation.diverged.v1` event. Actions:

1. Identify affected accounts
2. Replay from the last known consistent checkpoint timestamp
3. If replay does not fix → manual adjustment via the audit trail (requires sign-off from 2 compliance officers)

### Hold expiry worker stalled

Symptom: `balance_holds WHERE expires_at < now() AND released_at IS NULL` has > 0 rows older than 5 min.

1. Check `BalanceOutboxDispatcher` logs
2. Restart the pod
3. If the problem persists: run `POST /q/admin/expire-holds` (internal admin endpoint)

## Tech-stack matrix

Auto-sourced from `BuildInfo`:

| Component | Version |
|---|---|
| Kotlin | 2.3.20 |
| Quarkus | 3.33.2 LTS |
| JDK | 25 LTS |
| PostgreSQL JDBC | 42.7.x |
| Kafka client | 3.7.x |

## Deploy

CI: `.github/workflows/ci-balance-service.yml` — test → quarkusBuild → SBOM → image push → ArgoCD sync.
