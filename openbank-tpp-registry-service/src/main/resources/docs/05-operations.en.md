# Operations

## Build

```
./gradlew :openbank-tpp-registry-service:build      # compile + test the service
./gradlew detekt ktlintCheck koverVerify build      # the local gate before a PR
```

The service uses the `openbank.quarkus-service` convention plugin and `openbank-libs`. CI is path-scoped — only changed services build (ADR-0029).

### Image build

Always **fast-jar**, host-side build (never in-Docker Gradle):

```
openbank-infra/scripts/build-push-service.sh openbank-tpp-registry-service
```

`build-push-service.sh` runs `quarkusBuild` locally first, then the runtime stage COPYs `quarkus-app/`. Never use uber-jar — it leaves `quarkus-app/` empty and crashloops.

## Configuration (key env vars)

| Variable | Purpose | Default |
|---|---|---|
| `POSTGRES_PASSWORD` | DB credential | `CHANGE_ME_LOCAL_DEV_ONLY` (blocked in prod) |
| `OIDC_CLIENT_SECRET` | Keycloak client secret | `CHANGE_ME_LOCAL_DEV_ONLY` |
| `OPA_URL` / `OPA_PATH` / `OPA_TIMEOUT_MS` | OPA sidecar PDP | `http://localhost:8181` / `/v1/data/openbank/rest/allow` / `500` |
| `AUTHZ_ENFORCE` | flip OPA from advisory to enforce | `false` |
| `BUILD_TIME` / `GIT_COMMIT` | BuildInfo for `/api/v1/info` | `unknown` |

- **App port:** 8108. **Management port:** 8085 (`/q` root: health, docs, metrics).
- **Datasource:** `openbank_tpp_registry` on PostgreSQL; reactive + JDBC (Flyway) URLs.
- **Kafka:** bootstrap from `quarkus.smallrye-reactive-messaging.kafka.bootstrap-servers`; outgoing channel `tpp-events-out` → topic `openbank.tpp.registry.event`.
- **Redis:** `redis://localhost:6379` (idempotency).
- **Rate limiting:** `openbank.rate-limit.enabled=true`, `max-concurrent-requests=50`.
- **Security headers** (CSP, HSTS, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy) set via `quarkus.http.header.*`.

## Serverless tier (ADR-0057)

Per the scale-to-zero workload tiers and FinOps classifier (ADR-0057), tpp-registry is a **low-traffic control-plane registry** read mostly by `psd2-service` on the AIS/PIS hot path. The authorization check is latency-sensitive, so the realistic tier is **warm/min-replicas ≥ 1** (do not scale to zero on the read path that PSD2 depends on). Confirm the tier assignment in the FinOps classifier output for this service — exact tier label is **TBD** until classified there.

## Health & probes

- **Liveness/Readiness:** SmallRye Health at `/q/health` (root-path `/q/health`), served on the management port 8085. Readiness covers the reactive datasource and Kafka client.
- **Startup:** Flyway `migrate-at-start` with `connect-retries: 10` / `2S` interval — tolerates DB not-yet-ready on cold start.

## Observability

- **Tracing/metrics:** OpenTelemetry OTLP exporter → `http://localhost:4317` (configurable); `service.name = openbank-tpp-registry-service`.
- **Logs:** JSON console logging (structured) in non-dev profiles.

## SLO (proposed)

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Indicator | Target |
|---|---|
| `GET /check` availability | 99.9% (PSD2 hot path dependency) |
| `GET /check` p99 latency | ≤ 50 ms (in-cluster) |
| Register/blacklist availability | 99.5% |
| RTO / RPO | RTO 15 min / RPO 5 min (stateless app; state in PostgreSQL) |

## Runbooks

### TPP authorization check failing for a known-good TPP
1. `GET /api/v1/tpp-registry/{tppId}` — confirm `status=ACTIVE` and the role is present.
2. Check `qwac_expires_at` — an expired QWAC yields `403` with reason "QWAC certificate expired".
3. If status is `BLACKLISTED`, inspect `blacklist_reason` / `blacklisted_at`.

### Emergency blacklist (compromised / de-licensed TPP)
1. `POST /api/v1/tpp-registry/{tppId}/blacklist` with `{ "reason": "<incident ref>" }` and an `Idempotency-Key`.
2. Verify `GET /check` now returns `403`. PSD2 surfaces will reject the TPP on next call.

### Outbox not draining
1. Query `tpp_outbox WHERE status='PENDING' ORDER BY created_at` for backlog.
2. Inspect `last_error` / `attempt_count` on `FAILED` rows; check Kafka connectivity (`tpp-events-out`).
3. The dispatcher runs every 5 s (`@Scheduled`, batch 25); a wedged scheduler is restored by a pod restart.

### Flyway checksum mismatch on startup
- Never rewrite an applied migration. If a checksum mismatch crashes startup, set `QUARKUS_FLYWAY_REPAIR_AT_START=true` in the gitops env, let it settle, then remove.

## Release

Released component (has `version.txt`, currently `0.3.0`). Versioning/changelog are owned by release-please from Conventional Commits — do not hand-edit `version.txt` or `CHANGELOG.md`. The `openapi.yaml:info.version` is a separate API-contract axis (ADR-0048).
