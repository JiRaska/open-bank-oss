# Overview

## What the service does

`openbank-api-gateway` is the **single north-south entry point** for the OpenBank dockerized stack. It is a [Kong OSS](https://github.com/Kong/kong) `3.7.1` proxy running in **DB-less (declarative) mode** that:

- **Exposes one public proxy** on `http://localhost:8000` and routes each public path prefix to the correct backend service.
- **Maps clean public paths** (`/api/v1/<resource>`) to local upstreams reachable on the host (`host.docker.internal:8100–8117`).
- **Provides health pass-through routes** (`/health/<service>/*`) for quick partial-stack smoke checks.
- **Forwards auth context unchanged** — `Authorization`, `X-Request-Id`, `X-Correlation-Id` — so downstream services keep doing OIDC validation.

It is a **thin routing/edge tier**, not a business service. It holds no domain model, no schema, no events.

## What the service **does NOT** do

- ❌ Does not validate JWTs by default — auth is **passthrough**; the downstream Quarkus service validates the Keycloak bearer token (OIDC). Gateway-level JWT validation is an *optional, not-yet-enabled* Kong OSS `jwt` plugin (placeholders in `.env.example`).
- ❌ Does not persist anything — `KONG_DATABASE=off`; the only "state" is the declarative `kong/kong.yml`.
- ❌ Does not own any business domain (no accounts, balances, payments) — it only forwards to the services that do.
- ❌ Does not emit domain events or run an outbox.
- ❌ Does not perform rate-limiting, request transformation, or service discovery today — those are deliberately deferred (see [ADR 0051](../../../../docs/adr/0051-generic-service-discovery-and-single-admin-gateway.md) for the planned discovery/gateway direction).
- ❌ Is not the admin-UI BFF — the admin UI has its own backend-for-frontend proxy; this gateway fronts the public/banking API plane.

## Position in the domain

```
                       client / integrator / TPP
                                 │  HTTPS
                                 ▼
                    ┌──────────────────────────┐
                    │   openbank-api-gateway    │
                    │   (Kong OSS 3.7.1, :8000) │
                    │   passthrough auth        │
                    └────────────┬──────────────┘
            /api/v1/accounts ────┤    forwards Authorization,
            /api/v1/ledger   ────┤    X-Request-Id,
            /api/v1/sepa     ────┤    X-Correlation-Id
            …14 routes…      ────┤
                                 ▼
        ┌──────────── host.docker.internal:8100–8117 ───────────┐
        │ account ledger transaction balance consent psd2 agent │
        │ party notification audit kyc sepa domestic aml        │
        │ (each validates the Keycloak OIDC token itself)       │
        └────────────────────────────────────────────────────────┘
```

## Key use cases

The gateway has **no domain API of its own**; its "use cases" are routing rules. There are no domain events.

| Use case | Public path | Upstream | Event |
|---|---|---|---|
| Route account API | `/api/v1/accounts` | `:8100` | — |
| Route ledger API | `/api/v1/ledger` | `:8101` | — |
| Route transactions API | `/api/v1/transactions` | `:8102` | — |
| Route balances API | `/api/v1/balances` | `:8103` | — |
| Route consent API | `/api/v1/consents` | `:8106` | — |
| Route PSD2 API | `/api/v1/psd2` | `:8107` | — |
| Route agent API | `/api/v1/agent`, `/api/v1/agents` | `:8109` | — |
| Route party API | `/api/v1/parties` | `:8111` | — |
| Route notification API | `/api/v1/notifications` | `:8112` | — |
| Route audit API | `/api/v1/audit` | `:8113` | — |
| Route KYC API | `/api/v1/kyc` | `:8114` | — |
| Route SEPA API | `/api/v1/sepa` | `:8115` | — |
| Route domestic-payment API | `/api/v1/domestic` | `:8116` | — |
| Route AML API | `/api/v1/aml` | `:8117` | — |
| Health pass-through | `/health/<service>/*` | upstream `/q/health` | — |

## Callers

- **External clients / integrators / PSD2 TPPs** — the single public entry point to the banking API plane.
- **Local smoke tooling** — `make smoke` and `curl` against the admin API (`:8001`) and the health routes.
- **Operators** — Kong Admin API (`:8001/status`) for liveness of the gateway itself.

## Dependencies

- **Kong OSS** `3.7.1` (container `openbank-kong`).
- **The 14 upstream services**, reachable on the Docker host (`host.docker.internal:8100–8117`). `host.docker.internal` keeps the gateway decoupled from `openbank-infra` internals; the compose file adds a `host-gateway` mapping for Linux.
- **Keycloak** — *indirectly*: tokens flow through, but the gateway does not call Keycloak in passthrough mode. The optional `jwt` plugin would reference `OPENBANK_JWT_ISSUER` (`/realms/openbank`).
- **No** PostgreSQL, **no** Kafka, **no** Redis, **no** openbank-libs (it is not a JVM service).

## Business value

- **Single north-south choke point** — one place to later attach authentication, rate-limiting, and observability instead of fanning out from the web tier to every backend (the topology problem ADR-0051 is solving).
- **Stable public contract** — clients see clean `/api/v1/<resource>` paths decoupled from internal host/port layout.
- **Local-first parity** — keeps local behaviour close to production (bearer tokens flow end-to-end) without depending on Kong Enterprise-only OIDC features.
- **Operational simplicity** — DB-less means no gateway datastore to back up, migrate, or breach; the whole config is one reviewable file in git.
