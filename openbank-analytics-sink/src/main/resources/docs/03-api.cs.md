# API

> **Stav kontraktu:** v této službě zatím **není `openapi.yaml`** — REST kontrakt není formálně zafixován (služba buildí `quarkus-smallrye-openapi`, takže generovaná specifikace je servírována za běhu přes Swagger UI na `/api/docs`, ale žádný verzovaný `openapi.yaml` neexistuje). Endpointy níže jsou dokumentovány **přímo z JAX-RS resource tříd** pod `infrastructure/rest`. Formalizace kontraktu do verzovaného `openapi.yaml` + contract testu je follow-up (viz [05 — Provoz](./05-operations.md)).

Tato služba nevystavuje **žádné veřejné/zákaznické API**. REST povrch je pouze **operátorský/auditní/compliance** povrch a **není** v žádné platební cestě.

- **Base path / verzování:** `/api/v1/...` — major verze API `1` (`openbank.api.version=1`). Hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info` servíruje `openbank-libs`.
- **App port:** 8134. **Management port:** 8086 (`/q`).
- **Content type:** `application/json`.
- **Auth:** Keycloak OIDC (RS256 bearer). Každé sloveso je zabezpečeno rolí; **žádné `@PermitAll` mutace** (pravidlo K7 audit-trail).

## Rekonciliace — `/api/v1/analytics/reconciliation`

Role: `ROLE_AUDITOR`, `ROLE_ADMIN`, `ROLE_COMPLIANCE`.

| Metoda | Path | Popis |
|---|---|---|
| `POST` | `/run` | Spustí rekonciliační běh (`source="manual"`). Read-only porovnání sklad vs zdroj pravdy per-agregát `max(version)`; zaznamená `ReconciliationResult` (běží i na cronu, výchozí `0 30 2 * * ?`). |
| `GET` | `/last` | Poslední rekonciliační evidence. `204 No Content`, pokud zatím žádná. |

## Backfill / recovery loady — `/api/v1/analytics/backfill`

Role: `ROLE_ADMIN`, `ROLE_AUDITOR`. **Čtyři oči (maker-checker)** — navrhovatel a schvalovatel se musí lišit.

| Metoda | Path | Popis |
|---|---|---|
| `POST` | `/proposals` | Krok 1 — navrhni reload. Tělo `ReloadProposalDto { kind, from?, to?, aggregateType?, aggregateId?, reason }`. `kind` ∈ `BACKFILL` / `CORRECTION` / `INITIAL_LOAD` (`STREAM` odmítnut jako živá cesta). Vrací id `PROPOSED` návrhu. |
| `POST` | `/proposals/{id}/approve` | Krok 2 — **jiný** operátor schválí. Samoschválení ⇒ `409 Conflict` (`MakerCheckerViolation`). |
| `POST` | `/proposals/{id}/reject` | Zamítne čekající návrh. |
| `POST` | `/proposals/{id}/execute` | Krok 3 — provede `APPROVED` návrh; spustí reload a zapíše řádek `backfill_audit`, pak označí `EXECUTED`. |
| `GET` | `/proposals` | Seznam všech návrhů. |
| `GET` | `/proposals/{id}` | Jeden návrh (`404` při neexistenci). |
| `GET` | `/last` | Poslední skutečně provedený backfill report (`204`, pokud žádný). |

## GDPR výmaz — `/api/v1/analytics/erasure`

Role: `ROLE_COMPLIANCE`, `ROLE_ADMIN`.

| Metoda | Path | Popis |
|---|---|---|
| `POST` | `/` | GDPR Art. 17 výmaz vůči analytické vrstvě. Tělo `ErasureRequestDto { aggregateType, aggregateId }`. Vrací `ErasureDecision`: buď crypto-shred (`erased=true`), nebo **odmítnutí** pod zákonným hold (Art. 17(3)(b)) s auditovatelným `legalBasis`/`explanation`. |

## Chybový model

| Status | Význam |
|---|---|
| `400 Bad Request` | Neznámý `kind` reloadu, chybějící povinné `from`, nebo `STREAM` použit jako kind reloadu. |
| `401 / 403` | Chybějící/neplatný bearer token, nebo role nepovolená pro sloveso. |
| `404 Not Found` | Id návrhu nenalezeno. |
| `409 Conflict` | Maker-checker porušení (samoschválení / nelegální přechod návrhu), přes `MakerCheckerExceptionMapper`. |
| `204 No Content` | Čtení `/last`, když ještě žádný výsledek neexistuje. |

## Idempotence / doručení

- **Příjem** (Kafka, ne REST) je at-least-once; `eventId` je dedupe klíč a ClickHouse `ReplacingMergeTree` slučuje duplicity. Žádná hlavička `Idempotency-Key` zde není (na rozdíl od money-path služeb) — operátorská slovesa jsou buď přirozeně idempotentní čtení, nebo jsou chráněna stavovým automatem maker-checker.
