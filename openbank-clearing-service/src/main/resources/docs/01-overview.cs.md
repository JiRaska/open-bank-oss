# Přehled

## Co služba dělá

`openbank-clearing-service` je **engine pro clearing a zúčtování (settlement)** platformy OpenBank. Stojí *za* iniciací plateb a *před* (nebo souběžně s) účtováním v ledgeru a zodpovídá za seskupování plateb do zúčtovacích dávek a jejich provedení zúčtovacím cyklem. Drží tři agregáty:

- **ClearingItem** — jednotlivá platba předaná ke clearingu: `paymentId`, `paymentReference`, IBAN dlužníka/věřitele + volitelné BIC, `amount`, `currency`, `rail`, `valueDate`, `endToEndId`, `remittanceInfo` a `status` (PENDING / IN_CLEARING / SETTLED / FAILED / REVERSED).
- **ClearingBatch** — zúčtovací cyklus pro daný platební rail: `batchReference`, `rail`, `settlementType` (GROSS / NET / DEFERRED_NET, výchozí NET), souhrnné `totalDebit` / `totalCredit` / `netPosition`, `currency`, `itemCount`, `cycleId`, `settlementDate`, `settledAt` a `status`.
- **SettlementPosition** — čistá pozice za účastníka v rámci cyklu: `participantBic`, `currency`, `cycleId`, `grossDebit`, `grossCredit`, `netPosition`, příznak `settled`.

Podporované platební raily: `SEPA_SCT`, `SEPA_SCT_INST`, `SWIFT`, `DOMESTIC`, `INTERNAL`.

## Co služba **NEDĚLÁ**

- ❌ Neiniciuje platby — `sepa-payment`, `sepa-instant`, `domestic-payment`, `swift-service` platbu vytvoří a poté ji sem předají.
- ❌ Nevede zůstatky účtů — autoritativní je `balance-service`.
- ❌ Neúčtuje podvojné zápisy — hlavní knihu (GL) vlastní `ledger-service`; výsledky zúčtování putují do `transaction-service` (deklarovaný downstream, vztah `api`, role „settles").
- ❌ Neprovádí sanctions/AML screening — to je vynucováno výše na platebních površích (gate ADR-0032).
- ❌ Neověřuje vlastnictví IBAN ani nedrží prostředky — clearing pracuje s již autorizovanými platebními instrukcemi.

## Pozice v doméně

```
   ┌──────────────────┐  POST /clearing/submit   ┌────────────────────┐
   │ platební služby  │ ───────────────────────► │ clearing-service   │
   │ (sct/inst/dom/   │                          │  batches / items / │
   │  swift)          │                          │  positions         │
   └──────────────────┘                          └─────────┬──────────┘
                                                            │
   ┌──────────────────┐  cycle/trigger, settle             │ outbox → Kafka
   │ operátor / ops   │ ──────────────────────────────────►│ openbank.clearing.batch.event
   └──────────────────┘                                     ▼
                                                  ┌────────────────────┐
                                                  │ transaction-service│  (settles)
                                                  │ audit / downstream │
                                                  └────────────────────┘
                                                            │
                                                            ▼
                                                      PostgreSQL
                                                  (db: openbank_clearing)
```

## Klíčové případy užití

| Případ užití | API | Událost / efekt |
|---|---|---|
| Předání platby ke clearingu | `POST /api/v1/clearing/submit` | nový `ClearingItem` (status PENDING), placeholder batch id do cyklu |
| Spuštění zúčtovacího cyklu pro rail | `POST /api/v1/clearing/cycle/trigger?rail=…` | vytvoří `ClearingBatch`, připne pending položky, status IN_CLEARING (nebo SETTLED, pokud prázdný) |
| Zúčtování dávky | `POST /api/v1/clearing/batches/{id}/settle` | status dávky → SETTLED, událost `publishBatchSettled` |
| Výpis / detail dávek | `GET /api/v1/clearing/batches`, `…/{id}`, `…/{id}/items` | čtení |
| Pozice zúčtování pro cyklus | `GET /api/v1/clearing/positions/{cycleId}` | čtení |
| Vyhledání clearingové položky | `GET /api/v1/clearing/items/{id}`, `…/by-payment/{paymentId}` | čtení |

## Volající

- **platební služby** (`sepa-payment`, `sepa-instant`, `domestic-payment`, `swift-service`) — předávají platby ke clearingu (`ROLE_API` / `ROLE_PAYMENTS`).
- **provoz / payment-ops** (přes admin UI, Keycloak token) — spouští cykly a zúčtovávají dávky (`ROLE_PAYMENTS` / `ROLE_ADMIN`).
- **admin-ui / vieweři / operátoři** — read-only pohledy na dávky, položky a pozice.

## Závislosti

- **PostgreSQL** (databáze `openbank_clearing`, vlastněné schéma `clearing_schema` dle governance.yaml)
- **Kafka** (topic `openbank.clearing.batch.event`, kanál `clearing-events-out`)
- **Redis (Valkey)** — zapojen (`quarkus.redis.hosts`) pro idempotenci/cache
- **Keycloak** — OIDC autentizace
- **OPA sidecar** — `@Authorize` policy kontroly (ADR-0034), výchozí advisory
- **openbank-libs** — `libs.authz` (`@Authorize`, `OpaSidecarPolicyDecisionPoint`), `libs.security.Roles`, outbox plumbing, `DocsResource`, `ServiceInfoResource`

## Byznys hodnota

- **Agregace zúčtování** — z velkého objemu jednotlivých platebních instrukcí udělá malý počet čistých zúčtovacích pozic za účastníka, což je základ net settlementu na platebních railech.
- **Rail-aware zpracování** — jeden engine pro SEPA SCT, SEPA Instant, domácí a SWIFT, každý s vlastním cyklem.
- **Auditovatelnost** — zúčtování dávky emituje doménové události přes transakční outbox, čímž downstream služby (transaction-service, audit) dostávají eventually-consistent, přehratelný záznam.
- **Money-path disciplína** — least-privilege role, DB constraint na kladnou částku a udržovaný threat model chrání operaci s vysokým dopadem (jedno settle/trigger zasáhne mnoho plateb).
