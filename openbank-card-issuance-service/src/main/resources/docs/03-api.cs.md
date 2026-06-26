# API & kontrakty

REST kontrakt je popsán v [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1, `info.version: 1.0.0`). Níže uvedené endpointy jsou autoritativní množinou implementovanou v `CardResource`; tam, kde se commitnutý `openapi.yaml` a kód neshodují, vyhrává kód a rozdíl je vyznačen inline (spec ještě není plně sladěn — viz poznámky na konci).

## Základní cesta

- **Aplikační base:** `http://openbank-card-issuance-service:8118/api/v1` (in-cluster)
- **Swagger UI (dev):** `/api/docs`
- **OpenAPI spec:** servíruje SmallRye OpenAPI

## Autentizace

Všechny endpointy vyžadují **Keycloak Bearer token** (realm `openbank`). Operace jsou role-gated přes `@RolesAllowed`:

| Endpoint | Role |
|---|---|
| `GET` (všechny čtecí endpointy) | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /cards` (vydání) | `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /cards/{id}/activate` | `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /cards/{id}/suspend` | `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /cards/{id}/resume` | `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /cards/{id}/block` | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_COMPLIANCE` |

## Idempotence

Vydání karty vyžaduje hlavičku:

```
Idempotency-Key: <klientem-generované-uuid-v4>
```

Resource odmítá prázdný klíč. Klíč se persistuje na řádek `cards` (`idempotency_key`, `UNIQUE NOT NULL`); `CardService.issueCard` nejprve volá `findByIdempotencyKey` a při zásahu vrátí existující kartu místo vydání nové — opakování se stejným klíčem je tedy replay-safe a nikdy nevytvoří duplicitní kartu.

> Endpointy změny stavu (`activate`/`suspend`/`resume`/`block`) jsou na doménové úrovni přirozeně idempotentní: strážci `require(...)` stavového automatu odmítnou přechod ze stavu, který je již terminální/nekompatibilní.

## Endpointy

### Vydání karty

```http
POST /api/v1/cards
Content-Type: application/json
Authorization: Bearer <token>
Idempotency-Key: 5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b

{
  "partyId": "0f9d…",
  "accountId": "1a2b…",
  "productCode": "DEBIT_STANDARD",
  "cardType": "DEBIT",
  "network": "VISA",
  "cardholderName": "JAN NOVAK",
  "embossedName": "JAN NOVAK",
  "currency": "CZK",
  "dailyLimitMinorUnits": 500000,
  "monthlyLimitMinorUnits": 5000000,
  "deliveryAddress": "Václavské náměstí 1, Praha"
}
```

```http
201 Created
Location: /api/v1/cards/{id}

{
  "id": "…",
  "partyId": "…",
  "accountId": "…",
  "productCode": "DEBIT_STANDARD",
  "cardType": "DEBIT",
  "network": "VISA",
  "maskedPan": "**** **** **** 1234",
  "cardholderName": "JAN NOVAK",
  "embossedName": "JAN NOVAK",
  "expiryDate": "2030-06-09",
  "status": "PENDING",
  "dailyLimitMinorUnits": 500000,
  "monthlyLimitMinorUnits": 5000000,
  "currency": "CZK",
  "deliveryAddress": "…",
  "activatedAt": null,
  "blockedAt": null,
  "blockedReason": null,
  "createdAt": "…",
  "updatedAt": "…"
}
```

Defaulty: `dailyLimitMinorUnits=500000`, `monthlyLimitMinorUnits=5000000` při vynechání; `expiryDate` se nastaví na datum vydání + 4 roky; maskovaný PAN se generuje na serveru. **Vedlejší efekt:** řádek `card_outbox` nesoucí `card.issued.v1`.

### Přechody životního cyklu

| Volání | Z → Do | Tělo | Hlavička |
|---|---|---|---|
| `POST /cards/{id}/activate` | PENDING → ACTIVE | — | `X-Operator-Id` |
| `POST /cards/{id}/suspend` | ACTIVE → SUSPENDED | — | `X-Operator-Id` |
| `POST /cards/{id}/resume` | SUSPENDED → ACTIVE | — | `X-Operator-Id` |
| `POST /cards/{id}/block` | {ACTIVE, SUSPENDED} → BLOCKED | `{ "reason": "LOST" }` | `X-Operator-Id` |

`X-Operator-Id` se zaznamenává jako `changedBy` na emitované události `card.status_changed.v1`. Přechod porušující stavový automat vyvolá selhání předpokladu (např. aktivace ne-PENDING karty, blokace s prázdným důvodem).

### Čtení

| Volání | Vrací |
|---|---|
| `GET /api/v1/cards` | všechny karty |
| `GET /api/v1/cards/{id}` | jednu kartu, nebo `404 {"error":"Card not found"}` |
| `GET /api/v1/cards/account/{accountId}` | karty pro účet |
| `GET /api/v1/cards/party/{partyId}` | karty pro klienta |

## Model chyb

`GET /cards/{id}` vrací `404` s jednoduchým tělem `{"error":"Card not found"}`. Ostatní selhání se projeví jako standardní Quarkus/RESTEasy odpovědi:

| HTTP | Kdy |
|---|---|
| 400 | vadné tělo, prázdný `Idempotency-Key`, nelegální přechod stavu (`require`/`IllegalArgumentException`) |
| 401 | chybějící / neplatný token |
| 403 | chybějící role pro endpoint |
| 404 | id karty neexistuje |
| 500 | neočekávaná chyba |

> Jednotná obálka chyb problem+json v této službě zatím není zapojena; jde o follow-up hardening.

## Události

Topic: `openbank.cards.events`.

| Typ události | Spouštěč | Payload (klíčová pole) |
|---|---|---|
| `card.issued.v1` | `POST /cards` | cardId, partyId, accountId, cardType, network, maskedPan, occurredAt |
| `card.status_changed.v1` | activate / suspend / resume / block | cardId, previousStatus, newStatus, reason, changedBy, occurredAt |

Každý Kafka záznam nese hlavičky `ce-id` (= id události), `idempotency-key` (= id události) a `ce-type` (typ události); partition key je id karty (viz [02 — Architektura](./02-architecture.md)).

## Zpětná kompatibilita

- **Verze API v URL** (`/api/v1/...`). Breaking změny ⇒ `/api/v2/...` (ADR-0048: major `openapi.yaml:info.version` == `/api/v{N}`).
- **Verze události v sufixu typu** (`...v1`). Evoluce schématu: pouze aditivní; breaking změny dostanou nový sufix verze.
- CI `oasdiff` kontroluje API změny proti `main`.

## Známé rozdíly spec/kód (k sladění)

- `openapi.yaml` uvádí dev server na portu `8115`; služba ve skutečnosti běží na **8118** (`application.yaml`).
- `openapi.yaml` `IssueCardRequest` vynechává `productCode`, `cardholderName`, `currency`, `dailyLimitMinorUnits`, `monthlyLimitMinorUnits`, které kód vyžaduje/přijímá, a modeluje `deliveryAddress` jako objekt, zatímco kód používá řetězec.
- Enumy `cardType` / `cardNetwork` ve specu jsou užší než doména (chybí `VIRTUAL`, `AMEX`, `UNIONPAY`, `CANCELLED`, `PENDING`).
- `GET /api/v1/cards` (seznam všech) a sémantika rolí / `X-Operator-Id` nejsou ve specu plně popsány.
