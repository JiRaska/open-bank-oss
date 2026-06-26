# API & contracts

## Base path

- **Production base:** `http://openbank-account-service:8100/api/v1` (in-cluster)
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8100/q/openapi)
- **Swagger UI (dev):** [`/q/swagger-ui`](http://localhost:8100/q/swagger-ui)

## Autentizace

Všechny endpointy vyžadují **Keycloak Bearer token** s realm `openbank`. Mutační operace navíc role-gated:

| Role | Práva |
|---|---|
| `ROLE_VIEWER` | GET only |
| `ROLE_OPERATOR` | GET + open / freeze / unfreeze / close |
| `ROLE_COMPLIANCE` | GET + freeze / unfreeze (court orders) |
| `ROLE_ADMIN` | vše |

## Idempotence

Všechny **POST / PUT / PATCH** vyžadují header:

```
Idempotency-Key: <client-generated-uuid-v4>
```

Pravidla:
- Klient generuje UUID v4 per logical request (NE per HTTP retry).
- Key+endpoint cache 24h v Redis. Stejný key + stejný body → vrátí cached response (replay-safe).
- Stejný key + jiný body → **409 Conflict** s `code=idempotency-key-mismatch`.

## Klíčové endpointy

### Open account

```http
POST /api/v1/accounts
Content-Type: application/json
Authorization: Bearer <token>
Idempotency-Key: 5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b

{
  "ownerPartyId": "party-abc123",
  "type": "CURRENT",
  "currency": "EUR",
  "initialBalance": { "amount": "1000.00", "currency": "EUR" }
}
```

```http
201 Created
Location: /api/v1/accounts/acc-7f3e2a1b
Content-Type: application/json

{
  "id": "acc-7f3e2a1b",
  "iban": "CZ6508000000192000145399",
  "ownerPartyId": "party-abc123",
  "type": "CURRENT",
  "currency": "EUR",
  "status": "ACTIVE",
  "openedAt": "2026-05-29T10:42:13Z"
}
```

**Side-effects:**
- `account_outbox` row s eventem `AccountOpened` (dispatcher publikuje do Kafka < 1s).
- `audit-service` zaeviduje (consumer eventu).

### Freeze account

```http
POST /api/v1/accounts/{id}/freeze
Idempotency-Key: ...

{
  "reason": "AML_HOLD",
  "reference": "case-2026-1234",
  "expiresAt": "2026-06-15T00:00:00Z"
}
```

Reasons: `AML_HOLD`, `COURT_ORDER`, `FRAUD_INVESTIGATION`, `CUSTOMER_REQUEST`.

### Authorizations

```http
POST /api/v1/accounts/{id}/authorizations
{
  "partyId": "party-xyz",
  "role": "SIGNATORY",
  "scope": ["VIEW", "TRANSACT"]
}
```

## Error model

Sjednocený přes `openbank-libs.api.ApiError`:

```json
{
  "type": "https://openbank.example/errors/account-frozen",
  "title": "Account is frozen",
  "status": 422,
  "detail": "Account acc-7f3e2a1b is frozen (reason=AML_HOLD until 2026-06-15)",
  "instance": "/api/v1/accounts/acc-7f3e2a1b/close",
  "correlationId": "01HXXXX...",
  "code": "account-frozen"
}
```

| HTTP | code | Kdy |
|---|---|---|
| 400 | `validation-failed` | DTO validace (Iban, Currency, …) |
| 401 | `unauthorized` | chybí / neplatný token |
| 403 | `forbidden` | role chybí pro endpoint |
| 404 | `account-not-found` | ID neexistuje |
| 409 | `idempotency-key-mismatch` | replay s jiným body |
| 409 | `account-already-closed` | close již proběhl |
| 422 | `account-frozen` | mutace na frozen účtu |
| 422 | `insufficient-authorization` | not OWNER pro tuto operaci |
| 429 | `rate-limited` | per-token quota |
| 500 | `internal-error` | nečekaná chyba (s correlationId pro support) |

## Eventy

Topic: `openbank.account.events.v1` (CloudEvents binding, JSON).

| Event type | Trigger | Payload (klíčové fieldy) |
|---|---|---|
| `account.opened.v1` | POST /accounts | id, iban, ownerPartyId, type, currency, openedAt |
| `account.frozen.v1` | POST .../freeze | id, reason, reference, frozenAt, expiresAt |
| `account.unfrozen.v1` | POST .../unfreeze | id, reason, unfrozenAt |
| `account.closed.v1` | POST .../close | id, reason, closedAt, finalBalance |
| `authorization.granted.v1` | POST .../authorizations | accountId, partyId, role, scope |
| `authorization.revoked.v1` | DELETE .../authorizations/{id} | accountId, partyId, revokedAt |

Eventy jsou **append-only**; opravy se dělají kompenzačním eventem (např. `account.unfrozen.v1`), ne přepisem.

## Backward compatibility

- **API verze v URL** (`/api/v1/...`). Breaking změny = `/api/v2/...`, v1 běží paralelně 6 měsíců.
- **Event verze v topic name** (`...v1`). Schema evolution: jen additive (přidání optional fieldů), breaking = nový topic.
- **OpenAPI diff** v CI proti `main` — fail pokud removed endpoint / required-added field bez verze bumpu.
