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
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8085 | OpenAPI spec |
| `/api/docs` | 8085 | Swagger UI |
| `/q/health` | 8085 | SmallRye Health (liveness + readiness, management port) |

## Configuration

The service is configured via `application.yaml`. PostgreSQL and OIDC are required in the bank profile; Kafka and Redis are not wired today:

| Setting | Value | Purpose |
|---|---|---|
| `quarkus.http.port` | `8104` | app port |
| `quarkus.management.port` | `8085` | health and metrics port |
| `REACTIVE_URL` / `JDBC_URL` | local PostgreSQL defaults | application and Flyway database URLs |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | local development defaults | database credentials |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | local OpenBank realm | OIDC issuer/discovery URL |
| `quarkus.http.cors.origins` | `localhost:3000`, `openbank-admin-ui:3000` | CORS allowlist |
| `quarkus.http.header.*` | security headers | CSP, HSTS, X-Frame-Options, nosniff, etc. |
| `quarkus.log.level` | `INFO` | log level |
| `quarkus.smallrye-openapi.path` | `/q/openapi` | OpenAPI endpoint |
| `quarkus.swagger-ui.path` | `/api/docs` | Swagger UI |

Production database credentials are secrets and must be supplied by the deployment; the checked-in password is a local-development default, not a production value.

## Health checks

- **Liveness:** `:8085/q/health/live` — JVM + ArC up.
- **Readiness:** `:8085/q/health/ready` — includes extension-provided datasource readiness.

## FinOps workload tier (ADR-0057)

The catalog is a **stateful service with stateless application replicas**: PostgreSQL owns durable state. It is eligible for HTTP scale-to-zero in principle, but direct bank callers currently keep the deployed minimum at one replica; ADR-0083 records that operational constraint. Any change must be based on measured traffic and dependency behavior, not this document.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Notes |
|---|---|---|
| Availability | 99.5% | reference-data service, not money-path |
| Latency p95 GET | < 50 ms | design target; PostgreSQL I/O included |
| Cold start | within caller timeout budgets | must be measured with the KEDA proxy path |
| Error rate | < 0.1% 5xx | |

## Runbooks

- **Stale or wrong pricing in the admin UI** — the UI must read `GET /api/v1/fees`; confirm it is not falling back to a hardcoded list. Verify the product's `fees[]` and `status` via `GET /api/v1/products/{id}`.
- **Customer-facing listing requested** — there is no public projection in v1. Do not expose the authenticated operator list; use the v2 published projection once available.
- **State missing after restart** — treat as an incident: product state is PostgreSQL-backed. Verify database target, Flyway history and restore procedure; the seeder never overwrites a non-empty store.
- **Duplicate code on create** — `409`; pick a unique `code`.

## Release

Released component (has `version.txt`). Versioning/changelog are owned by release-please from Conventional Commits ([ADR 0029](../../../../docs/adr/0029-versioning-release-and-governance-as-code.md)). Do not hand-edit `version.txt` in a feature PR. API-contract changes bump `openapi.yaml:info.version` independently ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).
