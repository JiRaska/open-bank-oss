# Přehled

## Co služba dělá

`openbank-domestic-payment` je **vlastník iniciace a životního cyklu tuzemských plateb v ČR**. Konkrétně:

- **Přijímá tuzemskou platební instrukci** — účet plátce (id + číslo účtu + kód banky), číslo účtu + kód banky příjemce, částka, měna a české platební symboly (variabilní / specifický / konstantní), volitelná zpráva pro příjemce, priorita (STANDARD / URGENT), rozsah převodu (OWN_ACCOUNTS / INTERNAL_CLIENT / TECHNICAL_ACCOUNT) a end-to-end id.
- **Synchronně screenuje platbu** proti sankčním seznamům při založení (ADR-0032): kontroluje se jméno plátce i příjemce. Čistá platba je uvolněna do `VALIDATED`, sankční zásah je `REJECTED` s důvodem `SANCTIONS_HIT` a otevřením AML případu, a podprahový potenciální zásah nebo výpadek screeningové služby drží platbu v `RECEIVED` k ruční kontrole (fail-closed).
- **Řídí stavový automat platby** — `RECEIVED → VALIDATED → SENT_TO_CLEARING → SETTLED`, s terminálními větvemi `REJECTED` / `RETURNED` / `CANCELLED` — a při každém přechodu emituje doménovou událost.
- **Publikuje doménové události** přes transakční outbox, aby navazující clearing/ledger/audit konzumenti viděli životní cyklus platby.

## Co služba **NEDĚLÁ**

- Neprovádí clearing ani zúčtování — mezibankovní clearing dělá `clearing-service`; tato služba jen značí `SENT_TO_CLEARING` / `SETTLED`.
- Neúčtuje podvojně — to dělá `ledger-service`.
- Neřeší SEPA ani přeshraniční platby — to dělají `sepa-payment` / `sepa-instant` / `swift-service`.
- Není sankční/AML autorita — volá `sanctions-service` pro screeningové rozhodnutí a `aml-service` pro otevření případu; data rozhodovací politiky žijí tam.
- Neprovádí SCA — silné ověření zákazníka se očekává výše v řetězci u zákaznicky iniciovaných plateb (PSD2 RTS; zaznamenává se `sca_reference`).

## Pozice v doméně

```
   ┌────────────┐  POST /domestic-payments   ┌─────────────────────┐
   │ kanály /   │ ─────────────────────────► │ sanctions-service   │
   │ operátoři  │                            │ (sync screen)       │
   └─────┬──────┘                            └─────────────────────┘
         │ POST /api/v1/domestic-payments          ▲   │ HIT/POTENTIAL
         ▼                                          │   ▼
   ┌──────────────────────────┐  AML případ ┌─────────────────────┐
   │ domestic-payment-service │ ──────────► │ aml-service         │
   └────┬─────────────────────┘             └─────────────────────┘
        │ outbox → Kafka
        │ (openbank.domestic.payment.events)
        ▼
   ┌─────────────────┐   ┌──────────────────────────────────┐
   │ PostgreSQL      │   │ clearing-service / ledger-service │
   │ (domestic DB)   │   │ audit-service / notification      │
   └─────────────────┘   └──────────────────────────────────┘
```

## Klíčové případy užití

| Případ užití | API | Událost |
|---|---|---|
| Založit tuzemskou platbu (screening při založení) | `POST /api/v1/domestic-payments` | `domestic.payment.created` |
| Získat platbu podle id | `GET /api/v1/domestic-payments/{paymentId}` | — |
| Vypsat platby (podle stavu / účtu plátce) | `GET /api/v1/domestic-payments` | — |
| Přechod stavu platby (validace / odeslání / zúčtování / zamítnutí / storno) | `PATCH /api/v1/domestic-payments/{paymentId}/status` | `domestic.payment.status-changed` |

## Volající

- **platební kanály / operátoři** (přes Keycloak token, role `ROLE_OPERATOR` / `ROLE_ADMIN` / `ROLE_PAYMENTS`) — zakládají a řídí platby.
- **admin-ui / compliance ops** — čtou detail a seznamy plateb, ruční přechody stavu, kontrola držených plateb.
- **další služby** (`ROLE_API`) — read-only přístup k výpisu.

## Závislosti

- **PostgreSQL** (databáze `openbank_domestic_payments`)
- **Kafka** (topic `openbank.domestic.payment.events`)
- **Redis (Valkey)** — stav four-eyes schvalování; idempotence založení platby je trvale v PostgreSQL
- **Keycloak** — OIDC autentizace
- **sanctions-service** (REST klient `sanctions-service`) — synchronní screening jmen (ADR-0032)
- **aml-service** (REST klient `aml-service`) — otevření AML případu při zásahu / kontrole / výpadku screeningu
- **OPA sidecar** (ADR-0034) — `@Authorize` advisory authz
- **openbank-libs** — approval store, outbox plumbing, `ApiError`/`ErrorCode`, `@Authorize`, `ServiceInfoResource`, `DocsResource`

## Obchodní hodnota

- **Sankčně bezpečné z principu** — žádná tuzemská platba není uvolněna bez průchodu synchronní screeningovou bránou; výpadky selhávají uzavřeně (fail-closed), nikoliv propuštěním platby.
- **Auditovatelný životní cyklus** — každá změna stavu emituje doménovou událost, kterou perzistuje `audit-service`, čímž plní požadavky na nepopiratelnost a důkazy o incidentu.
- **Transakční konzistence** — řádek platby a její outbox událost se zapisují v jedné DB transakci, takže navazující clearing/ledger nikdy nevidí platbu, kterou databáze nemá.
- **Nativní pro CZ rails** — prvotřídní podpora českých platebních symbolů, kódů bank a ČNB reportingových polí.
