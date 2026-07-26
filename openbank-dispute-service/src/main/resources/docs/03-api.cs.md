# API

REST kontrakt je popsán v [`openapi.yaml`](../openapi.yaml) (OpenAPI **3.1.0**, `info.version: 1.0.0`, titul *Dispute Service API*). Všechny cesty jsou pod `/api/v1` (ADR-0048 — URL major odpovídá major verzi API kontraktu). Swagger UI je vystaveno na `/api/docs`.

> **Pozor na drift kontraktu:** commitnutý `openapi.yaml` uvádí `servers.url: http://localhost:8113`, ale služba se ve skutečnosti váže na **8135** (`application.yaml`). Schéma `OpenDisputeRequest` v `openapi.yaml` také vynechává `partyId` a `transactionDate`, které Kotlin DTO `OpenDisputeRequest` **vyžaduje**. Zdrojem pravdy je Kotlin kód; OpenAPI soubor potřebuje sladit (TBD).

## Báze a obsah

- **Base path:** `/api/v1/disputes`
- **Media type:** `application/json` (consumes & produces)
- **Autentizace:** Bearer JWT (Keycloak OIDC). Na úrovni třídy `@RolesAllowed("ROLE_VIEWER","ROLE_OPERATOR","ROLE_ADMIN","ROLE_API")`; čtení povolují `ROLE_VIEWER`, zápisy vyžadují `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API`.

## Endpointy

| Metoda | Cesta | Role | Shrnutí |
|---|---|---|---|
| `POST` | `/api/v1/disputes` | OPERATOR/ADMIN/SERVICE | Otevřít novou reklamaci → `201` |
| `GET` | `/api/v1/disputes?status=` | jakákoli role | Seznam reklamací dle stavu (výchozí `OPEN`) |
| `GET` | `/api/v1/disputes/{id}` | jakákoli role | Získat reklamaci dle ID → `200` / `404` |
| `PUT` | `/api/v1/disputes/{id}` | OPERATOR/ADMIN/SERVICE | Aktualizovat stav / řešení (`@Authorize dispute.update`) |
| `GET` | `/api/v1/disputes/reference/{ref}` | jakákoli role | Získat reklamaci dle reference → `200` / `404` |
| `GET` | `/api/v1/disputes/account/{accountId}` | jakákoli role | Seznam reklamací pro účet |
| `POST` | `/api/v1/disputes/{id}/evidence` | OPERATOR/ADMIN/SERVICE | Přidat důkaz → `201` |
| `GET` | `/api/v1/disputes/{id}/evidence` | jakákoli role | Seznam důkazů |
| `POST` | `/api/v1/disputes/{id}/withdraw?actor=` | OPERATOR/ADMIN/SERVICE | Stáhnout reklamaci |
| `POST` | `/api/v1/disputes/{id}/escalate?actor=` | OPERATOR/ADMIN/SERVICE | Eskalovat reklamaci |
| `GET` | `/api/v1/disputes/{id}/timeline` | jakákoli role | Získat časovou osu reklamace |

## Požadavek — otevřít reklamaci

`POST /api/v1/disputes` (tělo, dle Kotlin DTO):

```json
{
  "transactionId": "uuid",
  "accountId": "uuid",
  "partyId": "uuid",
  "disputeType": "UNAUTHORIZED",
  "amount": 129.90,
  "currency": "EUR",
  "description": "Card not present, not me",
  "merchantName": "ACME GmbH",
  "merchantId": "MID-123",
  "transactionDate": "2026-05-30"
}
```

`disputeType` ∈ `UNAUTHORIZED | DUPLICATE | GOODS_NOT_RECEIVED | NOT_AS_DESCRIBED | CREDIT_NOT_PROCESSED | TECHNICAL_ERROR | OTHER`. Server přiřadí `reference`, nastaví `status=OPEN`, `resolution=PENDING` a `resolutionDeadline = dnes + 45 dní`.

## Požadavek — aktualizace

`PUT /api/v1/disputes/{id}` — všechna pole volitelná:

```json
{ "status": "RESOLVED_CUSTOMER", "resolution": "CHARGEBACK", "chargebackAmount": 129.90, "resolvedBy": "operator-42" }
```

`status` ∈ `OPEN | UNDER_REVIEW | PENDING_CUSTOMER | PENDING_MERCHANT | RESOLVED_CUSTOMER | RESOLVED_MERCHANT | WITHDRAWN | ESCALATED`. Dosažení `RESOLVED_*` nebo `WITHDRAWN` orazítkuje `resolvedAt`.

## Idempotence

`Idempotency-Key` je povolen konfigurací CORS, ale služba zatím **nevynucuje** idempotentní opakování u mutací (v `DisputeResource` není zapojen idempotency store). `open` vytváří referenci založenou na čase (`DSP-<epochMillis>`); opakovaný `POST` vytvoří novou reklamaci. Zpevnění idempotence je sledovaný follow-up (TBD).

## Chybový model

Chyby používají schéma `ApiError` `{ "code": "...", "message": "..." }` pro `404`. Pozor: resource aktuálně mapuje neočekávaná selhání u `open`/`update` na `500` s tělem `{ "error": "<message>" }` namísto strukturovaného `ApiError`; `GET /{id}` vrací `404` (prázdné), když nenalezeno. Sjednocení chybových odpovědí na jednu obálku je follow-up (TBD).

## Verzování

- URL major: `/api/v1` (== `openbank.api.version`).
- Verze API kontraktu: `openapi.yaml:info.version` = `1.0.0` — nezávislá na release verzi (`version.txt` / `quarkus.application.version`, ADR-0048).
- Hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info` poskytuje `openbank-libs`.
