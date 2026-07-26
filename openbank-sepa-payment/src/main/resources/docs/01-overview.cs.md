# Přehled

## Co služba dělá

`openbank-sepa-payment` je **vlastník životního cyklu SEPA úhrad** (SCT, plus typový marker SCT_INST) na platformě OpenBank. Drží:

- **Agregát SepaPayment** — id platby, idempotenční klíč, typ (`SCT` / `SCT_INST`), stav, plátce (id účtu, IBAN, jméno), příjemce (IBAN, jméno, BIC), částku + měnu, informaci o úhradě, end-to-end id, důvod/detail zamítnutí a compliance pole (purpose code, charge bearer, SCA reference, consent id, AML screening příznaky).
- **Stavový životní cyklus** — řízený stavový automat `RECEIVED → VALIDATED → PROCESSING → COMPLETED` s terminálními větvemi `REJECTED` / `RETURNED` / `CANCELLED`. Neplatné přechody doménový model odmítá.
- **Synchronní screening gate** (ADR-0032) — při create jsou jména plátce i příjemce prověřena proti sankčním seznamům; čistá platba se stane `VALIDATED`, zásah je `REJECTED` (`SANCTIONS_HIT`) s otevřeným AML případem a podprahový potenciální zásah či výpadek screening služby drží platbu v `RECEIVED` k lidskému přezkoumání (fail-closed).

## Co služba **NEDĚLÁ**

- Neřeší časování okamžitého settlementu (SCT Inst) — to je `openbank-sepa-instant`.
- Nezpracovává tuzemské (ne-SEPA) převody — `openbank-domestic-payment`.
- Platbu neclearuje/nesettluje — události konzumuje `openbank-clearing-service`.
- Neúčtuje podvojné zápisy — `openbank-ledger-service`.
- Nepočítá ani nedrží zůstatky — `openbank-balance-service`.
- Neudržuje sankční seznamy ani AML případy — **volá** `openbank-sanctions-service` (screen) a `openbank-aml-service` (open case).
- Sama neprovádí SCA — reference na SCA evidenci (`sca_reference`); decoupled approval flow žije v `openbank-sca-service` (ADR-0021).

## Pozice v doméně

```
   ┌────────────┐   POST /sepa-payments   ┌────────────────────┐  screen (sync)  ┌───────────────────┐
   │  admin UI  │ ──────────────────────► │  sepa-payment      │ ──────────────► │ sanctions-service │
   │  kanály    │                         │  service           │                 └───────────────────┘
   └────────────┘                         │                    │  open case      ┌───────────────────┐
                                          │                    │ ──────────────► │   aml-service     │
                                          └──────┬─────────────┘                 └───────────────────┘
                                                 │ outbox → Kafka
                                                 ▼  (openbank.sepa.payment.events)
                                          ┌──────────────────────────────────────┐
                                          │ clearing-service / ledger-service     │
                                          │ audit-service / notification          │
                                          └──────────────────────────────────────┘
                                                 │
                                                 ▼
                                          PostgreSQL (openbank_sepa_payments)
```

## Klíčové use casy

| Use case | API | Událost |
|---|---|---|
| Vytvoření SEPA platby (se synchronním screeningem) | `POST /api/v1/sepa-payments` | `sepa.payment.created` (+ `sepa.payment.status-changed` dle verdiktu screeningu) |
| Získání platby podle id | `GET /api/v1/sepa-payments/{paymentId}` | — |
| Výpis plateb (filtr dle stavu / účtu plátce) | `GET /api/v1/sepa-payments` | — |
| Přechod stavu platby (provoz/clearing) | `PATCH /api/v1/sepa-payments/{paymentId}/status` | `sepa.payment.status-changed` |

## Volající

- **admin-ui / platbu iniciující kanály** (přes Keycloak token) — operátoři s `ROLE_PAYMENTS` / `ROLE_OPERATOR` iniciují převody.
- **clearing / ledger pipeline** — konzumují emitované Kafka události (ne přímí REST volající).
- **provoz / clearing back-office** — řídí stavové přechody (`PROCESSING` → `COMPLETED` / `RETURNED`).
- **service volající** — `ROLE_API` smí vypisovat platby.

## Závislosti

- **PostgreSQL** (databáze `openbank_sepa_payments`, deklarované schéma `sepa_schema`)
- **Kafka** (topic `openbank.sepa.payment.events`)
- **Redis (Valkey)** — idempotenční cache (`libs.idempotency.IdempotencyStore`)
- **Keycloak** — OIDC auth
- **sanctions-service** (REST, synchronní `POST /api/v1/sanctions/screen`) — screening gate, **fail-closed**
- **aml-service** (REST, idempotentní `POST /api/v1/aml/cases`) — best-effort otevření případu při zásahu/zadržení
- **OPA sidecar** (ADR-0034) — autorizace, defaultně advisory (`AUTHZ_ENFORCE`)
- **openbank-libs** — IdempotencyStore, `@Authorize`, ApiError/ErrorCode, ServiceInfoResource, DocsResource, BuildInfo

## Obchodní hodnota

- **Jediný vlastník životního cyklu SCT** — jeden konzistentní stavový automat pro každou SEPA úhradu, s auditovatelnými přechody.
- **Screening před uvolněním** — sankční/AML screening je vynucen **dříve**, než platba opustí `RECEIVED`, takže žádná hodnotu nesoucí instrukce není uvolněna neprověřená (ADR-0032, fail-closed).
- **Trvalá, at-least-once propagace** — transakční outbox zaručuje, že každá přijatá platba a každá změna stavu je publikována do clearing/ledger/audit, i přes pády.
- **Idempotentní iniciace** — opakovaný create se stejným `Idempotency-Key` nikdy nevytvoří duplicitní převod.
