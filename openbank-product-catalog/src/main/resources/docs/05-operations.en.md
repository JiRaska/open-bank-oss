# Operations

## Build & run

```bash
# Build (locally)
./gradlew :openbank-product-catalog:quarkusBuild

# Run dev mode (live reload)
./gradlew :openbank-product-catalog:quarkusDev

# Local gate before a PR
./gradlew detekt ktlintCheck koverVerify build
```

### Container image

`:openbank-product-catalog:quarkusBuild` (fast-jar) runs host-side from the **full repo root context** (the root `settings.gradle.kts` `include`s every module), and `.github/workflows/Dockerfile.deploy` copies the resulting `quarkus-app/` into an `eclipse-temurin:25-jre` image (glibc, #3354). It runs as a non-root `openbank` user, `EXPOSE`s 8104, and starts with ZGC. `openbank-product-catalog/Dockerfile` builds nothing (#3016) — the pipeline reads exactly one thing from it, the `EXPOSE` line.

> Platform rule: **always fast-jar, never uber-jar** — the runtime stage copies `quarkus-app/`. Build host-side, not in-Docker Gradle. Generic build helper: `openbank-infra/scripts/build-push-service.sh openbank-product-catalog`.

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/products/...` | 8104 | product master REST API |
| `/api/v1/fees` | 8104 | bank-wide fee schedule |
| `/api/v1/info` | 8104 | `ServiceInfoResource` (build metadata, from openbank-libs) |
| `/q/openbank/docs` | 8104 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8104 | OpenAPI spec |
| `/api/docs` | 8104 | Swagger UI |
| `/q/health` | 8104 | SmallRye Health (liveness + readiness) |

## Configuration

The service is configured via `application.yaml`. There are **no external datastore/broker/secret dependencies** today, so the configuration surface is small:

| Setting | Value | Purpose |
|---|---|---|
| `quarkus.http.port` | `8104` | app port |
| `quarkus.http.cors.origins` | `localhost:3000`, `openbank-admin-ui:3000` | CORS allowlist |
| `quarkus.http.header.*` | security headers | CSP, HSTS, X-Frame-Options, nosniff, etc. |
| `quarkus.log.level` | `INFO` | log level |
| `quarkus.smallrye-openapi.path` | `/q/openapi` | OpenAPI endpoint |
| `quarkus.swagger-ui.path` | `/api/docs` | Swagger UI |

No secrets are required, so there is no Vault/`BootstrapVerifier` blocking surface today ([ADR 0017](../../../../docs/adr/0017-secrets-via-vault.md) applies only once a datastore credential is introduced).

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC up. (SmallRye Health is on the classpath; no custom DB/broker readiness checks exist because there are no such dependencies yet.)
- **Readiness:** `/q/health/ready` — process ready to serve.

When the MongoDB-backed store lands, a readiness check on the datastore connection should be added.

## FinOps workload tier (ADR-0057)

The catalog is **stateless, read-mostly reference data with bursty traffic** (admin-UI browsing, occasional reads by account/interest/fx/card services). It is a natural **T1 — HTTP → 0** candidate: scale-to-zero on inbound HTTP via the KEDA HTTP add-on, cold-start-tolerant within its latency SLO, ~0 idle cost. As a **non-money-path** service it is allowed to scale to zero. Per [ADR 0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md) the tier is **derived from measured traffic**, not hand-assigned; the value here is the expected classification, to be confirmed by the FinOps classifier.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Notes |
|---|---|---|
| Availability | 99.5% | reference-data service, not money-path |
| Latency p95 GET | < 50 ms | in-memory reads, no I/O |
| Cold start (T1 scale-to-zero) | within latency SLO | Quarkus fast-jar starts in tens of ms |
| Error rate | < 0.1% 5xx | |

## Runbooks

- **Stale or wrong pricing in the admin UI** — the UI must read `GET /api/v1/fees`; confirm it is not falling back to a hardcoded list. Verify the product's `fees[]` and `status` via `GET /api/v1/products/{id}`.
- **Product missing from public list** — check `status == ACTIVE` and `isPublic == true`; DRAFT/INACTIVE/non-public products are excluded from customer-facing views.
- **State lost after restart** — expected today: the store is in-memory and re-seeds the fixed 15-product catalog on each boot. Any runtime create/update is not persisted until the DB-backed store lands ([04 — Data](./04-data.md)).
- **Duplicate code on create** — `409`; pick a unique `code`.

## Release

Released component (has `version.txt`, currently `0.1.0`). Versioning/changelog are owned by release-please from Conventional Commits ([ADR 0029](../../../../docs/adr/0029-versioning-release-and-governance-as-code.md)). Do not hand-edit `version.txt` in a feature PR. API-contract changes bump `openapi.yaml:info.version` independently ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).
