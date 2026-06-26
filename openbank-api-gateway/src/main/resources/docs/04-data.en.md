# Data

## There is no datastore

`openbank-api-gateway` is **stateless**. It runs Kong OSS in **DB-less mode** (`KONG_DATABASE: off` in `docker-compose.yml`), so:

- ❌ **No PostgreSQL / no DB schema** — there is nothing comparable to the `account` / `ledger` schemas of the business services.
- ❌ **No Flyway migrations** — there is no `db/migration/` directory and no version table.
- ❌ **No Kafka topic / no outbox** — the gateway emits no domain events.
- ❌ **No Redis / idempotency cache** — idempotency is owned downstream.

## The only "state": declarative config

The entire configuration is a single git-tracked file, loaded at startup and mounted **read-only**:

| Artifact | Role | Mutability |
|---|---|---|
| `kong/kong.yml` | Declarative routing config (`_format_version: "3.0"`, `_transform: true`) — 14 services + their health routes | Immutable at runtime; change = redeploy |
| `.env` (from `.env.example`) | Runtime knobs: ports, log level, auth mode, JWT placeholders | Local-only; `.env` is git-ignored |
| `docker-compose.yml` | Container definition (image, listeners, host mapping) | Git-tracked |

There is no runtime-mutating data plane: requests pass through and are not stored.

## Data in transit

The gateway **transports** request/response bodies and headers but **persists none of them**. The only data it actively touches:

| Field | Handling | PII? |
|---|---|---|
| `Authorization: Bearer <jwt>` | Forwarded verbatim, not inspected (passthrough) | Sensitive (bearer credential) — never log the token |
| `X-Request-Id`, `X-Correlation-Id` | Forwarded verbatim for tracing | No |
| Request/response bodies (e.g. IBANs, party data via `/api/v1/...`) | Streamed through; not buffered to disk, not stored | **Yes, in transit** — PII belongs to the upstream services; the gateway is a conduit only |

## Logs

Kong writes **access and error logs to stdout/stderr** (`KONG_PROXY_ACCESS_LOG=/dev/stdout`, `KONG_PROXY_ERROR_LOG=/dev/stderr`), captured by the container runtime. Access logs contain request lines (method, path, status, latency) and client IP. They do **not** contain request bodies by default. Treat `Authorization` header values as secrets — they must not be logged (default Kong access log format does not log arbitrary headers).

## Retention

| Data | Retention |
|---|---|
| Routing config (`kong/kong.yml`) | Git history (permanent) |
| In-flight request/response | Not retained (stateless) |
| Container access/error logs | Per the platform's log pipeline / container runtime policy — TBD, governed centrally, not by this component |

No personal data is stored by the gateway, so there is no erasure or data-subject-export obligation at this tier; those obligations live in the upstream owning services (see [06 — Compliance](./06-compliance.md)).
