# Přehled

## Co služba dělá

`openbank-sepa-instant` provádí **SEPA okamžité úhrady (SCT Inst)** — okamžitou linku s cílem zúčtování do 10 s. Drží:

- **Agregát SctInstPayment** — platební příkaz a jeho životní cyklus: identifikátory (`paymentId`, `endToEndId`, `idempotencyKey`), plátce (id účtu, IBAN, jméno), příjemce (IBAN, jméno, volitelný BIC), `amount` + `currency` (výchozí EUR), `remittanceInfo`, watchdog `executionTimeoutAt` a výsledkové časy/důvody (`settledAt`, `recalledAt`, `recallReason`, `rejectReason`, `rejectDetail`).
- **Stavový automat** — `PENDING → PROCESSING → SETTLED` na šťastné cestě, s `REJECTED`, `TIMEOUT` a `RECALLED` jako koncovými/větevními stavy.
- **Synchronní sankční bránu** (ADR-0032) při submitu: jména plátce i příjemce jsou prověřena **dříve**, než je platba uvolněna. Rozhodnutí (CLEAR / REVIEW / BLOCK) provádí čistý `ScreeningPolicy` v doméně.

## Co služba **NEDĚLÁ**

- ❌ Nevede podvojnou knihu — to je `ledger-service`.
- ❌ Neukládá kanonickou transakci — požádá `transaction-service`, aby ji vytvořila (lineage `creates`).
- ❌ Nepočítá zůstatky — to je `balance-service`.
- ❌ Neprovozuje běžnou neokamžitou SEPA linku — to je `sepa-payment-service`.
- ❌ Nevlastní sankční seznamy ani vyšetřování AML případů — **volá** `sanctions-service` (screen) a `aml-service` (otevření případu); samo o AML rozhodnutí nikdy nerozhoduje.
- ❌ Nespravuje definice účtů — to je `account-service`.

## Pozice v doméně

```
   ┌────────────┐  POST /api/v1/sepa-instant   ┌──────────────────────┐
   │  volající  │ ───────────────────────────► │ openbank-sepa-instant│
   │ (admin UI/ │                              │                      │
   │  zákazník) │                              │  brána ScreeningPolicy│
   └────────────┘                              └───────┬──────────────┘
                                                       │ sync screen (plátce+příjemce)
                          ┌────────────────────────────┼────────────────────────────┐
                          ▼                            ▼                             ▼
                 ┌────────────────┐          ┌──────────────────┐         outbox → Kafka
                 │ sanctions-svc  │          │   aml-service    │      openbank.sepa.instant.events
                 │  POST /screen  │          │ POST /aml/cases  │                 │
                 └────────────────┘          └──────────────────┘                 ▼
                                                                       ┌──────────────────────┐
   PostgreSQL                                                          │ transaction-service  │
   (openbank_sepa_instant)                                            │ ledger / balance     │
                                                                       │ audit / notification │
                                                                       └──────────────────────┘
```

## Klíčové scénáře

| Scénář | API | Událost / downstream volání |
|---|---|---|
| Submit okamžité platby (prověřené) | `POST /api/v1/sepa-instant` | `SctInstPaymentSubmitted` (při CLEAR) |
| Sankční zásah → blokace | (tentýž submit) | `SctInstPaymentRejected` + CRITICAL AML případ |
| Potenciální zásah / výpadek prověrky → podržení | (tentýž submit) | podrženo `PENDING`, otevřen HIGH/MEDIUM AML případ |
| Detail platby podle id | `GET /api/v1/sepa-instant/{paymentId}` | — |
| Seznam plateb pro účet plátce | `GET /api/v1/sepa-instant/debtor/{debtorAccountId}` | — |
| Seznam všech plateb | `GET /api/v1/sepa-instant` | — |
| Recall zúčtované platby | `POST /api/v1/sepa-instant/{paymentId}/recall` | `SctInstPaymentRecalled` |
| Timeout watchdogu provedení | (interní) | `SctInstPaymentTimeout` |

## Volající

- **admin-ui / zákaznické platební toky** (přes Keycloak token) — submit, dotazy, recall.
- Ostatní OpenBank služby konzumující vydávané události (transaction, ledger, balance, audit, notification) přes Kafku.

## Závislosti

- **PostgreSQL** (`openbank_sepa_instant`, schéma `sepa_instant_schema`)
- **Kafka** (`openbank-kafka`, topic `openbank.sepa.instant.events`)
- **sanctions-service** — synchronní screen (`POST /api/v1/sanctions/screen`); fail-closed při nedostupnosti
- **aml-service** — otevření AML případu (`POST /api/v1/aml/cases`); best-effort follow-up
- **Redis (Valkey)** — klient nakonfigurován (`redis://…`)
- **Keycloak** — OIDC auth; **OPA** sidecar — autorizace (ADR-0034, ve výchozím stavu advisory)
- **openbank-libs** — autorizace (`@Authorize`), outbox/event plumbing, BuildInfo, DocsResource

## Obchodní hodnota

- **Okamžité zúčtování s compliance jistotou** — každá okamžitá platba je synchronně sankčně prověřena dříve, než opustí banku; žádná platba se nikdy nezúčtuje neprověřená (fail-closed).
- **Auditovatelné výstupy** — submit / reject / settle / timeout / recall vydávají doménovou událost pro auditní stopu.
- **Workflow recallu** — podpora vrácení zúčtované okamžité platby z důvodů fraud / duplicate / wrong-amount / wrong-beneficiary.
- **Money-path odolnost** — always-on (T0) s circuit-breakerem, retry a timeout fault tolerance na prověrkovém hopu.
