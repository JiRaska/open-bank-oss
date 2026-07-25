# Operations

## Build & run

```bash
# Build (fast-jar — never uber-jar)
./gradlew :openbank-card-issuance-service:quarkusBuild

# Run dev mode (live reload; OIDC disabled in %dev)
./gradlew :openbank-card-issuance-service:quarkusDev

# Generic infra build/push
openbank-infra/scripts/build-push-service.sh card-issuance-service
```

The service uses the shared `openbank.quarkus-service` Gradle convention; the Dockerfile builds a **fast-jar** (`quarkus-app/`), per the repo-wide rule.

## Endpoints & ports

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/cards/...` | 8118 | business REST API |
| `/api/docs` | 8118 | Swagger UI |
| `/api/v1/info` | 8118 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

The **management interface is enabled on a separate port 8085** (`quarkus.management.enabled`, `root-path: /q`); the app listens on **8118**.

## Configuration

| Config | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod via Vault** |
| reactive datasource URL | `postgresql://localhost:5432/openbank_cards` | PostgreSQL |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — **override in prod** |
| OIDC auth-server | `http://localhost:8080/realms/openbank` | issuer |
| Kafka bootstrap | `localhost:29092` | brokers |
| Redis hosts | `redis://localhost:6379` | Valkey/Redis |
| OTel OTLP endpoint | `http://localhost:4317` | tracing |
| `openbank.rate-limit.max-concurrent-requests` | `200` | concurrency cap |
| `openbank.card.pan-vault-backfill.enabled` | `true` | mint a synthetic PAN at boot for non-terminal cards issued before the vault (`pan_encrypted IS NULL`), preserving each card's displayed last 4. Idempotent, non-blocking, and never fails the boot; it logs one `[pan-vault-backfill]` summary line per start |

Security headers (`X-Content-Type-Options`, `X-Frame-Options: DENY`, CSP `default-src 'self'`, HSTS, etc.) are set globally in `application.yaml`. Logs are JSON in non-dev profiles.

## Health checks

- **Liveness:** `/q/health/live`
- **Readiness:** `/q/health/ready` — backed by SmallRye Health (DB / Kafka producer reachability)

Probes target the management port **8085**.

## Serverless / workload tier (ADR-0057)

Per [ADR-0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md) (scale-to-zero workload tiers + FinOps classifier), card-issuance is a low-traffic, request-driven service and is a candidate for a scale-to-zero / scale-from-zero tier. **Constraint:** the outbox dispatcher runs on a `@Scheduled` tick (every 5s) and the Deployment is pinned to `replicas: 1` for single-writer ordering (ADR-0050 N4) — so any scale-to-zero policy must keep exactly one replica when there is undelivered outbox work. The authoritative tier classification is derived by the FinOps classifier, not declared here.

## SLO (targets)

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | `up{service="card-issuance-service"}` |
| Latency p95 GET | < 100 ms | `http_server_requests_seconds` |
| Latency p95 POST (issue) | < 300 ms | includes DB write + outbox insert |
| Outbox lag | < 10 s | dispatcher runs every 5s; pending-row age |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Outbox lag growing

1. Count undelivered rows: `SELECT count(*) FROM card_outbox WHERE status='PENDING'`.
2. Check Kafka reachability from the pod (the publisher is circuit-broken: repeated failures open the breaker for ~5s).
3. Tail dispatcher logs: `kubectl logs -l app=card-issuance-service | grep CardOutboxDispatcher`.
4. Inspect `FAILED`/`DEAD` rows: `SELECT event_id, attempt_count, last_error FROM card_outbox WHERE status IN ('FAILED','DEAD')`.

### Flyway checksum mismatch on startup

Never edit an applied migration. If a checksum mismatch blocks startup, set `QUARKUS_FLYWAY_REPAIR_AT_START=true` in the gitops env, let it settle, then remove it. (`validate-on-migrate` is already `false` here.)

### Issue returns an existing card unexpectedly

This is by design: the `Idempotency-Key` matched an existing `cards.idempotency_key`. Use a fresh key for a genuinely new card.

## Testing & CI

- Unit: `CardTest` (state machine), `CardServiceTest`, `CardOutboxDispatchTest`.
- Integration: `CardOutboxDispatchIT` with `PostgresRedisTestResource` — isolated PostgreSQL + Valkey per test JVM via Testcontainers (CI infra sweep #578); Kafka is in-memory. The scheduler is disabled under `%test` so the IT drives `dispatchScheduledBatch()` explicitly.
- Governance: `HibernateSequenceGuardTest` guards the `<table>_seq` convention.
- Coverage is ratchet-only (kover); card-issuance is **not** money-path so the higher money-path floor does not apply.

## Deploy / release

- Versioning: per-service `version.txt` (currently `0.3.0`), owned by release-please from Conventional Commits. Do not hand-edit.
- CD: ArgoCD picks up the new image tag from the gitops manifests.
