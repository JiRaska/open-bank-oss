# Operations

## Build & run

```bash
# Build (locally) — fast-jar (never uber-jar)
./gradlew :openbank-psd2-service:quarkusBuild -Dquarkus.package.jar.type=fast-jar

# Run dev mode (live reload; OIDC disabled in %dev)
./gradlew :openbank-psd2-service:quarkusDev

# Local gate before a PR
./gradlew detekt ktlintCheck koverVerify build
```

The Dockerfile is a multi-stage build on `eclipse-temurin:25` (JDK build stage, JRE runtime stage), copies the `quarkus-app/` fast-jar layout, runs as a non-root `openbank` user, and starts with `-XX:+UseZGC`. It `EXPOSE`s `8107`.

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/open-banking/v2/...` | 8107 | Open Banking AIS / PIS / consents (TPP-facing) |
| `/open-banking/sandbox/v2/...` | 8107 | sandbox fixtures (no auth) |
| `/open-banking/docs` | 8107 | Swagger UI |
| `/api/v1/info` | 8107 | service / build metadata |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/health` | 8085 | liveness + readiness (`smallrye-health`, root-path `/q`) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management interface is enabled on a separate port (`quarkus.management.port=8085`, `host=0.0.0.0`, `root-path=/q`). OpenTelemetry OTLP traces export to `:4317`.

## Configuration

| Setting / env | Default | Purpose |
|---|---|---|
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | service OIDC client secret — **override in prod** |
| `TPP_REGISTRY_SERVICE_URL` | `http://localhost:8108` | tpp-registry REST client base URL |
| `quarkus.smallrye-reactive-messaging.kafka.bootstrap-servers` | `localhost:29092` | Kafka brokers |
| `quarkus.redis.hosts` | `redis://localhost:6379` | idempotency cache |
| `quarkus.oidc.auth-server-url` | `http://localhost:8080/realms/openbank` | Keycloak realm |
| `openbank.psd2.sandbox-mode` | `true` | sandbox surface enabled |
| `openbank.psd2.idempotency-ttl-seconds` | `86400` | idempotency cache TTL |
| `openbank.rate-limit.max-concurrent-requests` | `100` | concurrency cap |
| `openbank.outbox.poll-interval` / `initial-delay` | `5s` / `5s` | outbox dispatcher cadence |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build provenance surfaced in `/api/v1/info` |

Resilience knobs live under `openbank.resilience.{circuit-breaker,retry,timeout}` and the topic mapping under `mp.messaging.outgoing.psd2-events-out` (topic `openbank.psd2.events`, String serializers).

Security response headers are set globally (`X-Content-Type-Options`, `X-Frame-Options: DENY`, CSP `default-src 'self'`, HSTS, `Referrer-Policy`, `Permissions-Policy`). CORS is restricted to `http://localhost:3000` with the Open Banking header allowlist (`Consent-ID`, `Idempotency-Key`, `TPP-*`, `X-TPP-ID`, `SSL-CLIENT-S-DN`, …).

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC up.
- **Readiness:** `/q/health/ready` — Kafka producer + Redis connectivity (no business DB to gate on beyond the outbox).

## Serverless tier (ADR-0057)

PSD2 is an **external-facing, TPP-driven** surface with bursty, latency-sensitive traffic and a fail-closed consent dependency. Under the scale-to-zero workload tiers (see [ADR 0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md)) it should be classified to keep a warm minimum replica during business hours to avoid cold-start latency on TPP calls; the exact tier assignment is driven by the FinOps classifier (TBD — confirm against the live classifier output, not hard-coded here).

## SLO (targets)

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | `up{service="openbank-psd2-service"}` |
| Latency p95 AIS GET | < 150 ms | `http_server_requests_seconds{quantile=0.95}` (incl. consent validate + downstream read) |
| Latency p95 PIS POST | < 400 ms | includes consent validate + transaction-service initiate |
| Outbox lag | < 10 s | age of oldest `PENDING` row (poll interval 5 s) |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### TPP getting 503 SERVICE_UNAVAILABLE

Cause: `tpp-registry` circuit breaker open or registry unreachable.
1. Check tpp-registry health and reachability from the pod (`TPP_REGISTRY_SERVICE_URL`).
2. Inspect logs for `TPP registry circuit open` / `authorization failed`.
3. The breaker auto-recovers after the configured delay (5 s) once the registry is healthy; no manual reset needed.

### TPP getting 401 CONSENT_INVALID unexpectedly

Cause: `consent-service` returned not-valid, **or** the consent-validate fallback fired (fails closed, returns `false`).
1. Check consent-service health — a downstream outage degrades to deny.
2. Verify the consent scope matches the operation (`ACCOUNTS_READ` / `BALANCES_READ` / `TRANSACTIONS_READ` / `*_INITIATE`).
3. Confirm the consent is not expired (capped at 90 days at creation).

### Outbox lag growing

1. Count pending rows: `SELECT count(*) FROM psd2_outbox WHERE status='PENDING'`.
2. Check Kafka reachability and the `openbank.psd2.events` topic.
3. Inspect `Psd2OutboxDispatcher` logs; the publish path is bulkhead-limited (1) and circuit-broken — a Kafka outage parks rows as `PENDING`/`FAILED` and they retry on the next poll.

### Idempotency replay

A repeated PIS call with the same `Idempotency-Key` (same `tppId`+product) returns the cached `201` with `X-Idempotency-Replayed: true`. This is expected and safe.

## Flyway operations

Migrations are immutable once applied. If a checksum mismatch blocks startup on a live DB, set `QUARKUS_FLYWAY_REPAIR_AT_START=true` temporarily, then remove it once settled (never rewrite an applied migration).

## Deploy / release

- Per-service path-scoped CI builds only changed services. Release is automatic via **release-please** from Conventional Commits — never hand-edit `version.txt` or `CHANGELOG.md`. Current release version: `version.txt = 0.3.0`.
- Image build/push via `openbank-infra/scripts/build-push-service.sh openbank-psd2-service` (host-side `quarkusBuild`, fast-jar). GitOps image tags: on merge conflicts take `--ours` for image lines.
