# Architecture

## C4 — context & container

```
[ Client / Integrator / TPP ]
            │  HTTPS, Authorization: Bearer <jwt>
            ▼
┌────────────────────────────────────────────────────┐
│ Container: openbank-kong (Kong OSS 3.7.1)            │
│   proxy  : 0.0.0.0:8000                              │
│   admin  : 0.0.0.0:8001                              │
│   mode   : DB-less (KONG_DATABASE=off)               │
│   config : /etc/kong/kong.yml (read-only mount)      │
│   plugins: none enabled (passthrough)                │
└───────────────┬────────────────────────────────────┘
                │  HTTP, host.docker.internal:<port>
                ▼
   14 OpenBank upstream services (Quarkus), each owning
   its domain, DB schema, OIDC validation and outbox.
```

The gateway is intentionally a **single container with no sidecars and no datastore**. There is no hexagonal domain/application/adapter split here — that pattern (ADR-0002) applies to the Quarkus *business* services, not to a configuration-driven proxy. The "source code" of this component is its **declarative configuration**.

## Deployment topology (local)

Defined by `docker-compose.yml`:

- Image: `kong:3.7.1`, container `openbank-kong`, `restart: unless-stopped`.
- `KONG_DATABASE: off` + `KONG_DECLARATIVE_CONFIG: /etc/kong/kong.yml`.
- Listeners: `KONG_PROXY_LISTEN=0.0.0.0:8000 reuseport backlog=16384`, `KONG_ADMIN_LISTEN=0.0.0.0:8001 reuseport backlog=16384`.
- Logs to stdout/stderr (`KONG_PROXY_ACCESS_LOG=/dev/stdout`, errors to `/dev/stderr`); `KONG_LOG_LEVEL` defaults to `info`.
- Published ports `${KONG_PROXY_PORT:-8000}:8000` and `${KONG_ADMIN_PORT:-8001}:8001`.
- `extra_hosts: host.docker.internal:host-gateway` so the gateway reaches host-exposed services (incl. Linux Docker hosts).
- `kong/kong.yml` mounted read-only — the config is immutable at runtime; changes are a redeploy.

## Routing model

`kong/kong.yml` is `_format_version: "3.0"`, `_transform: true`. Each backend is expressed as **two Kong services + routes**:

1. **Business route** — e.g. service `account-service` → `host.docker.internal:8100`, `path: /`, route `account-route` on `paths: [/api/v1/accounts]` with **`strip_path: false`** (the full public path is forwarded to the upstream).
2. **Health route** — e.g. `account-health-service` → upstream `path: /q/health`, route `account-health-route` on `paths: [/health/account]` with **`strip_path: true`** (the `/health/account` prefix is stripped, so `/health/account/ready` hits the upstream's `/q/health/ready`).

Per-route resilience defaults applied to every upstream:

- `retries: 2`
- `connect_timeout: 5000` ms
- `write_timeout: 30000` ms
- `read_timeout: 30000` ms

### Full route table

| Kong service | Upstream | Public path(s) | strip_path |
|---|---|---|---|
| account-service | `:8100` `/` | `/api/v1/accounts` | false |
| account-health-service | `:8100` `/q/health` | `/health/account` | true |
| ledger-service | `:8101` `/` | `/api/v1/ledger` | false |
| transaction-service | `:8102` `/` | `/api/v1/transactions` | false |
| balance-service | `:8103` `/` | `/api/v1/balances` | false |
| consent-service | `:8106` `/` | `/api/v1/consents` | false |
| psd2-service | `:8107` `/` | `/api/v1/psd2` | false |
| agent-service | `:8109` `/` | `/api/v1/agent`, `/api/v1/agents` | false |
| party-service | `:8111` `/` | `/api/v1/parties` | false |
| notification-service | `:8112` `/` | `/api/v1/notifications` | false |
| audit-service | `:8113` `/` | `/api/v1/audit` | false |
| kyc-service | `:8114` `/` | `/api/v1/kyc` | false |
| sepa-service | `:8115` `/` | `/api/v1/sepa` | false |
| domestic-service | `:8116` `/` | `/api/v1/domestic` | false |
| aml-service | `:8117` `/` | `/api/v1/aml` | false |

Each business service above also has a matching `*-health-service` on `/health/<service>` (strip_path: true) → upstream `/q/health`.

## Auth flow (passthrough)

```
Client ──Bearer jwt──► Kong ──(headers forwarded verbatim)──► Quarkus service
                        │                                       │
                        │ no plugin, no token inspection        │ OIDC validate
                        ▼                                       ▼
                   route match only                       401/403 on bad token
```

- Default `OPENBANK_AUTH_MODE=passthrough`. Kong forwards `Authorization`, `X-Request-Id`, `X-Correlation-Id` unchanged; downstream services own AuthN/AuthZ.
- **Optional future:** Kong OSS `jwt` plugin with static consumers/keys, validating against `OPENBANK_JWT_ISSUER` (`http://localhost:8080/realms/openbank`), `OPENBANK_JWT_AUDIENCE`, `OPENBANK_JWT_CLAIMS=exp,nbf`. Not enabled today; this keeps local flow close to prod without Kong Enterprise OIDC.

## No outbox / no events

The gateway has **no outbox and produces no Kafka events**. Domain events are owned by the downstream services. There is therefore no event-schema versioning concern at this tier.

## Key design decisions

- **DB-less over DB-backed Kong** — no datastore to operate; config is git-reviewable.
- **Explicit routing over service discovery** — simple and inspectable for the local stack; dynamic discovery is the direction of [ADR 0051](../../../../docs/adr/0051-generic-service-discovery-and-single-admin-gateway.md) (Kubernetes-API control plane), not yet adopted here.
- **`host.docker.internal` upstreams** — decouples the gateway from `openbank-infra` internals while reaching host-exposed services.
