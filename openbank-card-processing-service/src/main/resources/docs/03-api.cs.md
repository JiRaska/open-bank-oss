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
| POST | `/api/v1/sandbox/acquirer/purchase` | `ROLE_ADMIN` | `cardprocessing.simulate` | **404, pokud není** `openbank.card-processing.sandbox-acquirer-enabled=true` |

## Požadavek na autorizaci

Pole: `cardId`, `amountMinorUnits`, `currencyCode`, `channel` (`CONTACTLESS`, `ONLINE`, `ATM`, `CHIP_AND_PIN`), volitelně `mcc`, `merchantName`, `merchantCountry`, `networkReference`. PAN ani kartové údaje se nepřijímají.

Zamítnutí je vytvořený záznam rozhodnutí, proto je to **201**, ne 4xx; odpověď nese `status: DECLINED` a `declineReason` z card-issuance.

## Idempotence

- **Autorizace:** `Idempotency-Key` se ukládá na řádek pod UNIQUE indexem; opakovaný klíč vrátí první autorizaci beze změny a druhý hold nevznikne.
- **Clearing:** klíč je povinný a započte se **nejvýše jednou na autorizaci**. Každý započtený clearing se zapíše do `card_clearings` pod UNIQUE omezením `(authorization_id, idempotency_key)`, ve stejné transakci jako snížení holdu a událost `card.cleared.v1`. Opakování se stejnou částkou a měnou (měna bez ohledu na velikost písmen) vrátí aktuální stav autorizace s **200** — žádné druhé snížení holdu, žádná druhá událost, žádné druhé zaúčtování. Stejný klíč s jinou částkou nebo měnou je **409 `IDEMPOTENCY_KEY_REUSED`** (tělo libs `ApiError`). Dva souběžné duplikáty se nemohou započíst oba: insert poraženého selže na omezení, jeho transakce se celá vrátí a vrátí výsledek vítěze. Odmítnutá prezentace nic nezapisuje, takže její opakování se vyhodnotí znovu. Souběžné clearingy s **různými** klíči na jedné autorizaci serializuje optimistický zámek: poražený se jednou znovu vyhodnotí proti skutečnému zbývajícímu holdu (započte se, nebo 409 `EXCEEDS_AUTHORIZED_AMOUNT`); prohraje-li podruhé, odpoví **409 `IDEMPOTENCY_REQUEST_IN_PROGRESS`** — opakujte později. Zaúčtování má v transaction-service klíč `card-clearing:<idAutorizace>:<klíč>` (omezený na autorizaci; klíč, který by přesáhl 100znakový sloupec transaction-service, se nahradí `sha256-` + prvními 32 hex znaky jeho SHA-256).
- **Sandbox nákup:** `idempotencyKey` v těle slouží jako klíč autorizace i jako network reference; klíč clearingu je `<klíč>:clearing`.

## Model chyb a odmítnutí

| Situace | Status |
|---|---|
| Chybějící nebo prázdný `Idempotency-Key`, nekladná částka, měna ≠ měna karty | 400 (`IllegalArgumentException` mapuje libs-runtime) |
| Karta neznámá pro card-issuance | 404 |
| Clearing/reverzace odmítnuta životním cyklem | **409** s `{ reason, message }` |
| Klíč clearingu znovu použit s jinou částkou nebo měnou | **409** s libs `ApiError`, `code: IDEMPOTENCY_KEY_REUSED` |
| Clearing dvakrát prohrál souběh s jiným clearingem | **409** s libs `ApiError`, `code: IDEMPOTENCY_REQUEST_IN_PROGRESS` (opakujte později) |

`reason` je jedna z hodnot `NOT_HOLDING_FUNDS` (koncový stav nebo neznámé id autorizace), `AMOUNT_NOT_POSITIVE`, `EXCEEDS_AUTHORIZED_AMOUNT`, `CURRENCY_MISMATCH`, `NOT_YET_EXPIRED` (jen expirace, interní).

## Události

Publikované na `openbank.card.processing.events`, popsané v `openbank-contracts/openbank-card-processing-service/asyncapi.yaml`:

| Typ události | Kdy | Významná pole |
|---|---|---|
| `card.authorised.v1` | schválení | částka, měna, kanál, mcc, kategorie, obchodník, `expiresAt` |
| `card.declined.v1` | zamítnutí | `reason` (důvod zamítnutí z card-issuance, doslovně) |
| `card.cleared.v1` | přijatý clearing | `clearedAmountMinorUnits`, `cumulativeClearedMinorUnits`, `fullyCleared` |
| `card.hold_released.v1` | reverzace nebo expirace | `releasedAmountMinorUnits`, `releaseKind` (`REVERSAL` / `EXPIRY`) |

Všechny nesou `authorizationId`, `cardId`, `occurredAt` a `sourceService = card-processing-service`.
