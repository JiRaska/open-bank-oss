# API & kontrakty

## Základní cesta

- **Produkční base:** `http://openbank-standing-order-service:8121/api/v1` (in-cluster)
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8121/q/openapi)
- **Swagger UI:** [`/api/docs`](http://localhost:8121/api/docs) (nakonfigurováno `swagger-ui.path`)
- **Zdroj kontraktu:** `src/main/resources/openapi.yaml` (`info.version: 1.0.0`, `openapi: 3.1.0`).

> **Drift kontraktu — přečtěte před integrací.** Commitnutý `openapi.yaml` zatím plně neodpovídá implementovanému `StandingOrderResource` / DTO:
> - `servers` URL ve specifikaci uvádí port **8116**; služba reálně poslouchá na **8121** (`application.yaml`). Použijte 8121.
> - `CreateStandingOrderRequest` ve specifikaci používá `debtorAccountId`, `amount`, `currencyCode`. **Implementované** tělo požadavku (DTO `CreateStandingOrderRequest`) používá `idempotencyKey`, `partyId`, `debitAccountId`, `creditorIban`, `creditorName`, `creditorBic`, `amountMinorUnits`, `currency`, `frequency`, `paymentType`, `remittanceInfo`, `startDate`, `endDate`.
> - Specifikace vynechává endpoint `GET /api/v1/standing-orders` (výpis všech), který resource vystavuje.
>
> Níže uvedené implementované chování je autoritativní; OpenAPI soubor je třeba sladit (sledováno jako TBD).

## Autentizace & autorizace

- **AuthN:** Keycloak OIDC bearer token, realm `openbank` (OIDC vypnut v profilech `%dev`/`%test`).
- **AuthZ:** OPA sidecar přes `openbank-libs` `@Authorize` (ADR-0034). Režim je **ve výchozím stavu advisory** (`authz.enforce=false`); přepíná se na enforce per prostředí. Aktuálně operace `pause` nese `@Authorize(action = "standingOrder.pause", resource = "#id")`; ostatní mutace zatím nejsou anotovány (TBD — doplnit `resume`/`cancel`/`create`).

## Idempotence

Vytvoření je idempotentní přes pole požadavku, nikoli hlavičku:

- Klient dodá `idempotencyKey` v těle `CreateStandingOrderRequest`.
- `StandingOrderService.create` nejprve volá `findByIdempotencyKey(...)`; pokud příkaz s tím klíčem existuje, vrátí se beze změny (bezpečné při opakování).
- DB vynucuje unikátnost: `standing_orders.idempotency_key` má `UNIQUE` omezení.

## Endpointy (dle implementace)

### Vytvoření trvalého příkazu

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

Nový příkaz startuje jako `ACTIVE` s `nextExecutionDate = startDate`, `executionCount = 0`, `failureCount = 0`.

### Ostatní endpointy

| Metoda & cesta | Účel | Úspěch | Poznámky |
|---|---|---|---|
| `GET /api/v1/standing-orders` | Výpis všech příkazů | 200 | vrací `StandingOrderResponse[]` |
| `GET /api/v1/standing-orders/{id}` | Získat jeden příkaz | 200 | 404 při nenalezení |
| `GET /api/v1/standing-orders/party/{partyId}` | Výpis příkazů pro party | 200 | `StandingOrderResponse[]` |
| `POST /api/v1/standing-orders/{id}/pause` | Pozastavit ACTIVE příkaz | 200 | odmítne ne-ACTIVE (doménový `require`) |
| `POST /api/v1/standing-orders/{id}/resume` | Obnovit PAUSED příkaz | 200 | odmítne ne-PAUSED |
| `DELETE /api/v1/standing-orders/{id}` | Zrušit příkaz | 204 | odmítne CANCELLED/COMPLETED |

## Chybový model

Commitnutý kontrakt deklaruje minimální `ApiError` (`{ code, message }`). Mapování za běhu:

| HTTP | Kdy |
|---|---|
| 400 | vadné tělo / neplatná hodnota enumu |
| 401 | chybějící / neplatný token (při zapnutém OIDC) |
| 403 | OPA odmítne akci (při `authz.enforce=true`) |
| 404 | id příkazu neexistuje (`NotFoundException`) |
| 409 | (logicky) duplicitní `idempotencyKey` se vyhodnotí jako existující příkaz, ne chyba |
| 422 | nepovolený přechod stavu (např. pause na ne-ACTIVE) se projeví jako doménové selhání `require` |
| 429 | rate-limit (`openbank.rate-limit`, max 200 souběžných) |
| 500 | neočekávaná chyba |

> Sjednocený problém detail ve stylu RFC-7807 používaný money-path službami zde zatím není zapojen — tvar chyby je minimální `ApiError`. Sladit v rámci úklidu kontraktu (TBD).

## Události

Topic: `openbank.standing-orders.order.event` (Kafka, JSON payload, `String` serializery klíče/hodnoty). Publikováno přes transakční outbox.

| Doménová událost | Spouštěč | Payload (klíčová pole) |
|---|---|---|
| `StandingOrderCreated` | `POST /standing-orders` | id, partyId, currency, amountMinorUnits, at |
| `StandingOrderExecuted` | (plánováno) plánovač materializuje splatný příkaz | id, partyId, at |
| `StandingOrderCancelled` | `DELETE /standing-orders/{id}` | id, partyId, at |

Události jsou append-only; korekce se dělají kompenzační událostí, ne přepisem.

## Verzování & zpětná kompatibilita

- **Release osa:** `version.txt` = `0.2.0` (vlastní release-please; viz [05 — Provoz](./05-operations.md)).
- **API kontraktová osa:** `openapi.yaml info.version` = `1.0.0`, nezávislá na release verzi (ADR-0048). Major v URL (`/api/v1`) sleduje major kontraktu.
- **API verze v URL** — breaking změny jdou na `/api/v2`, v1 běží paralelně po dobu deprecation okna.
- **Verze události v názvu topicu** — evoluce schématu jen aditivně.
