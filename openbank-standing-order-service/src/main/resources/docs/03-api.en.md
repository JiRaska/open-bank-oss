# API & contracts

## Base path

- **Production base:** `http://openbank-standing-order-service:8121/api/v1` (in-cluster)
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8121/q/openapi)
- **Swagger UI:** [`/api/docs`](http://localhost:8121/api/docs) (configured `swagger-ui.path`)
- **Contract source:** `src/main/resources/openapi.yaml` (`info.version: 1.0.0`, `openapi: 3.1.0`).

> **Contract drift — read before integrating.** The committed `openapi.yaml` does not yet fully match the implemented `StandingOrderResource` / DTOs:
> - The spec's `servers` URL lists port **8116**; the service actually binds **8121** (`application.yaml`). Use 8121.
> - The spec's `CreateStandingOrderRequest` uses `debtorAccountId`, `amount`, `currencyCode`. The **implemented** request body (`CreateStandingOrderRequest` DTO) uses `idempotencyKey`, `partyId`, `debitAccountId`, `creditorIban`, `creditorName`, `creditorBic`, `amountMinorUnits`, `currency`, `frequency`, `paymentType`, `remittanceInfo`, `startDate`, `endDate`.
> - The spec omits the `GET /api/v1/standing-orders` (list-all) endpoint that the resource exposes.
>
> The implemented behavior below is authoritative; the OpenAPI file should be reconciled (tracked as a TBD).

## Authentication & authorization

- **AuthN:** Keycloak OIDC bearer token, realm `openbank` (OIDC disabled in `%dev`/`%test` profiles).
- **AuthZ:** OPA sidecar via `openbank-libs` `@Authorize` (ADR-0034). Mode is **advisory by default** (`authz.enforce=false`); flip to enforce per environment. Currently the `pause` operation carries `@Authorize(action = "standingOrder.pause", resource = "#id")`; other mutations are not yet annotated (TBD — annotate `resume`/`cancel`/`create` for full coverage).

## Idempotency

Creation is idempotent via a request field, not a header:

- The client supplies `idempotencyKey` in the `CreateStandingOrderRequest` body.
- `StandingOrderService.create` calls `findByIdempotencyKey(...)` first; if an order with that key already exists, it is returned unchanged (replay-safe).
- The DB enforces uniqueness: `standing_orders.idempotency_key` has a `UNIQUE` constraint.

## Endpoints (as implemented)

### Create standing order

```http
POST /api/v1/standing-orders
Content-Type: application/json
Authorization: Bearer <token>

{
  "idempotencyKey": "5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b",
  "partyId": "7f3e2a1b-0000-0000-0000-000000000001",
  "debitAccountId": "a1b2c3d4-0000-0000-0000-000000000002",
  "creditorIban": "CZ6508000000192000145399",
  "creditorName": "Acme Energy s.r.o.",
  "creditorBic": "GIBACZPX",
  "amountMinorUnits": 149900,
  "currency": "EUR",
  "frequency": "MONTHLY",
  "paymentType": "SEPA_CREDIT",
  "remittanceInfo": "VS 12345",
  "startDate": "2026-07-01",
  "endDate": null
}
```

```http
201 Created
Content-Type: application/json

{
  "id": "b9c8a7d6-...",
  "partyId": "7f3e2a1b-...",
  "debtorAccountId": "a1b2c3d4-...",
  "creditorIban": "CZ6508000000192000145399",
  "creditorName": "Acme Energy s.r.o.",
  "status": "ACTIVE",
  "frequency": "MONTHLY",
  "paymentType": "SEPA_CREDIT",
  "amountMinorUnits": 149900,
  "amount": 1499.0,
  "currency": "EUR",
  "nextExecutionDate": "2026-07-01",
  "remittanceInfo": "VS 12345",
  "description": "VS 12345",
  "executionCount": 0,
  "createdAt": "2026-06-09T10:42:13Z",
  "updatedAt": "2026-06-09T10:42:13Z"
}
```

A new order starts `ACTIVE` with `nextExecutionDate = startDate`, `executionCount = 0`, `failureCount = 0`.

### Other endpoints

| Method & path | Purpose | Success | Notes |
|---|---|---|---|
| `GET /api/v1/standing-orders` | List all orders | 200 | returns `StandingOrderResponse[]` |
| `GET /api/v1/standing-orders/{id}` | Get one order | 200 | 404 if not found |
| `GET /api/v1/standing-orders/party/{partyId}` | List orders for a party | 200 | `StandingOrderResponse[]` |
| `POST /api/v1/standing-orders/{id}/pause` | Pause an ACTIVE order | 200 | rejects non-ACTIVE (domain `require`) |
| `POST /api/v1/standing-orders/{id}/resume` | Resume a PAUSED order | 200 | rejects non-PAUSED |
| `DELETE /api/v1/standing-orders/{id}` | Cancel an order | 204 | rejects CANCELLED/COMPLETED |

## Error model

The committed contract declares a minimal `ApiError` (`{ code, message }`). Mapping at runtime:

| HTTP | When |
|---|---|
| 400 | malformed body / invalid enum value |
| 401 | missing / invalid token (when OIDC enabled) |
| 403 | OPA denies the action (when `authz.enforce=true`) |
| 404 | order id does not exist (`NotFoundException`) |
| 409 | (logical) duplicate `idempotencyKey` resolves to the existing order rather than an error |
| 422 | illegal state transition (e.g. pausing a non-ACTIVE order) surfaces as a domain `require` failure |
| 429 | rate-limited (`openbank.rate-limit`, max 200 concurrent) |
| 500 | unexpected error |

> The unified RFC-7807-style problem detail used by money-path services is not yet wired here — error shape is the minimal `ApiError`. Reconcile as part of the contract clean-up (TBD).

## Events

Topic: `openbank.standing-orders.order.event` (Kafka, JSON payload, `String` key/value serializers). Published via the transactional outbox.

| Domain event | Trigger | Payload (key fields) |
|---|---|---|
| `StandingOrderCreated` | `POST /standing-orders` | id, partyId, currency, amountMinorUnits, at |
| `StandingOrderExecuted` | (planned) scheduler materializes a due order | id, partyId, at |
| `StandingOrderCancelled` | `DELETE /standing-orders/{id}` | id, partyId, at |

Events are append-only; corrections are made by a compensating event, not by rewriting.

## Versioning & backward compatibility

- **Release axis:** `version.txt` = `0.2.0` (release-please owns it; see [05 — Operations](./05-operations.md)).
- **API contract axis:** `openapi.yaml info.version` = `1.0.0`, independent of the release version (ADR-0048). The URL major (`/api/v1`) tracks the contract major.
- **API version in URL** — breaking changes move to `/api/v2`, v1 runs in parallel for the deprecation window.
- **Event version in topic name** — schema evolution is additive only.
