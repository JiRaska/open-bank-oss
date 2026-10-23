# Data

Tato stránka zatím popisuje jen životní cyklus outboxu; zbytek datového modelu ještě není sepsán.

## Retence outboxu (řádky SENT)

`treasury_outbox` je doručovací buffer, ne záznam. Řádky, které už dorazily do brokeru (`status = 'SENT'`), se mažou, jakmile je jejich `sent_at` starší než `openbank.outbox.retention.sent-days` (výchozí **7**), sdíleným jobem `OutboxSentRetentionJob` z libs-runtime (ADR-0329, ADR-0327 D8). Repozitář se zapojuje delegací `SentOutboxRetention` na `PanacheOutboxRetention`.

- Běží každou noc (`openbank.outbox.retention.cron`, výchozí `0 17 3 * * ?`) na každé replice; každé mazání je omezené (`batch-size` 5 000, nejvýš `max-batches` 200 za běh), takže dlouho nečištěná tabulka se vyprázdní během několika nocí.
- Řádků PENDING, FAILED, DISPATCHING a DEAD se nikdy nedotkne — DEAD řádky jsou DLQ na straně producenta.
- Replay události starší než okno jde z Kafka topicu nebo z audit-service, ne z této tabulky.
- Signály: `openbank_outbox_purged_total{service,status="SENT"}`, `openbank_outbox_purge_failed_total` a workflow liveness `outbox-sent-retention`.
- Vypnutí: `openbank.outbox.retention.enabled=false` (při startu zaloguje WARN). Nesnižujte `sent-days` pod potřebu kteréhokoli čtenáře SENT řádků.

## Zaúčtování uvádí účetní třídu, ne GL kód (ADR-0341 D3)

Noha zaúčtování nově nese `accountingClass` + `LedgerRole` (jistina, naběhlý úrok, úrokový výnos/náklad) + měnu a žádný GL kód; účet určí ledger ze své mapy. Každý `ProductType` deklaruje svou účetní třídu (např. `INTERBANK_PLACEMENT`, `CNB_LOMBARD_BORROWING`) a odsouhlasení nostra používá `TreasuryChart.nostroCode`. Test s legacy oraklem zajišťuje, že výsledné žurnály se nemění.

## Referenční data a cash-flow ledger (ADR-0344, fáze 0)

- **`reference_records`** (V16, V17) drží efektivně datovaná, verzovaná referenční data: kalendáře, indexy, protistrany, platební instrukce, knihy, typy produktů a další druhy z katalogu ADR-0344. Verze je PENDING, dokud ji neschválí jiný člověk. Schválení `counterparty`, které zvyšuje limit, vyžaduje senior approvera. `row_version` je optimistický zámek. Kontrola limitů čte ACTIVE verzi `counterparty`; tabulka `counterparties` je jen projekce pro cizí klíč obchodů. `reference_commands` ukládá Idempotency-Key každého příkazu.
- **`deal_versions`, `deal_cash_flows`, `cash_flow_overrides`, `cash_flow_conflicts`** (V18, V19) ukládají každou verzi podmínek a její vygenerovaný cash-flow ledger. Podání zapíše ledger a `treasury.cashflow.generated.v1` spolu s obchodem v jedné transakci. Override je čtyřoký a zachovává původní datum i částku. Regeneraci přežije, jen dokud se nezmění hash specifikace nohy; jinak se z něj stane evidovaný konflikt. `deal_cash_flows.row_version` je optimistický zámek.
- Ve fázi 0 lze sjednat jen pět stávajících produktů. Návrh obchodu s produktem popsaným nohami vrátí 400, dokud jeho fáze nedodá účtovací pravidla.
- **Rollback:** V16 až V19 jsou aditivní. Stávající obchody dostanou druh `ACTUAL`, verzi 1 a verzi rozvrhu 0. Nové tabulky lze odstranit jen dokud se nepoužívají; příkazy jsou v hlavičce každé migrace.


## Fixingy a tržní data (ADR-0344, fáze 1a)

- **`observation_batches`, `index_observations`, `fixing_cases`** (V20) drží pozorování indexů. Dávka je PENDING, dokud ji neschválí jiný člověk, a použijí se jen ACTIVE pozorování. Kontrola kvality ověří MISSING, STALE a SUSPICIOUS ještě před fixingem; selhání otevře případ a nic nezafixuje.
- **`deal_cash_flows.fixing_source`, `fixing_sha256`, `fixed_at`** (V20) zaznamenávají původ zafixované sazby. Zapisuje je jen repozitář fixingů, hlídaným UPDATE.
- Hodinový běh fixingů (`openbank.treasury.fixing.enabled`, ve výchozím stavu zapnutý) zafixuje splatné pohyblivé toky a publikuje `treasury.cashflow.fixed.v1`.
- IRS a FRA lze nově navrhnout, podat a zafixovat. Vypořádat je půjde až s maticí účtovacích pravidel (fáze 2).

## Platby a kanál mezibankovních převodů (ADR-0344, fáze 1b)

- **`treasury_payments`, `treasury_payment_flows`** (V21) drží platby na vlastní účet a FIXED toky, které každá platba hradí. Tok patří nejvýše jedné platbě; vazbu zapisuje jen hlídaný UPDATE, nikdy uložení celého řádku toku. V22 přidává převodní kanál a BIC zprostředkovatele. V23 drží idempotenční klíč platby jedinečný jen mezi nezrušenými platbami, takže zrušenou platbu lze navrhnout znovu.
- Denní běh návrhů (`openbank.treasury.payments.enabled`, ve výchozím stavu zapnutý) navrhne platby pro FIXED toky splatné do `cut-off-window-days` (2) dnů, započtené podle nettingové dohody a adresované podle SSI platné k datu valuty. Uvolnit je smí jen `ROLE_TREASURY_PAYMENT_RELEASER`, a nikdy autor podmínek započteného obchodu.
- Uvolnění zapíše `treasury.payment.released.v1` do `openbank.treasury.payments` v téže transakci. swift-service z něj sestaví pacs.009 a odpoví na `openbank.swift.institution-transfers`; treasury pak platbu posune na SENT, SETTLED nebo FAILED. Stav, který nelze použít, skončí v `openbank.dlq.treasury.institution-transfer-status-in`.
- Zrušení nebo storno obchodu se odmítne, dokud je některá jeho platba uvolněná (409 PAYMENT_RELEASED); jinak ve stejné transakci stáhne jeho platby ve stavu PROPOSED.
- **Rollback:** V21 až V23 jsou aditivní. Tabulky lze odstranit jen dokud neexistuje žádná platba; příkazy jsou v hlavičce každé migrace.

## Matice účtovacích pravidel (ADR-0344 fáze 2a)

- **`deal_journals`** (V24) nově připouští události matice (`TRADE`, `SETTLEMENT`, `ACCRUAL`, `PAYMENT`, `AMORTISATION`, `MATURITY` a další) vedle původních `SETTLED`, `ACCRUED`, `MATURED`, `REVERSED`. Klíč deníku matice je `treasury:<dealId>:<event>:<flowId|date>`, takže každý uhrazený tok a každý den časového rozlišení se zaúčtuje jednou.
- `PostingMatrix` jsou verzovaná data: jeden řádek na produkt, událost, kategorii IFRS 9 a výběr toku, vystavená na `GET /api/v1/treasury/posting-rules`. Strana deníku uvádí účetní třídu a roli; ledger je mapuje na své instrumentové účty z V35.
- IRS a FRA se nově vypořádávají, časově rozlišují, platí a splácejí přes matici. Ostatní produkty popsané nohami mají řádky neaktivní, dokud nepřijde jejich fáze.
- Odchozí platba ve stavu SENT nebo SETTLED hned zaúčtuje deníky PAYMENT svých toků se stejnými klíči, jaké používá denní běh. Když ledger neodpovídá, zaúčtuje je další přehrání nebo denní běh a `openbank.treasury.payments.outflow.failed` výpadek započítá.
- **Rollback:** V24 jen rozšiřuje CHECK. Původní omezení lze vrátit, dokud neexistuje žádný řádek deníku matice; příkaz je v hlavičce migrace.

## FX forwardy a swapy, směnky, úvěry, přecenění a analýza (ADR-0344 fáze 3)

- FX_FORWARD, FX_SWAP, BILL_OF_EXCHANGE a LOAN lze nově navrhnout a vypořádávají se, časově rozlišují, platí a splácejí přes matici. Každý má paritní fixtury s nezávislým orákulem a své původní zlaté deníky.
- **`deal_revaluations`** (V25) drží jeden řádek na obchod a datum ocenění: čistou reálnou hodnotu z rizikového enginu na snapshotu TIED_OUT k tomu datu, účetní hodnotu, kumulativní úpravu a změnu zaúčtovanou jako událost matice REVALUATION (FVOCI do fondu z přecenění, FVTPL a deriváty do nerealizovaného zisku či ztráty). Klíč v ledgeru je `treasury:<dealId>:revaluation:<date>`. Data na obchod jen rostou; dřívější datum se odmítne a nikdy se nedoplňuje. Obchod, který engine neocenil, je hlášená mezera, nikdy nula.
- Denní běh (`openbank.treasury.revaluation.enabled`, ve výchozím stavu zapnutý, cron `openbank.treasury.revaluation.cron`) a `POST /api/v1/treasury/revaluations/run` nejdřív znovu zaúčtují dlužné řádky; `openbank_treasury_revaluation_unposted` počítá, co ledger ještě nepřijal, a spouští `TreasuryRevaluationUnposted`.
- **`internal_rate_sheets`** (V26) drží interní sazby schválené čtyřma očima (`/market-data/internal-rate-sheets`). Jeden ACTIVE list na jméno; schválení nahradí předchozí. Zatím se z nich nic neoceňuje.
- `POST /api/v1/treasury/analysis/cash-flows` agreguje uložené peněžní toky podle měny (hrubě, netto nebo kumulativně). Jen čte a přepočítává jen schváleným fixingem `FX_<CCY>_<REF>`.
- **Rollback:** V25 a V26 jsou aditivní. Tabulky lze odstranit, dokud neobsahují řádek; příkaz je v hlavičce každé migrace. Zaúčtované deníky přecenění v ledgeru zůstávají.
