# Operations

## Build

```
./gradlew :openbank-dispute-service:build          # compile + test
./gradlew detekt ktlintCheck koverVerify build     # local gate before a PR
```

The service uses the `openbank.quarkus-service` convention plugin. Container images are built **fast-jar** (never uber-jar) via `openbank-infra/scripts/build-push-service.sh openbank-dispute-service`, with `quarkusBuild` run host-side first (CLAUDE.md GitOps rules).

## Runtime configuration

| Setting | Value | Source |
|---|---|---|
| App HTTP port | `8135` | `application.yaml` |
| Management port | `8085`, root-path `/q` | `application.yaml` |
| DB URL (reactive / JDBC) | `postgresql://…/openbank_dispute` | `application.yaml` |
| Kafka topic | `openbank.disputes.dispute.event` | `mp.messaging.outgoing.dispute-events-out` |
| OIDC | realm `openbank`, client `openbank-services` | `application.yaml` |
| Redis | `redis://localhost:6379` | `application.yaml` |
| OTLP endpoint | `http://localhost:4317` | `application.yaml` |
| Outbox poll | every `5s`, initial delay `5s` | `openbank.outbox` |
| Dispute SLA | `45` days; chargeback window `120` days | `openbank.dispute.*` |
| Rate limit | enabled, max `200` concurrent | `openbank.rate-limit` |
| OPA / authz | `OPA_URL`, `authz.enforce=false` (advisory) | `application.yaml` |

Secrets (`POSTGRES_PASSWORD`, `OIDC_CLIENT_SECRET`) default to `CHANGE_ME_LOCAL_DEV_ONLY` placeholders and must be injected from the platform secret store in non-dev environments. In `%dev` and `%test`, OIDC is disabled.

## Serverless tier (ADR-0057)

Dispute traffic is operator-driven and bursty rather than constant. It is a candidate for the **scale-to-zero / scheduled-warm** tier per ADR-0057; the `@Scheduled` outbox dispatcher (every 5s) means a fully idle scale-to-zero must account for outbox drain — keep at least a warm replica when undelivered outbox rows exist. Confirm the actual tier assignment in the GitOps manifests (TBD if not yet declared).

## Health probes

SmallRye Health on the management port (`/q/health`):

- **Liveness** `/q/health/live`
- **Readiness** `/q/health/ready` — includes datasource + Kafka connectivity
- **Metrics** `/q/metrics` (Prometheus / Micrometer)
- **Docs** `/q/openbank/docs` (this documentation, ADR-0019)

## SLO (proposed)

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target |
|---|---|
| Availability (read API) | 99.9% |
| `POST /disputes` p99 latency | < 300 ms |
| Outbox publish lag | < 30 s (poll 5s + retry) |
| RTO / RPO | 15 min / 5 min (DORA-aligned) |

## Runbooks

### Outbox not draining
1. Check `/q/metrics` for the scheduler and circuit-breaker state.
2. Query `SELECT status, count(*) FROM dispute_outbox GROUP BY status;` — rows stuck non-sent with `last_error` indicate a Kafka publish problem.
3. Verify Kafka connectivity / topic `openbank.disputes.dispute.event`. The circuit breaker opens at 50% failure over a volume of 10; it half-opens after 5s.

### Flyway checksum mismatch on startup
Never edit an applied migration. Set `QUARKUS_FLYWAY_REPAIR_AT_START=true` in the GitOps env, restart, then remove once the DB is settled (CLAUDE.md). Note `validate-on-migrate` is `false` here, so most drift is tolerated at migrate time.

### Insert fails with `relation "dispute_outbox_seq" does not exist`
The V3 sequence migration did not apply. Re-run migrations / confirm V3 is present.

### 401/403 on the API
Confirm the bearer token comes from the `openbank` realm and carries `ROLE_VIEWER` (reads) or `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API` (mutations). If OPA enforcement was flipped on (`AUTHZ_ENFORCE=true`), check the OPA sidecar decision logs.

## Release

Per-service SemVer via release-please (the commit message is the changelog). Do not hand-edit `version.txt`, `CHANGELOG.md`, or `openapi.yaml:info.version`. Use `/bump openbank-dispute-service` for version sync and `/ship-check` before merge (ADR-0029).
