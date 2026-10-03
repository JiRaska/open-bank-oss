# API

Kontrakt je `src/main/resources/openapi.yaml` (`info.version` 1.0.0, major v URL `/api/v1`). Swagger UI: `/api/docs` na portu 8157.

## Endpointy

| Metoda | Cesta | Role | OPA akce | Poznámky |
|---|---|---|---|---|
| POST | `/api/v1/card-authorizations` | `ROLE_API`, `ROLE_OPERATOR`, `ROLE_ADMIN` | `cardprocessing.authorize` | `Idempotency-Key` povinný. **201** pro schválení i zamítnutí |
| GET | `/api/v1/card-authorizations/{id}` | stejné | `cardprocessing.read` | 404, pokud neexistuje |
| POST | `/api/v1/card-authorizations/{id}/clearing` | stejné | `cardprocessing.clear` | `Idempotency-Key` povinný. 200 nebo 409 |
| POST | `/api/v1/card-authorizations/{id}/reversal` | stejné | `cardprocessing.reverse` | 200 nebo 409 |
| GET | `/api/v1/card-authorizations/card/{cardId}` | stejné | `cardprocessing.read` | od nejnovějších; `limit` výchozí 50, omezen na 1..200 |
| POST | `/api/v1/card-tokens` | `ROLE_API`, `ROLE_OPERATOR`, `ROLE_ADMIN` | `cardprocessing.token` | `Idempotency-Key` povinný. 201, 404, 409 nebo 503 |
| POST | `/api/v1/card-tokens/{tokenReference}/status` | stejné | `cardprocessing.token` | `Idempotency-Key` povinný; tělo `{ status }` — `ACTIVE`, `SUSPENDED`, `DELETED`; jiné hodnoty 400. 200, 404 nebo 409 |
| GET | `/api/v1/card-tokens/card/{cardId}` | stejné | `cardprocessing.read` | `{ tokens, source, degradedReason, count }` |
| POST | `/api/v1/card-disputes` | stejné | `cardprocessing.dispute` | `Idempotency-Key` povinný. 201, 400, 404 nebo 409 |
| POST | `/api/v1/card-disputes/{id}/evidence` | stejné | `cardprocessing.dispute` | `Idempotency-Key` povinný; tělo `{ documentReference, note }`; připojí se do historie důkazů |
| GET | `/api/v1/card-disputes/{id}/evidence` | stejné | `cardprocessing.read` | historie důkazů, od nejstaršího |
| POST | `/api/v1/card-disputes/{id}/refresh` | stejné | `cardprocessing.dispute` | `Idempotency-Key` povinný; znovu načte stav ze sítě; uzavřený případ vrátí beze změny |
| GET | `/api/v1/card-disputes/{id}` | stejné | `cardprocessing.read` | `status` i `schemeStatus` |
| GET | `/api/v1/card-disputes/card/{cardId}` | stejné | `cardprocessing.read` | od nejnovějších; `limit` výchozí 50, omezen na 1..200 |
| POST | `/api/v1/sandbox/acquirer/purchase` | `ROLE_ADMIN` | `cardprocessing.simulate` | **404, pokud není** `openbank.card-processing.sandbox-acquirer-enabled=true` |

## Požadavek na autorizaci

Pole: `cardId`, `amountMinorUnits`, `currencyCode`, `channel` (`CONTACTLESS`, `ONLINE`, `ATM`, `CHIP_AND_PIN`), volitelně `mcc`, `merchantName`, `merchantCountry`, `networkReference`. PAN ani kartové údaje se nepřijímají.

Zamítnutí je vytvořený záznam rozhodnutí, proto je to **201**, ne 4xx; odpověď nese `status: DECLINED` a `declineReason` z card-issuance.

## Idempotence

- **Autorizace:** `Idempotency-Key` se ukládá na řádek pod UNIQUE indexem; opakovaný klíč vrátí první autorizaci beze změny a druhý hold nevznikne.
- **Clearing:** klíč je povinný a předává se transaction-service jako `card-clearing:<klíč>` pro zaúčtování. Služba před započtením prezentace klíč clearingu nevyhledává, takže opakovaná prezentace se započte znovu, pokud se ještě vejde do zbývajícího holdu.
- **Sandbox nákup:** `idempotencyKey` v těle slouží jako klíč autorizace i jako network reference; klíč clearingu je `<klíč>:clearing`.

## Model chyb a odmítnutí

| Situace | Status |
|---|---|
| Chybějící nebo prázdný `Idempotency-Key`, nekladná částka, měna ≠ měna karty | 400 (`IllegalArgumentException` mapuje libs-runtime) |
| Karta neznámá pro card-issuance | 404 |
| Clearing/reverzace odmítnuta životním cyklem | **409** s `{ reason, message }` |

`reason` je jedna z hodnot `NOT_HOLDING_FUNDS` (koncový stav nebo neznámé id autorizace), `AMOUNT_NOT_POSITIVE`, `EXCEEDS_AUTHORIZED_AMOUNT`, `CURRENCY_MISMATCH`, `NOT_YET_EXPIRED` (jen expirace, interní).

## Odmítnutí u tokenů a reklamací

Oba resource odpovídají na odmítnutí `{ reason, message }`. `CARD_NOT_FOUND`, `TOKEN_NOT_FOUND`, `AUTHORIZATION_NOT_FOUND` a `CASE_NOT_FOUND` jsou **404**; `ISSUER_UNAVAILABLE` (card-issuance nebyla dosažitelná — selhává uzavřeně, stejně jako autorizace) je **503**; všechny ostatní důvody jsou **409**.

- **Token:** `CARD_NOT_FOUND` (card-issuance kartu nezná), `CARD_NOT_ACTIVE` (blokovaná, pozastavená, expirovaná, zrušená nebo s neuvedeným stavem — nikdy se netokenizuje), `ISSUER_UNAVAILABLE`, `TOKEN_NOT_FOUND`, `TOKEN_TERMINAL`, `SCHEME_UNAVAILABLE` (zahrnuje `NOT_BOUND`), `SCHEME_REFUSED`.
- **Reklamace:** `AUTHORIZATION_NOT_FOUND`, `NO_NETWORK_REFERENCE`, `NOTHING_CLEARED`, `CURRENCY_MISMATCH` (měna reklamace není měnou autorizace; porovnává se jako Money, nikdy se nepřepočítává — neznámý kód je 400), `AMOUNT_EXCEEDS_CLEARED`, `ALREADY_DISPUTED`, `CASE_NOT_FOUND`, `CASE_TERMINAL`, `SCHEME_UNAVAILABLE`, `SCHEME_REFUSED`.

Idempotence: vydání tokenu, změna stavu tokenu, otevření reklamace, podání důkazu a refresh reklamace **rezervují** `Idempotency-Key` v databázi (`card_lifecycle_idempotency`) ještě před voláním sítě. Ze dvou souběžných požadavků se stejným klíčem se k síti dostane právě jeden; opakování po jeho dokončení přehraje první výsledek, souběžný požadavek dostane **409 `IDEMPOTENCY_REQUEST_IN_PROGRESS`** a stejný klíč s jiným tělem je **409 `IDEMPOTENCY_KEY_REUSED`** (flotilový tvar chyby). Odmítnutí klíč uvolní. Klíč má 1–128 znaků z `[A-Za-z0-9._:-]` (jinak 400). Přehraná změna stavu vrátí token v aktuálním stavu; přehraný refresh vrátí případ, který vytvořil první refresh — u uzavřeného případu uložený výsledek — aniž by se znovu ptal sítě.

OPA politika uděluje `cardprocessing.token` a `cardprocessing.dispute` HUMAN principálům s `ROLE_OPERATOR` nebo `ROLE_ADMIN` (důvod `operator-card-lifecycle-write`); kontrola rolí na resource navíc připouští `ROLE_API`. Dokud `AUTHZ_ENFORCE=false`, účinnou kontrolou je kontrola rolí.

## Události

Publikované na `openbank.card.processing.events`, popsané v `openbank-contracts/openbank-card-processing-service/asyncapi.yaml`:

| Typ události | Kdy | Významná pole |
|---|---|---|
| `card.authorised.v1` | schválení | částka, měna, kanál, mcc, kategorie, obchodník, `expiresAt` |
| `card.declined.v1` | zamítnutí | `reason` (důvod zamítnutí z card-issuance, doslovně) |
| `card.cleared.v1` | přijatý clearing | `clearedAmountMinorUnits`, `cumulativeClearedMinorUnits`, `fullyCleared` |
| `card.hold_released.v1` | reverzace nebo expirace | `releasedAmountMinorUnits`, `releaseKind` (`REVERSAL` / `EXPIRY`) |

| `card.token.provisioned.v1` | token vydán | `registrationId`, `tokenReference`, `requestorId`, `requestorLabel`, `scheme`, `status`, `expiry` |
| `card.token.status_changed.v1` | změna stavu tokenu | `previousStatus`, `status`, `scheme` |
| `card.dispute.opened.v1` | případ otevřen | `disputeId`, `authorizationId`, `networkCaseId`, `reasonCode`, částka, `respondByDate`, `scheme` |
| `card.dispute.evidence_submitted.v1` | podány důkazy | `disputeId`, `networkCaseId`, `documentReference` |
| `card.dispute.status_changed.v1` | refresh změnil stav | `previousStatus`, `status`, `schemeStatus` |

Všechny nesou `cardId`, `occurredAt` a `sourceService = card-processing-service`; události autorizací a reklamací nesou i `authorizationId`.
