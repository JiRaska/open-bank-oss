# Přehled

## Co služba dělá

`openbank-transaction-service` je **systém záznamu pro transakce** v platformě OpenBank a orchestrátor, který z platebního požadavku udělá zaúčtovanou transakci. Drží:

- **Agregát Transaction** — referenční číslo, typ (DEBIT / CREDIT / TRANSFER / FEE / INTEREST / REVERSAL / ADJUSTMENT), zdrojový/cílový účet, částku + měnu, FX kurz a zúčtovací (base) částku, stav (PENDING / PROCESSING / COMPLETED / FAILED / REVERSED), datumy valuty/zaúčtování a bohatou sadu vyhledávacích polí BIAN / ISO 20022 (IBAN, BBAN, end-to-end id, protistrana, purpose code, …).
- **PaymentSaga** — stavový automat orchestrace pro každou transakci (STARTED → PAYMENT_INITIATED → FUNDS_RESERVED → LEDGER_POSTING → FUNDS_CAPTURED → COMPLETED, s větvemi COMPENSATING / COMPENSATED / FAILED), který řídí distribuovaný „pohyb peněz" mezi balance-service a ledger-service.

Když je transakce iniciována, ságu spustí synchronně: vloží hold na zdrojovou kapsu (balance-service), zaúčtuje podvojný journal (ledger-service), zachytí debet, připíše prostředky na kapsu příjemce a označí transakci COMPLETED — nebo kompenzuje (reverze journalu, vrácení na kapsu, uvolnění holdu) při jakékoli chybě.

## Co služba **NEDĚLÁ**

- ❌ Nevede podvojnou hlavní knihu — to je `ledger-service` (tato služba ji *volá* pro zaúčtování journalu).
- ❌ Nepočítá ani nedrží autoritativní zůstatky — to je `balance-service` (tato služba vůči ní volá hold / debet / kredit).
- ❌ Nevlastní FX kurz — kurzy čte z `fx-service` pro zúčtování v jiné měně.
- ❌ Nemluví žádným platebním schématem — `sepa-payment`, `sepa-instant`, `domestic-payment`, `swift-service`, `standing-order-service`, `clearing-service` překládají zprávy schématu a transakce zakládají zde.
- ❌ Sama neprovádí AML/sankční screening — screening je gatovaný výše v platebních službách (sloupec `aml_screened` eviduje výsledek pro audit).

## Pozice v doméně

```
   ┌──────────────────────┐  POST /transactions   ┌──────────────────────┐
   │ platební služby      │ ────────────────────► │ transaction-service  │
   │ sepa / domestic /    │                        │  (orchestrátor ságy) │
   │ swift / instant / SO │                        └──────────┬───────────┘
   └──────────────────────┘                                   │
            ▲ fx-service (kurzy)                               │ synchronní volání
            │                                                  ▼
            │                          ┌────────────────────────────────────┐
            │                          │ balance-service  (hold/debet/kredit)│
            │                          │ ledger-service   (post/reverze GL)  │
            │                          └────────────────────────────────────┘
            │
   ┌────────┴─────────┐  outbox → Kafka   ┌──────────────────────┐
   │ transaction-     │ ────────────────► │ audit-service        │
   │ service          │                   │ notification-service │
   └──────┬───────────┘                   └──────────────────────┘
          ▼
     PostgreSQL
   (openbank_transactions, vlastní schéma `transactions_schema`)
```

## Klíčové případy užití

| Případ užití | API | Událost |
|---|---|---|
| Iniciace transakce (řídí platební ságu) | `POST /api/v1/transactions` | `openbank.transactions.transaction.initiated` poté `.completed` nebo `.failed` |
| Výpis transakcí účtu (cursor stránkování) | `GET /api/v1/transactions?accountId=…` | — |
| Vyhledávání transakcí (IBAN/BBAN/reference/částka/datum/protistrana) | `GET /api/v1/transactions/search` | — |
| Načtení jedné transakce podle id | `GET /api/v1/transactions/{transactionId}` | — |

## Volající

- **platební služby** (`sepa-payment`, `sepa-instant`, `domestic-payment`, `swift-service`, `standing-order-service`, `clearing-service`) — iniciují transakce (`ROLE_API`/`ROLE_OPERATOR`).
- **fx-service** — upstream zdroj kurzů pro zúčtování v jiné měně (tato služba volá *směrem ven* k němu).
- **agent-service** — read-only MCP nástroj nad historií transakcí (`ROLE_API`).
- **admin-ui** — operátoři / compliance čtou historii transakcí a vyhledávají.

## Závislosti

- **PostgreSQL** (`openbank_transactions`, vlastní `transactions_schema`)
- **Kafka** (topic `openbank.transactions.transaction.initiated` a sourozenecké typy událostí)
- **ledger-service** (REST, fault-tolerant klient `LedgerCallGuard`) — zaúčtování / reverze journalu
- **balance-service** (REST) — vložení holdu, debet, kredit, uvolnění holdu
- **fx-service** (REST) — FX kurz pro zúčtování v jiné měně
- **Keycloak** — auth (OIDC); `oidc-client` pro service-to-service tokeny
- **openbank-libs** — `Money`/`CurrencyCode`, `CursorPage`/`CursorEncoder` stránkování, `SagaStateMachine` (ADR-0045), outbox primitiva, `Roles`, `ServiceInfoResource`, `DocsResource`

## Byznys hodnota

- **Jediný zdroj pravdy** o tom, co bylo transakováno — každá platba, poplatek, úrokové zaúčtování a reverze přistane zde se stabilním referenčním číslem a auditní stopou.
- **Atomický pohyb peněz** — platební sága udržuje konzistenci kapes zůstatků a ledgeru, čistě kompenzuje při dílčí chybě, takže zákazník nikdy nezůstane krátký.
- **Historie pro regulátora** — partitionovaný sklad transakcí s 7letou retencí a vyhledávacími poli ISO 20022 / BIAN pro reporting ČNB, řešení sporů a Open Banking historii.
- **Eventuální propagace** přes outbox + Kafku — konzumenti auditu a notifikací vidí každou událost životního cyklu během sekund.
