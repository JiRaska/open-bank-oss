# Operations

## Build

```
./gradlew :openbank-audit-service:build           # compile + test
./gradlew detekt ktlintCheck koverVerify build    # the local gate before a PR
```

Build is fast-jar (never uber-jar — see root CLAUDE.md GitOps rules). Generic image build via `openbank-infra/scripts/build-push-service.sh openbank-audit-service` (host-side `quarkusBuild`, then Docker COPYs `quarkus-app/`).

- **Release axis:** `version.txt` (currently `0.3.0`), owned by release-please from Conventional Commits — do not hand-edit.
- **API axis:** `openapi.yaml: info.version` (`1.0.0`); major == `openbank.api.version` == `/api/v1` (ADR-0048).

## Runtime configuration

| Concern | Value | Source |
|---|---|---|
| App HTTP port | `8113` | `quarkus.http.port` |
| East-west mTLS port | `8443` (private-CA cert `audit-service-internal-tls`, client certificate required; `%prod` only) | `QUARKUS_HTTP_SSL_*` env, `quarkus.http.ssl.client-auth` (build time) |
| Management port | `8085`, root-path `/q` | `quarkus.management.*` |
| Datasource | `postgresql://localhost:5432/openbank_audit` | `quarkus.datasource` |
| Flyway | `migrate-at-start: true`, 10 connect retries | `quarkus.flyway` |
| Kafka bootstrap | `localhost:29092` (local) | `quarkus.smallrye-reactive-messaging.kafka` |
| Inbound channel | `audit-events-in`, group `audit-service`, `auto.offset.reset=earliest` | `mp.messaging.incoming` |
| OIDC | `…/realms/openbank`, client `openbank-services` | `quarkus.oidc` |
| OTel | OTLP `http://localhost:4317` | `quarkus.otel` |
| Rate limit | enabled, max 200 concurrent | `openbank.rate-limit` |

The 8443 listener exists for lending-service's evidence-bundle read (#11900), which carries a person's bearer token; HTTP/8113 stays for existing callers and the probes (`quarkus.http.insecure-requests: enabled`).

Secrets (`POSTGRES_PASSWORD`, `OIDC_CLIENT_SECRET`) carry `CHANGE_ME_LOCAL_DEV_ONLY` placeholders; production injects real values (Vault, ADR-0017). Never ship the placeholders.

## Serverless / workload tier (ADR-0057)

audit-service is an event consumer with a steady, low-latency ingest obligation and a 10-year durability mandate. It is **not** a good scale-to-zero candidate: a cold consumer would lag the audit stream and risk missing/ delaying regulated evidence. Classify it as an **always-on (warm) tier** workload under [ADR-0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md); the read API alone could tolerate scale-down, but the Kafka consumer should stay resident. (Exact tier label is set in the FinOps classifier config, not in this service.)

## Health probes

SmallRye Health is on the management port:

- **Liveness:** `GET :8085/q/health/live`
- **Readiness:** `GET :8085/q/health/ready` — includes datasource and Kafka connectivity.

Metrics: `GET :8085/q/metrics` (Prometheus). Docs: `:8085/q/openbank/docs` (this documentation). Tracing: OTLP to the collector.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Indicator | Objective (proposed) |
|---|---|
| Ingest lag (event → persisted) | p99 < 5 s under nominal load |
| Read API latency `GET /entries/{id}` | p99 < 300 ms |
| Availability (read API) | 99.9% |
| Durability (no lost audit events) | 100% — backed by `earliest` replay + immutable store |
| RTO / RPO | RTO 15 min / RPO 0 for committed rows (Kafka replay covers in-flight) |

## Runbooks

### Consumer lag / events not appearing
1. Check readiness on `:8085/q/health/ready` (Kafka up?).
2. Inspect consumer group `audit-service` lag on the broker.
3. Logs are JSON (`quarkus.log.console.json`); `AuditConsumer` logs `Failed to record audit entry` with the first 200 chars of any poison payload — search for it. Poison messages are swallowed (offset advances), so persistent gaps point to a parse/DB failure, not back-pressure.
4. To backfill after an outage, the consumer's `auto.offset.reset=earliest` plus topic retention determines replay reach.

### DB / migration issues
- `migrate-at-start` runs Flyway on boot. On a checksum mismatch (a migration changed after apply), set `QUARKUS_FLYWAY_REPAIR_AT_START=true` temporarily, then remove once the DB is settled (root CLAUDE.md Flyway rule). Never rewrite an applied migration.
- `relation "<table>_seq" does not exist` ⇒ V4 sequences missing; re-run migrations.

### Immutability surprise
- An `UPDATE`/`DELETE` against `audit_entries` returning "0 rows affected" is **expected** — the `DO INSTEAD NOTHING` rules silently no-op. Do not attempt to "fix" data in place; append a compensating entry instead.

## Deploy

GitOps via ArgoCD (manifests in `gitops/`). On merge-conflict for image tags, take `--ours` for image lines (root CLAUDE.md). Commits must be signed with the GPG-registered email.

## ČNB policy-rate evidence

The audit consumer subscribes to `openbank.fx.cnb-policy-rate.published` alongside the existing FX topics. Keep this topic in the configured `audit-events-in` subscription when changing the consumer list: a newly published or revised policy-rate fact then has the same retained audit path as other business events. If records are absent, inspect the audit consumer group's lag and the topic's retention before replay.
