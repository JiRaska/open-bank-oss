# Přehled

## Co služba dělá

`openbank-standing-order-service` je **systém záznamu pro opakované platební příkazy** (trvalé příkazy) v platformě OpenBank. Drží:

- **Agregát StandingOrder** — trvalou instrukci: účet plátce, příjemce (IBAN, jméno, volitelný BIC), částku (uloženou v minor units), měnu, `frequency` (DAILY / WEEKLY / BIWEEKLY / MONTHLY / QUARTERLY / ANNUALLY), `paymentType` (SEPA_CREDIT / DOMESTIC / INTERNAL), volitelnou poznámku pro příjemce, `startDate` / volitelný `endDate`.
- **Stav životního cyklu** — `status` ∈ ACTIVE / PAUSED / CANCELLED / COMPLETED / FAILED, plus evidence provádění (`nextExecutionDate`, `lastExecutionDate`, `executionCount`, `failureCount`).
- **Outbox** — transakční outbox řádek na každou změnu stavu, odesílaný do Kafky pro navazující konzumenty.

## Co služba **NEDĚLÁ**

- ❌ Nepřesouvá peníze — neodepisuje z účtu ani neúčtuje do ledgeru. Vlastní platbu zakládá navazující služba (`transaction-service`, poté služby SEPA/tuzemských plateb).
- ❌ Nedrží zůstatky — to dělá `balance-service`.
- ❌ Neprovádí AML/sankční screening — screening gate (ADR-0032) vynucují navazující platební povrchy při materializaci opakované platby.
- ❌ Nevaliduje vlastnictví účtu / dostatek prostředků v čase provedení — to je odpovědnost konzumující platební služby.
- ❌ Nevede podvojné účetnictví — to dělá `ledger-service`.

> **Poznámka k vyzrálosti:** **plánovač provádění** (komponenta procházející `next_execution_date` a spouštějící platbu pro každý splatný příkaz) je definován na úrovni portu (`listDueForExecution`), ale v tomto buildu zatím není zapojen jako naplánovaná úloha — na časovači běží jen outbox dispatcher. Naplánovanou materializaci berte jako poslední mezeru (TBD), nikoli jako dodanou schopnost.

## Pozice v doméně

```
   ┌────────────┐  POST /standing-orders   ┌────────────────────────┐
   │  admin UI  │ ───────────────────────► │ standing-order-service │
   │ / customer │                          └───────────┬────────────┘
   └────────────┘                                      │ outbox → Kafka
                                                        ▼
                            ┌──────────────────────────────────────────┐
                            │ Kafka: openbank.standing-orders.order.event│
                            └───────────────┬──────────────────────────┘
                                            ▼
                            ┌────────────────────┐   ┌───────────────┐
                            │ transaction-service│   │ audit-service │
                            │ (zakládá platbu)   │   │ notification  │
                            └────────────────────┘   └───────────────┘
                                            │
                                            ▼
                                       PostgreSQL
                              (DB: openbank_standing_orders)
```

## Klíčové případy užití

| Případ užití | API | Událost |
|---|---|---|
| Vytvořit trvalý příkaz | `POST /api/v1/standing-orders` | `StandingOrderCreated` |
| Získat trvalý příkaz | `GET /api/v1/standing-orders/{id}` | — |
| Vypsat příkazy pro party | `GET /api/v1/standing-orders/party/{partyId}` | — |
| Vypsat všechny příkazy | `GET /api/v1/standing-orders` | — |
| Pozastavit aktivní příkaz | `POST /api/v1/standing-orders/{id}/pause` | — |
| Obnovit pozastavený příkaz | `POST /api/v1/standing-orders/{id}/resume` | — |
| Zrušit příkaz | `DELETE /api/v1/standing-orders/{id}` | `StandingOrderCancelled` |
| (plánováno) Provést splatný příkaz | plánovač → navazující platba | `StandingOrderExecuted` |

## Volající

- **admin-ui / zákaznická app** (přes Keycloak token) — vytváření, výpis, pozastavení/obnovení, zrušení trvalých příkazů.
- **transaction-service** — navazující konzument událostí příkazů; materializuje vlastní platbu (governance linie `downstream → transaction-service`).
- **audit-service** — konzumuje události pro auditní stopu.

## Závislosti

- **PostgreSQL** (databáze `openbank_standing_orders`)
- **Kafka** (topic `openbank.standing-orders.order.event`)
- **Redis (Valkey)** — klient přítomen (idempotence / cache plumbing)
- **Keycloak** — OIDC autentizace
- **OPA sidecar** — autorizační rozhodnutí (`@Authorize`, ADR-0034), výchozí advisory režim
- **openbank-libs** — authz API (`@Authorize`, `PolicyDecisionPoint`), outbox plumbing, `ServiceInfoResource` (`/api/v1/info`), `DocsResource`

## Obchodní hodnota

- **Jediný zdroj pravdy** pro opakované instrukce zákazníka — definice příkazu žije na jednom místě, oddělená od samotného placení.
- **Oddělené provádění** přes outbox + Kafka — navazující založení platby je eventuálně konzistentní a odolné (dispatcher má circuit breaker, retry, bulkhead a timeout).
- **Auditovatelný životní cyklus** — každé vytvoření/zrušení vydá doménovou událost pro auditní pipeline.
- **Idempotentní vytvoření** — klientem dodaný `idempotencyKey` (unikátní v DB) činí založení příkazu bezpečným při opakování.
