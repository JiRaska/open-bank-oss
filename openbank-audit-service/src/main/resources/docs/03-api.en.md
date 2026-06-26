# API

The REST surface is defined in [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version: 1.0.0`). It is intentionally **minimal and read-only** — the audit trail has no write API; ingestion is exclusively via Kafka (see [02 — Architecture](./02-architecture.md)).

The major API version is `1` (`openbank.api.version`), so all paths live under `/api/v1` (ADR-0048 — the API-contract axis is independent of the release `version.txt`).

## Endpoints

### `GET /api/v1/audit/entries/{aggregateId}`

Retrieve the audit trail for a single aggregate (an account, party, transaction, consent, KYC case, …), most recent first.

**Path parameters**

| Name | Type | Notes |
|---|---|---|
| `aggregateId` | string | The id whose history you want (e.g. an account id, party id, transaction id) |

**Query parameters**

| Name | Type | Default | Notes |
|---|---|---|---|
| `limit` | integer | 100 | Page size; clamped server-side to `1..500` |
| `eventType` | string | — | Declared in `openapi.yaml`; **filtering by event type is not yet implemented in `AuditResource`** (the resource currently accepts only `limit`). Treat as reserved. |
| `offset` | integer | 0 | Declared in `openapi.yaml`; not yet wired in the resource. Reserved. |

> Discrepancy note (grounded in code): `openapi.yaml` documents `eventType`/`offset`, but `AuditResource.getAuditTrail` currently binds only `aggregateId` and `limit`. The contract is ahead of the implementation here; the docs flag it rather than hiding it.

**Response `200`** — JSON array of `AuditEntryResponse`:

| Field | Type | Notes |
|---|---|---|
| `id` / `entryId` | uuid | Unique entry id (`entry_id` column) |
| `aggregateId` | string | The aggregate this event concerns |
| `aggregateType` | string | ACCOUNT / PARTY / TRANSACTION / CONSENT / KYC_CASE / UNKNOWN |
| `eventType` | string | The producer's event name |
| `actorId` | string \| null | Who triggered it (`requestedBy` / `actorId` from payload) |
| `payload` | object/string | The original event payload, verbatim |
| `occurredAt` | date-time | Business time of the event |
| `correlationId` | string \| null | Trace correlation id |

(The Kotlin domain `AuditEntry` additionally carries `actorType`, `sourceService` and `recordedAt`; the OpenAPI response schema is the published subset.)

## Authentication & authorization

- **AuthN:** Keycloak OIDC, RS256 bearer JWT (`bearerAuth` security scheme). OIDC is disabled in the `%dev` and `%test` profiles only.
- **AuthZ:** `@RolesAllowed("ROLE_AUDITOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")`. The endpoint is **never** `@PermitAll` — an unauthenticated audit log is itself an audit finding (regression guard `AuditResourceSecurityTest`, control K7).

| Caller | Required role |
|---|---|
| Dedicated read-only auditor | `ROLE_AUDITOR` |
| Platform administrator | `ROLE_ADMIN` |
| Compliance investigator | `ROLE_COMPLIANCE` |

## Error model

| Status | When |
|---|---|
| `200` | Trail returned (possibly an empty array) |
| `401` | Missing/invalid bearer token |
| `403` | Token lacks any of the three audit-reading roles |
| `404` | Unknown path |

There is no `4xx` for an unknown `aggregateId` — an aggregate with no recorded events simply returns `200` with `[]`.

## Idempotency & versioning

- **Idempotency:** not applicable to the read API. The write path is event-driven and naturally idempotent at the row level via the unique `entry_id` UUID.
- **Versioning:** `X-API-Version` / `X-Service-Version` response headers and `/api/v1/info` are served by `openbank-libs` (`ServiceInfoResource`, `ApiVersionResponseFilter`). No paths are currently deprecated.

## Interactive docs

Swagger UI is served at `/api/docs` (`quarkus.swagger-ui`, always-include enabled).
