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
