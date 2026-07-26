# API & contracts

The REST contract is described in [`openapi.yaml`](../openapi.yaml) (`info.version` 1.3.0; API major version `1` ⇒ URL prefix `/api/v1`). Where the OpenAPI document and the implementation diverge, this page documents the **code as the source of truth** and flags the divergence.

## Base path

- **In-cluster base:** `http://openbank-party-service:8111/api/v1`
- **OpenAPI spec:** `/q/openapi` (management port 8085) — the static contract is also bundled at `src/main/resources/openapi.yaml`
- **Swagger UI:** `/api/docs` (always included per `quarkus.swagger-ui`)

## Authentication & authorization

All endpoints require a **Keycloak Bearer token** (realm `openbank`). Per-endpoint roles (`@RolesAllowed`):

| Endpoint | Roles |
|---|---|
| `GET /parties`, `GET /parties/search`, `GET /parties/{id}`, `GET /parties/{id}/documents` | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_KYC`, `ROLE_API` |
| `POST /parties`, `POST /parties/{id}/documents` | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_KYC` |
| `PATCH /parties/{id}` | `ROLE_OPERATOR`, `ROLE_ADMIN` (+ `@Authorize(action="party.update")` via OPA, advisory) |
| `PUT /parties/{id}/kyc-status` | `ROLE_ADMIN`, `ROLE_KYC` |
| `DELETE /parties/{id}` | `ROLE_ADMIN` |

OPA authorization (ADR-0034) runs in **advisory mode** (`authz.enforce=false`): `@Authorize`-annotated denies are logged at WARN but the request proceeds. The flip to enforce is a later phase.

## Idempotency & de-duplication

- `POST /api/v1/parties` **requires** the header `Idempotency-Key`. The key is carried into the create command.
- Independently, **email is unique** (DB constraint + `findByEmail`). Creating a party with an email that already exists returns **409 Conflict** (`PartyAlreadyExistsException`). This is the primary replay/duplicate guard.

## Endpoints

### Create party

```http
POST /api/v1/parties
Content-Type: application/json
Authorization: Bearer <token>
Idempotency-Key: <client-uuid-v4>

{
  "partyType": "INDIVIDUAL",
  "legalName": "Jan Novák",
  "tradingName": null,
  "dateOfBirth": "1985-03-21",
  "nationality": "CZE",
  "taxId": null,
  "registrationNumber": null,
  "email": "jan.novak@example.com",
  "phone": "+420777123456",
  "address": { "line1": "Wenceslas Sq 1", "line2": null, "city": "Praha", "postalCode": "11000", "countryCode": "CZ" }
}
```

```http
201 Created
Location: /api/v1/parties/{uuid}

{
  "id": "…", "partyType": "INDIVIDUAL", "status": "PENDING_KYC",
  "legalName": "Jan Novák", "email": "jan.novak@example.com",
  "kycStatus": "NOT_STARTED", "createdAt": "…", "updatedAt": "…"
}
```

- `partyType` ∈ INDIVIDUAL / SOLE_TRADER / COMPANY / TRUST.
- `email` is **required**; omitting it returns `400` (`"email is required"`).
- New parties start `status=PENDING_KYC`, `kycStatus=NOT_STARTED`, `amlStatus=NOT_SCREENED`.
- Side-effect: a `party_outbox` row with `PARTY_CREATED`, dispatched to Kafka within ~5 s.

### Get / list / search

```http
GET /api/v1/parties/{id}
GET /api/v1/parties?page=0&size=20&status=PENDING_KYC
GET /api/v1/parties/search?q=Novak&limit=20&cursor=<opaque>
```

- **List** is page/size paginated (size clamped to `[1,100]`); optional `status` filter (PENDING_KYC / ACTIVE / SUSPENDED / CLOSED) powers the onboarding cockpit funnel (ADR-0068). Response echoes `statusFilter`. The handler sets a cosmetic `X-Party-List-Mode: enriched|standard` header driven by the `party-list-enriched` flag.
- **Search** (ADR-0055) is a case-insensitive trigram substring match over legal/trading name, keyset-cursor paginated. A blank/`*`/sub-2-char term returns an empty page (no full-table enumeration). Gated by the `party-search` feature flag (`@FeatureFlag`). Returns a **data-minimised** summary; **birth number is never searchable**.

### Update party

```http
PATCH /api/v1/parties/{id}
{ "email": "...", "phone": "...", "tradingName": "...", "address": { ... } }
```

Partial update — only provided fields change. Emits `PARTY_UPDATED`.

### Documents

```http
POST /api/v1/parties/{id}/documents
{ "documentType": "PASSPORT", "documentNumber": "...", "issuingCountry": "CZ", "expiryDate": "2030-01-01" }

GET  /api/v1/parties/{id}/documents
```

`documentType` (code enum) ∈ NATIONAL_ID / PASSPORT / DRIVING_LICENCE / COMPANY_REGISTRATION / TAX_ID.

### KYC status

```http
PUT /api/v1/parties/{id}/kyc-status
{ "kycStatus": "APPROVED" }
```

`kycStatus` ∈ NOT_STARTED / IN_PROGRESS / APPROVED / REJECTED / EXPIRED. Recomputes party `status` via the two-key gate and emits `KYC_STATUS_CHANGED`.

### Erase party (GDPR Art. 17)

```http
DELETE /api/v1/parties/{id}
→ 204 No Content
```

Anonymises all PII (legal name → `ANONYMIZED`, email → random `erased-<uuid>@erased.invalid` tombstone, phone/address/dob/nationality/taxId/registrationNumber → null), sets `status=CLOSED`, and emits `PARTY_ERASED`. The tombstone email preserves the unique constraint without being correlatable back to the subject.

## Error model

Unified via `openbank-libs.api.error.ApiError`, mapped in `ExceptionMappers`.

| HTTP | Cause |
|---|---|
| 400 | validation error (e.g. missing `email`, invalid enum value) |
| 401 | missing / invalid token |
| 403 | role missing for the endpoint; or feature flag disabled (`@FeatureFlag` maps to a "feature disabled" error) |
| 404 | `PartyNotFoundException` — id does not exist |
| 409 | `PartyAlreadyExistsException` — email already registered |
| 429 | per-token rate limit (`openbank.rate-limit`, 150 concurrent) |
| 500 | unexpected error |

> OpenAPI note: the divergence between the bundled `openapi.yaml` (DELETE erase not listed; `documentType`/`kycStatus` enum names differ; server URL shows port 8126) and the running service (port 8111, the enums and DELETE above) is a known contract-drift to reconcile. The code is authoritative.

## Events

Outgoing topic: `openbank.party.events` (JSON, string serializer).

| Event type | Trigger | Payload (key fields) |
|---|---|---|
| `PARTY_CREATED` | create party | partyId, partyType, status, kycStatus, legalName, email, occurredAt |
| `PARTY_UPDATED` | update party | partyId, partyType, status, kycStatus, legalName, email, occurredAt |
| `KYC_STATUS_CHANGED` | KYC or AML outcome recorded (incl. ACTIVE flip) | partyId, partyType, status, kycStatus, legalName, email, occurredAt |
| `PARTY_ERASED` | GDPR erasure | partyId, erasedAt |

Incoming topics consumed (group `openbank-party-service`, `auto.offset.reset=earliest`):

| Topic | Handled event types | Effect |
|---|---|---|
| `openbank.kyc.events` | `KYC_CASE_APPROVED`, `KYC_CASE_REJECTED` | set `kycStatus`, recompute status |
| `openbank.aml.events` | `newStatus/status` ∈ `CLEARED`, `BLOCKED` | set `amlStatus`, recompute status |

## Versioning & backward compatibility

- **API version in URL** (`/api/v1/...`); `openbank.api.version=1`. Breaking changes ⇒ `/api/v2`.
- **OpenAPI contract axis** is independent of the release `version.txt` (ADR-0048).
- **Events** are additive-only on `openbank.party.events`; breaking changes would use a new topic.
