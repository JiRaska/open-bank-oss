# API

REST kontrakt je definován v [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version 1.0.0`). Všechny cesty jsou pod `/api/v1/clearing` — URL major verze (`v1`) sleduje major verzi OpenAPI kontraktu (ADR-0048). Media type je `application/json`. Autentizace je Keycloak bearer JWT (`bearerAuth`).

> **Pozn.:** blok `servers` v `openapi.yaml` uvádí `http://localhost:8114`, ale běžící aplikační HTTP port je **8124** (`application.yaml: quarkus.http.port`). Pro lokální běh berte jako autoritativní 8124; server URL v kontraktu je známý nesoulad.

## Endpointy

| Metoda | Cesta | Role (`@RolesAllowed`) | Účel |
|---|---|---|---|
| `POST` | `/api/v1/clearing/submit` | `SERVICE`, `PAYMENTS`, `ADMIN` | Předání platby ke clearingu → `201 Created` s novým `ClearingItem` |
| `GET` | `/api/v1/clearing/batches?status=&page=&size=` | `SERVICE`, `VIEWER`, `OPERATOR`, `PAYMENTS`, `ADMIN` | Výpis clearingových dávek (volitelný `status`, stránkováno) |
| `GET` | `/api/v1/clearing/batches/{id}` | `SERVICE`, `VIEWER`, `OPERATOR`, `PAYMENTS`, `ADMIN` | Dávka podle id (`404`, pokud chybí) |
| `GET` | `/api/v1/clearing/batches/{id}/items` | `SERVICE`, `VIEWER`, `OPERATOR`, `PAYMENTS`, `ADMIN` | Výpis položek v dávce |
| `POST` | `/api/v1/clearing/batches/{id}/settle` | `PAYMENTS`, `ADMIN` + `@Authorize(clearingBatch.settle)` | Zúčtování dávky → status SETTLED, emituje batch-settled |
| `POST` | `/api/v1/clearing/cycle/trigger?rail=SEPA_SCT` | `PAYMENTS`, `ADMIN` | Spuštění zúčtovacího cyklu pro rail |
| `GET` | `/api/v1/clearing/positions/{cycleId}` | `SERVICE`, `VIEWER`, `OPERATOR`, `PAYMENTS`, `ADMIN` | Pozice zúčtování pro cyklus |
| `GET` | `/api/v1/clearing/items/{id}` | `SERVICE`, `VIEWER`, `OPERATOR`, `PAYMENTS`, `ADMIN` | Clearingová položka podle id (`404`, pokud chybí) |
| `GET` | `/api/v1/clearing/items/by-payment/{paymentId}` | `SERVICE`, `VIEWER`, `OPERATOR`, `PAYMENTS`, `ADMIN` | Clearingové položky pro platbu |

Hodnoty `rail` (query / enum): `SEPA_SCT`, `SEPA_SCT_INST`, `SWIFT`, `DOMESTIC`, `INTERNAL` (OpenAPI enum `SubmitPaymentRequest.rail` uvádí `SEPA_SCT`, `SEPA_SCT_INST`, `CZ_DOMESTIC`, `SWIFT`; doménový enum používá `DOMESTIC`/`INTERNAL` — pozor na nesoulad pojmenování `CZ_DOMESTIC` vs `DOMESTIC` mezi kontraktem a kódem).

## Tělo submit požadavku

`SubmitPaymentRequest` (`POST /clearing/submit`):

| Pole | Typ | Povinné | Pozn. |
|---|---|---|---|
| `paymentId` | UUID | ano | id upstream platby |
| `paymentReference` | string | ano (kód) | reference (`VARCHAR(64)`) |
| `debtorIban` | string | ano (kód) | až 34 znaků |
| `creditorIban` | string | ano (kód) | až 34 znaků |
| `debtorBic` / `creditorBic` | string | ne | až 11 znaků |
| `amount` | number (BigDecimal) | ano | musí být `> 0` (DB CHECK) |
| `currency` | string (CHAR(3)) | ne | výchozí `EUR` |
| `rail` | enum | ne | výchozí `SEPA_SCT` |
| `valueDate` | date | ne | výchozí dnešek, pokud chybí |
| `endToEndId` | string | ne | až 35 znaků |
| `remittanceInfo` | string | ne | až 140 znaků |

(OpenAPI schéma označuje za povinné `paymentId, rail, amount, currency`; Kotlin `SubmitPaymentRequest` navíc vyžaduje reference a IBANy jako non-null. Kontrakt **zatím není plně formalizován** — request/response schémata v `openapi.yaml` jsou minimální a řada odpovědí dokumentuje jen `200`.)

## Idempotence

`Idempotency-Key` je nakonfigurován jako povolená request hlavička (CORS + `quarkus.http.cors.headers`) a Redis (Valkey) je zapojen jako závislost dle platformového vzoru idempotence. Handlery submit/settle v aktuálním kódu neukazují explicitní guard přes idempotency-store — end-to-end vynucení idempotence berte jako **částečné / TBD** a prozatím se spoléhejte na idempotenci upstream platební služby.

## Chybový model

Resource vrací reaktivní `Uni<Response>`:

- `201 Created` — úspěšný `submit`.
- `200 OK` — úspěšná čtení, `settle`, `cycle/trigger`.
- `404 Not Found` — `getBatch` / `getItem`, když id neexistuje.
- `500` s tělem `{ "error": "<zpráva>" }` — selhání `submit`, `settle`, `triggerCycle` jsou převedena na server-error odpověď nesoucí zprávu výjimky (`onFailure().recoverWithItem`). Typovaný RFC-7807 problem+json model zde **zatím** není.

## Verzování

- **Verze API kontraktu:** `openapi.yaml: info.version = 1.0.0`; URL major `/api/v1` == major kontraktu (ADR-0048).
- **Release verze:** `version.txt = 0.2.0` (nezávislá osa, vlastněná release-please).
- Hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info` obsluhuje `openbank-libs`.
