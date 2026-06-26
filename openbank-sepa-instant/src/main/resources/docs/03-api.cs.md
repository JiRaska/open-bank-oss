# API

REST kontrakt služby `openbank-sepa-instant`. Formální kontrakt žije v [`openapi.yaml`](../openapi.yaml) (`info.version 1.1.0`, OpenAPI 3.1.0). Major verze v URL je `/api/v{N}` s `openbank.api.version = "1"` (ADR-0048). Swagger UI je na `/api/docs`.

> Poznámka: zdrojový `openapi.yaml` a nasazený `SctInstResource` mírně driftují (viz poznámka "Drift" na konci). Endpointy níže jsou dokumentovány z **reálného kódu resource**, který je autoritativní pro chování.

## Základní cesta

`/api/v1/sepa-instant`

## Endpointy

### `POST /api/v1/sepa-instant` — Submit platby

Odešle SCT Inst platbu. Před uvolněním synchronně prověří jména plátce a příjemce (ADR-0032).

- **Hlavičky:** `Idempotency-Key` (volitelná; fallback na pole `idempotencyKey` v těle).
- **Tělo požadavku** (`SubmitSctInstRequest`):

  | Pole | Typ | Poznámka |
  |---|---|---|
  | `idempotencyKey` | string | povinné (hlavička nebo tělo) |
  | `debtorAccountId` | uuid | povinné |
  | `debtorIban` | string | povinné |
  | `debtorName` | string | povinné — prověřováno |
  | `creditorIban` | string | povinné |
  | `creditorName` | string | povinné — prověřováno |
  | `creditorBic` | string? | volitelné |
  | `amount` | number | povinné |
  | `currency` | string | výchozí `EUR` |
  | `remittanceInfo` | string? | volitelné |
  | `endToEndId` | string | povinné |

- **Odpověď:** `201 Created` s `SctInstPaymentResponse`. `status` odráží výsledek prověrky: `PROCESSING` (CLEAR), `PENDING` (REVIEW / výpadek prověrky) nebo `REJECTED` (BLOCK / sankční zásah).

### `GET /api/v1/sepa-instant` — Seznam všech plateb

Vrací všechny platby jako `SctInstPaymentResponse[]`. `200 OK`.

### `GET /api/v1/sepa-instant/{paymentId}` — Detail podle id

`paymentId` je UUID. `200 OK` s `SctInstPaymentResponse`, nebo `404` při nenalezení.

### `GET /api/v1/sepa-instant/debtor/{debtorAccountId}` — Seznam podle plátce

Query parametry: `page` (výchozí `0`), `size` (výchozí `20`). Vrací `SctInstPaymentResponse[]`. `200 OK`.

### `POST /api/v1/sepa-instant/{paymentId}/recall` — Recall

Vrátí **SETTLED** platbu. Chráněno `@Authorize(action = "sctInstPayment.recall", resource = "#paymentId")` (ADR-0034 OPA).

- **Tělo požadavku** (`RecallRequest`): `{ "reason": "FRAUD" | "DUPLICATE" | "WRONG_AMOUNT" | "WRONG_BENEFICIARY" }`.
- **Odpověď:** `200 OK` s aktualizovaným `SctInstPaymentResponse` (`status = RECALLED`), vydá `SctInstPaymentRecalled`.
- **Chyby:** `400`, není-li platba ve stavu `SETTLED`; `404` při nenalezení.

## Tvar odpovědi — `SctInstPaymentResponse`

| Pole | Typ |
|---|---|
| `paymentId` | uuid |
| `status` | string (`PENDING`/`PROCESSING`/`SETTLED`/`REJECTED`/`TIMEOUT`/`RECALLED`) |
| `debtorIban` | string |
| `creditorIban` | string |
| `amount` | number |
| `currency` | string |
| `endToEndId` | string |
| `executionTimeoutAt` | date-time? |
| `settledAt` | date-time? |
| `createdAt` | date-time |

## Idempotence

`Idempotency-Key` (hlavička, nebo `idempotencyKey` v těle) je vynucen **unique constraintem** na `sct_inst_payments.idempotency_key`. Při opakovaném submitu se stejným klíčem služba vrátí již uloženou platbu beze změny — žádná druhá prověrka, žádná duplicitní událost.

## Model chyb

Chyby se vrací jako malý JSON objekt přes exception mappery:

```json
{ "error": "Only SETTLED payments can be recalled" }
```

- `404 Not Found` — neznámé `paymentId` (`NotFoundMapper`).
- `400 Bad Request` — neplatný přechod stavu, např. recall nezúčtované platby (`BadRequestMapper`).

(`openapi.yaml` deklaruje schéma `ApiError { code, message }`; nasazené mappery vydávají `{ error }`. Za autoritativní berte chování resource.)

## Verzování

- **Verze URL/API kontraktu:** `/api/v1`, `openapi.yaml info.version = 1.1.0` (osa API kontraktu, ADR-0048).
- **Release verze:** `version.txt = 0.2.0` (nezávislá release osa, vlastní release-please).
- Hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info` obsluhuje `openbank-libs`.

## Poznámka k driftu

Zacommitovaný `openapi.yaml` uvádí tělo submitu bez `idempotencyKey`/`debtorIban`/`debtorName`, používá jiný enum stavů (`SUBMITTED/ACCEPTED/SETTLED/REJECTED/RECALLED`), deklaruje stránkování seznamu plátce jako `limit`/`offset` a lokální server na portu `8111`. Běžící služba používá `page`/`size`, enum `PENDING/PROCESSING/SETTLED/REJECTED/TIMEOUT/RECALLED` a port `8127`. Sjednocení kontraktu s implementací je TBD follow-up.
