# Operations

## Build & run

```bash
# Build (locally, fast-jar — never uber-jar)
./gradlew :openbank-notification-service:quarkusBuild

# Run dev mode (live reload; OIDC disabled, mailer mocked)
./gradlew :openbank-notification-service:quarkusDev

# Local gate before a PR
./gradlew detekt ktlintCheck koverVerify build
```

Generic image build: `openbank-infra/scripts/build-push-service.sh notification-service` (host-side `quarkusBuild` first, then Docker COPYs `quarkus-app/`).

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/notifications/...` | 8112 | read notifications |
| `/api/v1/devices` | 8112 | push device-token registry |
| `/api/v1/ops/dispatch/...` | 8112 | break-glass dispatch control |
| `/api/v1/info` | 8112 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8112 | Swagger UI |
| `/q/openapi` | 8085 / 8112 | OpenAPI spec |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

A dedicated **management interface** is enabled on port **8085** (`quarkus.management`) carrying health, metrics and docs; the business API is on **8112**. (Management is disabled under the test profile.)

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod via Vault** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret |
| `SLACK_WEBHOOK_ENABLED` | `false` | enable oversight webhook (ADR-0059) |
| `SLACK_WEBHOOK_URL` | (unset) | Slack incoming-webhook URL — injected from Vault, never in git |
| `FCM_ENABLED` | `false` | enable FCM push adapter |
| `FCM_SERVICE_ACCOUNT_JSON` | (unset) | FCM service-account JSON (Vault) |
| `FCM_PROJECT_ID` | (unset) | optional, falls back to JSON |
| `APNS_ENABLED` | `false` | enable APNs push adapter |
| `APNS_KEY_ID` / `APNS_TEAM_ID` / `APNS_BUNDLE_ID` | / / `cz.openbank.app` | APNs identifiers |
| `APNS_PRIVATE_KEY` | (unset) | .p8 PKCS#8 EC key (Vault) |
| `APNS_SANDBOX` | `false` | true → APNs sandbox host |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata in `/api/v1/info` |

Push adapters and the oversight webhook are **off by default**; a disabled adapter records a successful no-op (no egress). Credentials are injected at runtime from Vault via ExternalSecret — never committed.

## Health checks

- **Liveness:** `/q/health/live` (port 8085) — JVM + ArC. Pod restart on failure.
- **Readiness:** `/q/health/ready` — reactive DB connection. Flyway retries connection 10× at 2 s on startup.

## Serverless / workload tier (ADR-0057)

notification-service is event-driven and bursty (it reacts to upstream events), making it a candidate for the **scale-to-zero / scale-from-zero** workload tiers of [ADR 0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md). Caveats specific to this service:

- The `@Scheduled` outbox tick (every 5 s) and the persistent Kafka consumer subscription mean it is **not** a pure request-driven HTTP workload — scaling to zero must account for the consumer group keeping a partition assignment. Tier classification is **TBD** pending the FinOps classifier run.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.5% | Prometheus `up{service="notification-service"}` |
| Consumer lag | < 5 s | Kafka consumer-group lag on `openbank.notification.requests` |
| Email/push delivery success | best-effort (not money path) | `notifications.status` distribution |
| Outbox dispatch tick | every 5 s, batch 25 | `NotificationOutboxDispatcher` |
| Error rate (REST) | < 0.5% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Notifications not being delivered

1. Is dispatch halted? `GET /api/v1/ops/dispatch` — if `state=HALTED`, an operator hit the break-glass. Resume via four-eyes (`/resume/propose` + a *different* actor `/approve`).
2. Check consumer lag on `openbank.notification.requests`; check `NotificationConsumer` logs for parse/processing errors.
3. EMAIL stuck `PENDING`/`FAILED`? Verify SMTP (`quarkus.mailer.*`); in dev the mailer is mocked.
4. PUSH `FAILED` with "no active devices"? The party has no ACTIVE `device_tokens`, or FCM/APNs adapters are disabled (then push is a no-op SENT).

### Halt / resume the dispatch loop (break-glass)

```bash
# Halt (single actor)
curl -XPOST .../api/v1/ops/dispatch/halt -d '{"reason":"incident #123"}'
# Resume needs four-eyes:
curl -XPOST .../api/v1/ops/dispatch/resume/propose -d '{"reason":"cleared"}'   # actor A
curl -XPOST .../api/v1/ops/dispatch/resume/{id}/approve -d '{"reason":"ok"}'    # actor B (≠ A) → 422 if A==B
```

### Push tokens being rejected

Provider rejections (`UNREGISTERED` / `BadDeviceToken`) automatically mark the `device_tokens` row `INVALID` so it drops from future fan-out. No manual action needed; a growing INVALID count signals stale client installs.

### Poison message on the topic

An un-parseable payload is logged and acked (it does not wedge the partition). Inspect the logged payload; fix the producer.

## Deploy / release

- Per-service path-scoped CI; release-please owns `version.txt` (currently `0.4.0`) and the changelog from Conventional Commits.
- CI runs tests against an isolated PostgreSQL per test JVM via Testcontainers (#578); Kafka is in-memory in tests (only an `@Incoming` channel), the `@Scheduled` outbox tick is disabled under test.
- CD via ArgoCD on image-tag bump (GitOps); for image-tag merge conflicts take `--ours`.
