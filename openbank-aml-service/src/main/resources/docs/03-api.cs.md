# API a kontrakty

## Základní cesta

- **In-cluster base:** `http://openbank-aml-service:8117/api/v1` (aplikační port 8117)
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8117/q/openapi)
- **Swagger UI:** [`/api/docs`](http://localhost:8117/api/docs) (`quarkus.swagger-ui.always-include=true`)
- **Verze API:** `v1` (`openbank.api.version=1`, prefix URL `/api/v1`)

> **Poznámka ke kontraktu:** zaregistrovaný `openapi.yaml` je vůči implementovanému `AmlCaseResource` částečně zastaralý — dokumentuje jiné názvy polí (např. `triggerType`/`riskScore`/`decision`) a jiný status enum (`SAR_FILED`, `NO_ACTION`, …). **Tato stránka dokumentuje kontrakt, který kód skutečně poskytuje.** OpenAPI dokument je třeba znovu sesynchronizovat s resourcem (sledováno jako follow-up).

## Autentizace a autorizace

Všechny endpointy vyžadují **Keycloak Bearer token** (realm `openbank`, OIDC). Mutující operace jsou role-gated přes `@RolesAllowed`:

| Endpoint | Vyžadovaná role |
|---|---|
| `POST /api/v1/aml/cases` | `ROLE_OPERATOR`, `ROLE_ADMIN` nebo `ROLE_COMPLIANCE` |
| `GET /api/v1/aml/cases` / `GET .../{caseId}` | autentizovaný (bez extra role) |
| `PUT /api/v1/aml/cases/{caseId}/decision` | `ROLE_OPERATOR`, `ROLE_ADMIN` nebo `ROLE_COMPLIANCE` |

Decision endpoint navíc nese `@Authorize(action = "amlCase.updateDecision", resource = "#caseId")` — kontrola OPA politiky přes sidecar PDP (ADR-0034). Autorizace je ve výchozím stavu **advisory** (`authz.enforce=false`): zamítnutí se logují na WARN a požadavek přesto projde; přepne se přes `AUTHZ_ENFORCE=true`.

## Idempotence

`POST /api/v1/aml/cases` **vyžaduje** hlavičku:

```
Idempotency-Key: <klíč generovaný klientem>
```

Pravidla:
- Hlavička musí být neprázdná (jinak je požadavek odmítnut).
- Klíč je cachován v Redis. Replay se stejným klíčem vrátí cachovanou odpověď s hlavičkou `X-Idempotency-Replayed: true`.
- Klíč je rovněž perzistován jako unikátní sloupec `idempotency_key` na řádku případu — opakovaný create znovu použije existující případ místo vytvoření duplikátu. (Onboarding konzument používá jako klíč `"<partyId>:CUSTOMER_ONBOARDING"`.)

## Endpointy

### Založení AML případu

```http
POST /api/v1/aml/cases
Content-Type: application/json
Authorization: Bearer <token>
Idempotency-Key: 5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b

{
  "partyId": "f0a1...uuid",
  "accountId": null,
  "transactionId": null,
  "customerReference": "CUST-00042",
  "screeningType": "TRANSACTION_MONITORING",
  "riskLevel": "HIGH",
  "alertCode": "TXN_THRESHOLD",
  "alertDetail": "Souhrn > 15k EUR / 24h",
  "matchedEntity": null
}
```

```http
201 Created
Location: /api/v1/aml/cases/{caseId}
Content-Type: application/json

{
  "id": "...uuid",
  "idempotencyKey": "5e9f8b6a-...",
  "partyId": "f0a1...uuid",
  "accountId": null,
  "transactionId": null,
  "customerReference": "CUST-00042",
  "screeningType": "TRANSACTION_MONITORING",
  "riskLevel": "HIGH",
  "status": "UNDER_REVIEW",
  "alertCode": "TXN_THRESHOLD",
  "alertDetail": "Souhrn > 15k EUR / 24h",
  "matchedEntity": null,
  "decisionReason": null,
  "assignedAnalyst": null,
  "decidedBy": null,
  "screenedAt": "2026-06-09T10:42:13Z",
  "decidedAt": null,
  "createdAt": "2026-06-09T10:42:13Z",
  "updatedAt": "2026-06-09T10:42:13Z"
}
```

- `screeningType` ∈ `CUSTOMER_ONBOARDING | TRANSACTION_MONITORING | PERIODIC_REVIEW | MANUAL_INVESTIGATION`
- `riskLevel` ∈ `LOW | MEDIUM | HIGH | CRITICAL` — `HIGH`/`CRITICAL` zakládají `UNDER_REVIEW`, `LOW`/`MEDIUM` zakládají `OPEN`.
- **Vedlejší efekty:** řádek `aml_outbox` s `aml.case.created.v1`; dispatcher publikuje do Kafky během vteřin.

### Získání AML případu

```http
GET /api/v1/aml/cases/{caseId}
```
`200 OK` s tělem případu, nebo `404` pokud id neexistuje.

### Výpis AML případů

```http
GET /api/v1/aml/cases?status=UNDER_REVIEW&partyId=...&screeningType=TRANSACTION_MONITORING&limit=50&offset=0
```
Všechny query parametry volitelné. `limit` výchozí 50 (server clampuje na 1..200); `offset` výchozí 0. Vrací JSON pole těl případů.

### Aktualizace rozhodnutí

```http
PUT /api/v1/aml/cases/{caseId}/decision
Content-Type: application/json

{
  "targetStatus": "CLEARED",
  "decisionReason": "Po přezkumu žádná nepříznivá shoda",
  "assignedAnalyst": "analyst-7",
  "decidedBy": "analyst-7"
}
```

- `targetStatus` musí být platný přechod z aktuálního stavu (viz stavový automat v [02 — Architektura](./02-architecture.md)).
- `decidedBy` je povinné; `decisionReason` je povinné při `targetStatus = BLOCKED`.
- **Vedlejší efekty:** přechod + událost `aml.case.status_changed.v1` se commitují atomicky přes outbox.

## Model chyb

Jednotně přes `openbank-libs` `ApiError` (`{ correlationId, status, code, message }`):

| HTTP | code | Kdy |
|---|---|---|
| 400 | validation | chybějící `Idempotency-Key`, špatná hodnota enumu, vadné tělo |
| 401 | unauthorized | chybějící / neplatný token |
| 403 | forbidden | chybějící role nebo OPA deny (při enforce) |
| 404 | `not-found` | `AmlCaseNotFoundException` — id případu neexistuje |
| 409 | `conflict` | `InvalidAmlCaseStateTransitionException` — neplatný přechod stavu |
| 500 | internal-error | neočekávaná chyba (s correlationId pro podporu) |

## Události

Odchozí topic: `openbank.aml.events` (JSON; partition key = aggregate id případu; hlavičky `ce-id` / `idempotency-key` / `ce-type`).

| Typ události | Spouštěč | Payload (klíčová pole) |
|---|---|---|
| `aml.case.created.v1` | `POST /aml/cases` nebo onboarding konzument | caseId, idempotencyKey, partyId, accountId, transactionId, customerReference, screeningType, riskLevel, status, alertCode, matchedEntity, occurredAt |
| `aml.case.status_changed.v1` | `PUT .../decision` | caseId, partyId, previousStatus, newStatus, decisionReason, assignedAnalyst, decidedBy, occurredAt |

Příchozí topic: `openbank.party.events` — konzumace `PARTY_CREATED` (skupina `openbank-aml-service`, `auto.offset.reset=earliest`).

Události jsou **append-only**; opravy se dělají následným přechodovým eventem, ne přepsáním.

## Zpětná kompatibilita

- **Verze API v URL** (`/api/v1/...`). Breaking změny ⇒ `/api/v2/...`. Dvě verzovací osy (release `version.txt` vs `openapi.yaml:info.version`) jsou nezávislé (ADR-0048).
- **Verze události v příponě typu** (`...v1`). Evoluce schématu je aditivní (volitelná pole); breaking změny dostanou novou příponu verze.
- **OpenAPI diff** v CI proti `main` — ale pozor, výše popsaný drift specifikace je nutné nejprve smířit.
