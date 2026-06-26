# API

> **No `openapi.yaml`.** The gateway exposes **no business API of its own** — it forwards to the upstream services, each of which publishes its own OpenAPI contract. The "API" of this component is its **proxy surface** (route table) plus the **Kong Admin API**. This section documents both as found in `kong/kong.yml`, `docker-compose.yml`, `Makefile` and `README.md`; the contract is not formalized as OpenAPI because there is nothing service-specific to formalize.

## Surfaces

| Surface | Listener | Purpose |
|---|---|---|
| **Proxy** | `http://localhost:8000` (`KONG_PROXY_PORT`) | Public traffic; routes to upstream services |
| **Admin API** | `http://localhost:8001` (`KONG_ADMIN_PORT`) | Kong operational API (read status, inspect config) |

## Proxy contract

The proxy is a pass-through. The **real contract for `/api/v1/<resource>`** is owned by the upstream service (see that service's own `03-api`). The gateway guarantees only:

- **Path routing** per the [route table](./02-architecture.md#full-route-table). Business routes use `strip_path: false`, so the full path (e.g. `/api/v1/accounts/{id}`) reaches the upstream untouched.
- **Header forwarding**: `Authorization`, `X-Request-Id`, `X-Correlation-Id` are passed through unchanged (passthrough auth).
- **Versioning**: the `/api/v1` prefix is part of the upstream contract (URL `major == openbank.api.version`, ADR-0048). The gateway does not own or rewrite the version segment; it forwards it verbatim.

### Health pass-through

Each service has a health route under `/health/<service>` with `strip_path: true`:

```
GET http://localhost:8000/health/account/ready   → upstream :8100 /q/health/ready
GET http://localhost:8000/health/sepa/ready       → upstream :8115 /q/health/ready
GET http://localhost:8000/health/aml/ready        → upstream :8117 /q/health/ready
```

Example proxy calls (from `README.md`):

```bash
curl -i http://localhost:8000/api/v1/accounts/q/health/ready

curl -i \
  -H "Authorization: Bearer <keycloak-access-token>" \
  -H "X-Request-Id: demo-request-001" \
  http://localhost:8000/api/v1/psd2/q/health/ready
```

## Admin API

Kong OSS Admin API on `:8001`. Used operationally, not by clients:

```bash
curl -s http://localhost:8001/status | python3 -m json.tool
```

`make admin` / `make status` wrap this. In a hardened deployment the admin listener must **not** be publicly exposed (see [05 — Operations](./05-operations.md) and [06 — Compliance](./06-compliance.md)).

## Idempotency

**Not handled at the gateway.** Idempotency (`Idempotency-Key`) is the responsibility of the downstream services that own mutations. The gateway forwards any such header transparently.

## Error model

Errors at the gateway layer are **Kong's own**, not the OpenBank domain error envelope:

| Condition | Response | Meaning |
|---|---|---|
| No route matches the path | `404` (Kong `{"message":"no Route matched..."}`) | Path not in the route table |
| Upstream down / unreachable | `502 Bad Gateway` | Backend service not running (expected in partial-stack local runs) |
| Upstream timeout | `504 Gateway Timeout` | Exceeds `connect`/`read`/`write` timeouts (5s / 30s / 30s) after `retries: 2` |
| Auth failure | (passthrough) `401`/`403` returned by the **upstream**, not Kong | Token validated downstream |

For business errors (validation, conflict, idempotency replays) the upstream's domain error envelope is returned unchanged.

## Versioning of the gateway itself

The gateway is **not a released component** — it has no `version.txt`, is not in `release-please-config.json`, and does not serve `/api/v1/info` or `X-Service-Version` (those are openbank-libs features for JVM services). Its version is the pinned Kong image tag `kong:3.7.1` and the git history of `kong/kong.yml`.
