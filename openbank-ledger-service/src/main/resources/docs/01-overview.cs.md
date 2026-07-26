# Přehled

## Co služba dělá

`openbank-ledger-service` je **podvojná hlavní kniha** platformy OpenBank — zlatý zdroj účetní pravdy ([ADR-0039](../../../../docs/adr/0039-ledger-as-golden-source-balance-as-projection.md)). Drží:

- **GlAccount** — účtovou osnovu: kód, název, typ (ASSET / LIABILITY / EQUITY / INCOME / EXPENSE), jednu měnu, hierarchii rodič/list. Naseedováno hotovostními, vkladovými, úrokovými, poplatkovými, per-měnovými deposit-control (2100/2101/2102/2103), FX position (199x) a FX counter-value (199x-CV) účty.
- **JournalEntry** — vyvážený, neměnný účetní doklad: reference na transakci, datum zápisu/valuty, stav (PENDING / POSTED / REVERSED) a **dva nebo více řádků**, které musí být vyvážené **v rámci každé měny** (per-měnové vyvažování dle ADR-0025).
- **JournalLine** — jeden debetní nebo kreditní řádek: účet hlavní knihy, strana, částka + měna, FX kurz, bázová částka + bázová měna, sekvence a volitelný **sub_account_id** (analytická evidence po zákaznících na deposit-control řádcích, ADR-0039 fáze B).
- **TrialBalance** — předvaha: debetní/kreditní součty po účtech, které musí dát nulu.

Služba také provádí **denní FX revalvaci** (mark-to-ČNB devizových pozic, [ADR-0046](../../../../docs/adr/0046-daily-fx-revaluation-mechanics-and-cnb-rates.md)).

## Co služba **NEDĚLÁ**

- ❌ Nepočítá ani neposkytuje zákaznické zůstatky — to dělá `balance-service`, projekce read-modelu (ADR-0039).
- ❌ Neorchestruje platby ani ságy — to dělá `transaction-service`, který sem následně zaúčtuje vyvážený zápis.
- ❌ Nedrží definici účtu / IBAN — to je `account-service`.
- ❌ Neprovádí AML/sankční screening — `aml-service` / `sanctions-service`.
- ❌ Nezískává FX kurzy — čte zákonný kurz ČNB z `fx-service`.

## Pozice v doméně

```
   ┌────────────────────┐  zaúčtuj vyvážený zápis  ┌──────────────────┐
   │ transaction-service│ ───────────────────────► │  ledger-service  │
   └────────────────────┘   (PostJournalCommand)    └────────┬─────────┘
                                                             │
   ┌────────────────────┐  GET kurz ČNB (REST)               │ outbox → Kafka
   │     fx-service      │ ◄────────────────────────────────┐│  openbank.ledger.journal.posted
   └────────────────────┘                                   ││
                                                             ▼▼
   ┌─────────────────┐                              ┌──────────────────────┐
   │   PostgreSQL    │ ◄── partitionovaná kniha ──  │ balance-service      │
   │ (openbank_ledger│                              │ audit-service        │
   │  partitionováno │                              │ rekonciliace         │
   │  dle entry_date)│                              └──────────────────────┘
   └─────────────────┘
```

## Klíčové use-casy

| Use-case | API | Událost |
|---|---|---|
| Zaúčtovat vyvážený zápis | `POST /api/v1/journals` | `JournalPosted` → `openbank.ledger.journal.posted` |
| Stornovat zaúčtovaný zápis | `POST /api/v1/journals/{journalId}/reverse` | `JournalReversed` |
| Seznam zápisů (cursor stránkování) | `GET /api/v1/journals` | — |
| Detail zápisu podle ID | `GET /api/v1/journals/{journalId}` | — |
| Zápisy pro transakci | `GET /api/v1/journals/transaction/{transactionId}` | — |
| Předvaha (debet/kredit po účtech) | `GET /api/v1/journals/trial-balance` | — |
| Analytická evidence po zákaznících | `GET /api/v1/journals/sub-ledger-balances` | — |
| Denní FX revalvace (ops/backfill) | `POST /api/v1/ledger/fx-revaluation` | `FxRevalued` → `openbank.ledger.fx.revalued` |

## Volající

- **transaction-service** (`ROLE_OPERATOR`/`ROLE_API`) — účtuje vyvážený zápis pro každou vypořádanou transakci; jediný zapisovatel obchodních zápisů.
- **balance-service** (`ROLE_API`) — čte hlavní knihu / analytickou evidenci pro rekonciliaci proti read-modelu zůstatků (ADR-0039).
- **audit-service** — konzumuje proud událostí `JournalPosted` pro neměnný audit řetězec.
- **admin-ui** (`ROLE_OPERATOR` / `ROLE_AUDITOR` / `ROLE_VIEWER`) — operátoři a auditoři prochází zápisy, předvahu, analytiku; operátoři spouští backfill FX revalvace.
- **FxRevaluationScheduler** (v procesu) — automaticky řídí denní revalvaci.

## Závislosti

- **PostgreSQL** (`openbank_ledger`) — partitionovaná kniha, účty HK, outbox, idempotence, audit partitionů.
- **Kafka** (`openbank-kafka`, topic `openbank.ledger.journal.posted`) — dispatch outboxu.
- **fx-service** (REST, OIDC client filter) — zákonný kurz ČNB pro revalvaci.
- **Keycloak** — OIDC auth (RS256 JWT).
- **openbank-libs** — Money / CurrencyCode, DomainEvent, CursorPage stránkování, Roles, outbox plumbing, ServiceInfoResource, DocsResource, BuildInfo.

## Obchodní hodnota

- **Jediný zdroj účetní pravdy** — každý pohyb peněz v bance je zde vyváženým podvojným zápisem; zůstatek zákazníka je jen jeho projekcí (ADR-0039).
- **Regulatorní integrita** — per-měnové vyvažování (ADR-0025), neměnná append-only kniha s ročním partitioningem a opravou pouze stornem, plus analytická evidence po zákaznících, která tie-outuje deposit-control účty HK dle účetního zákona ČNB (563/1991 Sb. + vyhláška 501/2002 Sb.).
- **Distribuce událostí at-least-once** přes transakční outbox (ADR-0050), aby downstream balance/audit pohledy byly eventually consistent.
- **Zákonné FX ocenění** — denní mark-to-ČNB revalvace devizových pozic s zaúčtováním kurzových rozdílů na výsledkový účet (ADR-0046).
