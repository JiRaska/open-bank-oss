# API

## Contract & versioning

- **Base path:** `/api/v1/parties` — all endpoints live under the `v1` API version (`openbank.api.version = "1"`, ADR-0048).
- **Media type:** `application/json` (consumes + produces).
- **Auth:** `Authorization: Bearer <JWT>` (Keycloak OIDC, RS256). Each endpoint is gated by `@RolesAllowed`; `changeStatus` is additionally guarded by OPA `@Authorize(action = "party.changeStatus", resource = "#id")` (advisory by default, ADR-0034).
- **OpenAPI:** served at `/q/openapi`; a checked-in `openapi.yaml` exists but is **partially out of sync with the code** (it predates the case-lifecycle endpoint, uses different request DTOs and a stale local-dev port/role naming). The endpoint table below is generated from the actual `PartyResource` and is authoritative. Reconciling `openapi.yaml` with the resource is a tracked follow-up.

> **Idempotency:** there is **no `Idempotency-Key` header / cache** in this service today (unlike account-service). Create is deduplicated at the data layer: a party with an existing bankID `sub` raises `PartyAlreadyExistsException` → **409**, and the `(id_type, id_value)` unique constraint on `party_external_ids` blocks duplicate external ids. Adding edge idempotency is a possible enhancement.

## Endpoints

| Method | Path | Roles | Purpose | Success |
|---|---|---|---|---|
| `POST` | `/api/v1/parties` | employee, admin | Create a party (unified identity) | `201` + `PartyResponse`, `Location` header |
| `GET` | `/api/v1/parties/{id}` | employee, admin, customer | Get party by internal UUID | `200` + `PartyResponse` |
| `GET` | `/api/v1/parties/by-external-id?type=&value=` | employee, admin | Resolve party by external id (`type`=`ExternalIdType`) | `200` + `PartyResponse` |
| `GET` | `/api/v1/parties?givenName=&familyName=&email=&role=&status=&limit=20&afterId=` | employee, admin | Search / keyset-paginate parties | `200` + `PartyResponse[]` |
| `POST` | `/api/v1/parties/{id}/sync/bankid` | employee, admin | Overwrite core + contact attrs from bankID | `200` + `PartyResponse` |
| `POST` | `/api/v1/parties/{id}/sync/rob` | employee, admin | Sync address + AIFO from ROB | `200` + `PartyResponse` |
| `PATCH` | `/api/v1/parties/{id}/contact` | employee, admin, customer | Update email / phone / language / data-box | `200` + `PartyResponse` |
| `PUT` | `/api/v1/parties/{id}/kyc` | employee, admin | Set KYC level + AML risk + PEP/sanctions flags | `200` + `PartyResponse` |
| `PATCH` | `/api/v1/parties/{id}/status` | admin | Change status (+ OPA `@Authorize`) | `200` + `PartyResponse` |
| `PATCH` | `/api/v1/parties/{id}/case` | employee, admin | Transition the PID verification case | `200` + `PartyResponse` |
| `POST` | `/api/v1/parties/{id}/relationships` | employee, admin | Add a role/relationship | `201` + `RelationshipResponse`, `Location` header |
| `DELETE` | `/api/v1/parties/{id}/relationships/{relationshipId}` | employee, admin | Terminate a relationship | `200` + `RelationshipResponse` |

## Key request/response shapes

### `CreatePartyRequest` (POST /parties)

```json
{
  "partyType": "NATURAL_PERSON",        // NATURAL_PERSON | LEGAL_ENTITY | SOLE_TRADER (default NATURAL_PERSON)
  "givenName": "Jan",
  "familyName": "Novák",
  "birthdate": "1985-04-12",
  "nationalities": ["CZ"],
  "verificationSource": "BANKID",       // BANKID | BRANCH_MANUAL | API_UPLOAD | ROB (default BANKID)
  "bankIdSub": "bankid|abc123",         // optional; if present, must be unique
  "initialRole": "CUSTOMER",            // CUSTOMER | EMPLOYEE | ADMIN | AGENT | GUARANTOR | AUTHORIZED_PERSON
  "onboardingChannel": "BANKID"         // BANKID | BRANCH | API | MOBILE_APP
}
```

On create the service sets `status=ACTIVE`, `kycLevel=BASIC`, `amlRiskScore=LOW`, opens a `PID_VERIFICATION` case (`status=OPEN`), and emits `PartyCreated`, `case.created`, `RelationshipAdded`.

### `PartyResponse` (returned everywhere)

Nested object with `id`, `partyType`, `status`, `externalIds[]`, `coreAttributes`, `addressAttributes?`, `contactAttributes`, `kycAttributes`, `relationships[]`, `caseLifecycle?`, `createdAt`, `updatedAt`, `version`. See `dto/PartyDtos.kt` for the full field list. Note: the encrypted birth number is **never** serialized into `coreAttributes`.

### `UpdateKycRequest` (PUT /kyc)

```json
{ "kycLevel": "ENHANCED", "amlRiskScore": "MEDIUM", "pepFlag": false, "sanctionsFlag": false }
```

### `TransitionCaseRequest` (PATCH /case)

```json
{ "status": "IN_REVIEW", "actor": "ops:alice", "reasonCode": "REVIEW_STARTED", "reason": "manual KYC", "metadata": {} }
```

`status` ∈ `CaseStatus` (DRAFT, OPEN, IN_REVIEW, WAITING_FOR_CUSTOMER, WAITING_FOR_EXTERNAL_PARTY, APPROVED, REJECTED, CLOSED, CANCELLED); `reasonCode` ∈ `CaseReasonCode`. Illegal transitions are rejected by `CaseTransitionEngine`.

## Error model

Errors use `openbank-libs`' uniform `ApiError` body:

```json
{ "traceId": "f1c2…", "status": 404, "code": "NOT_FOUND", "message": "Party … not found" }
```

| HTTP | `code` | When | Source |
|---|---|---|---|
| `400` | `VALIDATION_ERROR` | illegal PID case transition | `InvalidPartyCaseTransitionMapper` |
| `404` | `NOT_FOUND` | party / relationship / external id not found | `PartyNotFoundMapper` |
| `409` | `CONFLICT` | duplicate bankID sub on create | `PartyAlreadyExistsMapper` |
| `409` | `CONFLICT` | party already has the active role | `RelationshipAlreadyExistsMapper` |
| `401`/`403` | — | missing token / wrong role | Quarkus security (`@RolesAllowed`) |

## Events emitted (Kafka topic `party.events`)

Keyed by `aggregateId` (party UUID), envelope `{eventType, aggregateId, occurredAt, payload}`:

`PartyCreated`, `PartyVerified`, `KycLevelChanged`, `PartyStatusChanged`, `RelationshipAdded`, `RelationshipTerminated`, `AddressUpdatedFromRob`, `case.created`, `case.transitioned`, `case.evidence.linked`. All carry `version = 1`.
