# API

REST contract from `src/main/resources/openapi.yaml` (`OpenAPI 3.1.0`, `info.version 1.0.0`). The URL major matches `openbank.api.version = 1` → all paths are under `/api/v1` (API contract axis is independent of the release `version.txt`, per [ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).

Base URL (local dev): `http://localhost:8122`. JSON in/out (`application/json`).

## Endpoints

| Method | Path | Operation | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/swift` | `sendSwiftMessage` | Submit a SWIFT message for dispatch (idempotent) |
| `GET` | `/api/v1/swift/{id}` | `getSwiftMessage` | Get a message by UUID |
| `GET` | `/api/v1/swift/status/{status}` | `listSwiftByStatus` | List messages by lifecycle status |
| `POST` | `/api/v1/swift/{id}/ack` | `acknowledgeSwiftMessage` | Record ACK from receiving bank → `ACKNOWLEDGED` |
| `POST` | `/api/v1/swift/{id}/reject` | `rejectSwiftMessage` | Record rejection → `REJECTED` |
| `GET` | `/api/v1/swift/messages` | (resource only) | List all messages — **present in `SwiftResource` but not in `openapi.yaml`** (contract drift, see note) |

> **Contract drift to reconcile:** `SwiftResource.listAll()` exposes `GET /api/v1/swift/messages`, which is not described in `openapi.yaml`. The OpenAPI file should be updated to add it (or the endpoint removed) so the contract test stays green. Flagged as TBD.

## Submit — `POST /api/v1/swift`

Request body `SendSwiftCommand` (required fields): `idempotencyKey`, `messageType`, `senderBic`, `receiverBic`, `transactionReference`, `valueDate`, `currency`, `amountMinorUnits`, `beneficiaryAccount`, `beneficiaryName`. Optional: `relatedReference`, `orderingCustomerAccount`, `orderingCustomerName`, `remittanceInfo`, `chargeCode` (default `SHA`), `priority` (default `NORMAL`).

Field constraints (from the schema): BIC matches `^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$` (8 or 11 chars); `transactionReference` ≤ 16 (SWIFT field 20); `relatedReference` ≤ 16 (field 21); `valueDate` is `^\d{8}$` (YYYYMMDD); `currency` is ISO 4217 `^[A-Z]{3}$`; `amountMinorUnits` ≥ 1; `remittanceInfo` ≤ 140 (field 70); `chargeCode` ∈ {OUR, SHA, BEN} (field 71A).

Example (MT103 customer credit transfer):

```json
{
  "idempotencyKey": "pay-2024-001",
  "messageType": "MT103",
  "senderBic": "KOMBCZPP",
  "receiverBic": "DEUTDEDB",
  "transactionReference": "TXN20240101001",
  "valueDate": "20240101",
  "currency": "EUR",
  "amountMinorUnits": 150000,
  "orderingCustomerAccount": "CZ6508000000192000145399",
  "orderingCustomerName": "Jan Novák",
  "beneficiaryAccount": "DE89370400440532013000",
  "beneficiaryName": "Hans Müller",
  "remittanceInfo": "Invoice INV-2024-001",
  "chargeCode": "SHA",
  "priority": "NORMAL"
}
```

Responses:

| Code | Meaning |
|---|---|
| `201` | Accepted and queued; returns the `SwiftMessage` (status `VALIDATED`) |
| `400` | Invalid request body (`ErrorResponse`) |
| `409` | Duplicate idempotency key with conflicting payload (`ErrorResponse`) |
| `422` | BIC validation failed or business-rule violation (`ValidationError`) |

## Idempotency

Idempotency is **payload-level**, not header-level: the client supplies `idempotencyKey` in the `SendSwiftCommand`. `SwiftService.send` first calls `findByIdempotencyKey` and returns the existing message unchanged if found; the DB also enforces a `UNIQUE` constraint on `idempotency_key`. A repeated submit with the same key returns the original message without re-sending.

> Note: the platform `Idempotency-Key` HTTP header (allowed by CORS config) and the Redis client are wired into the service, but the send path keys off the body field.

## Acknowledge / Reject

- `POST /api/v1/swift/{id}/ack` — body `{ "ackRef": "ACKREF…" }`. Transitions the message to `ACKNOWLEDGED` and sets `ackReceivedAt`. Guarded by `@Authorize(action = "swift.acknowledge", resource = "#id")` (OPA, ADR-0034).
- `POST /api/v1/swift/{id}/reject` — body `{ "reason": "…" }`. Transitions to `REJECTED` and records `rejectionReason`.

Both return the updated `SwiftMessage`; `404` if the id is unknown; `409` if the message is not in an ack/reject-able state (per OpenAPI).

## Status query — `GET /api/v1/swift/status/{status}`

`{status}` ∈ `PENDING | VALIDATED | SENT | ACKNOWLEDGED | REJECTED | FAILED`. Returns an array of `SwiftMessage`.

## Error model

```json
{ "error": "string", "message": "string", "traceId": "string" }
```

Validation errors use `ValidationError`:

```json
{ "error": "Validation failed", "violations": [ { "field": "senderBic", "message": "..." } ] }
```

## Versioning

- **URL major** `/api/v1` ← `openbank.api.version`.
- **API contract version** `openapi.yaml: info.version = 1.0.0` — bumped from the OpenAPI diff (`oasdiff`), independent of the release `version.txt` ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).
- `X-API-Version` / `X-Service-Version` headers and `/api/v1/info` are served by `openbank-libs`.

## Management / supporting endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/docs` (Swagger UI) | 8122 | `always-include: true` |
| `/q/openapi` | 8085 | OpenAPI document |
| `/q/openbank/docs` | 8085 | Docs-as-Service (this documentation) |
| `/q/health` | 8085 | SmallRye Health (liveness/readiness) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |
