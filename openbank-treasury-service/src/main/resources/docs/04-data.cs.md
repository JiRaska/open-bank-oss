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
- **`deals.row_version`** (V21) verzuje řádek obchodu. Každý příkaz životního cyklu (úprava, podání, schválení, potvrzení, vypořádání) ukládá jednou cestou, která řádek zamkne a porovná verzi; souběžná změna vrátí 409 `DEAL_CHANGED` místo přepsání. Override lze navrhnout, schválit a použít jen dokud je tok PROJECTED nebo FIXED, nemá platbu ani platební či amortizační účetní zápis a jeho obchod není ukončený; poslední kontrola se opakuje pod zámkem řádku toku a neúspěch vrátí 409 `FLOW_NOT_OVERRIDABLE`.
- Ve fázi 0 lze sjednat jen pět stávajících produktů. Návrh obchodu s produktem popsaným nohami vrátí 400, dokud jeho fáze nedodá účtovací pravidla.
- **Rollback:** V16 až V21 jsou aditivní (V21 přidává jeden sloupec s výchozí hodnotou 0). Stávající obchody dostanou druh `ACTUAL`, verzi 1 a verzi rozvrhu 0. Nové tabulky lze odstranit jen dokud se nepoužívají; příkazy jsou v hlavičce každé migrace.


## Fixingy a tržní data (ADR-0344, fáze 1a)

- **`observation_batches`, `index_observations`, `fixing_cases`** (V20) drží pozorování indexů. Dávka je PENDING, dokud ji neschválí jiný člověk, a použijí se jen ACTIVE pozorování. Kontrola kvality ověří MISSING, STALE a SUSPICIOUS ještě před fixingem; selhání otevře případ a nic nezafixuje.
- **`deal_cash_flows.fixing_source`, `fixing_sha256`, `fixed_at`** (V20) zaznamenávají původ zafixované sazby. Zapisuje je jen repozitář fixingů, hlídaným UPDATE.
- Hodinový běh fixingů (`openbank.treasury.fixing.enabled`, ve výchozím stavu zapnutý) zafixuje splatné pohyblivé toky a publikuje `treasury.cashflow.fixed.v1`.
- IRS a FRA lze navrhnout, podat a zafixovat; od fáze 2 se i vypořádají (níže).

## Matice účtování, úvěrová expozice a dokončení dne (ADR-0344, fáze 2)

- Navrhnout lze nově všechny produkty. Produkty popsané nohami se vypořádají, časově rozliší, zaplatí a dospějí přes `PostingMatrix` (`GET /posting-rules`); klíče deníků jsou `treasury:<dealId>:<event>:<flowId|date>` a `deal_journals` přijímá události matice (V28). Tok bez odpovídajícího pravidla je odmítnut.
- Každý produkt s úvěrovým rizikem protistrany čerpá linku přes `CreditExposure`: deriváty |nominál| × `derivative-conversion-factor` podle produktu a zbytkové splatnosti, reverzní repo poskytnutou hotovost minus zajištění po `collateral-haircut`, repo dodané zajištění minus přijatou hotovost. Zajištění se oceňuje cenou banky: poslední AKTIVNÍ (čtyři oči) pozorování tržních dat podle ISIN zajištění, nejvýše `collateralPriceMaxAgeDays` (pole `limit-settings`, výchozí 0) před dnem obchodu; cena uvedená v obchodu se s ní jen porovná, v toleranci `collateralPriceTolerancePercent` téhož záznamu (bez účinného záznamu jen shodná cena). Chybějící záznam nebo cena je 422 (`CONVERSION_FACTOR_MISSING` / `COLLATERAL_HAIRCUT_MISSING` / `COLLATERAL_PRICE_MISSING`), cena obchodu mimo toleranci `COLLATERAL_PRICE_OUT_OF_TOLERANCE`, nikdy nula. Hodnota se ukládá do `limit_deal_amount` a zamčený dotaz na linku ji sčítá. V27 zakládá syntetické faktory a výchozí haircut, vše `verified = false`.
- **`treasury_day_completion`** (V29) drží poslední výsledek každého kroku za bankovní den. Zapisují ho běhy časového rozlišení a fixingů; `GET /day-completion/{date}` vypíše všechny kroky, nezaznamenaný jako PENDING. Odpověď ledgeru `409 ACCOUNTING_DAY_CLOSED` se zapíše jako REFUSED.
- **`position_closes`, `deal_filters`** (V26): snímky pozic na konci dne (back office, povinný idempotency klíč) a uložené filtry blotteru každého uživatele.

## Platby na vlastní účet a interní kurzovní lístky (ADR-0344 D6, kontrakt 8.5)

- **`treasury_payments` / `treasury_payment_flows`** (V30) drží platby navržené z FIXED toků (netované podle nettingové dohody, adresované ze snímku SSI). Tok je nejvýše na jedné živé platbě: vazba je klíčovaná tokem i jeho slotem (obchod, noha, perioda, druh). `transfer_rail` a `intermediary_bic` (V31) se zapisují při uvolnění; idempotenční klíč je unikátní mezi platbami, které nejsou CANCELLED (V32), takže toky zrušené platby lze navrhnout znovu.
- **Uvolnění** provádí `ROLE_TREASURY_PAYMENT_RELEASER` a je odmítnuto každému, kdo je autorem netovaného obchodu nebo úpravy netovaného toku. Zapíše RELEASED a `treasury.payment.released.v1` (outbox, `openbank.treasury.payments`) v jedné transakci pod advisory zámkem na financovací linku (GL, měna), který započítá každou nezaúčtovanou odchozí platbu ke všem datům valuty. Stavový proud railu (`openbank.swift.institution-transfers`) posouvá platbu do SENT / SETTLED / FAILED; co nelze použít, jde do `openbank.dlq.treasury.institution-transfer-status-in`.
- **Ruční vyřešení** (MANUALLY_SETTLED / RETURNED) jen z RAIL_NOT_SUPPORTED nebo FAILED; RELEASED a SENT vrací 409 `PAYMENT_IN_FLIGHT`.
- **`internal_rate_sheets`** (V33): interní kurzovní lístky s platností od data a čtyřma očima, jeden ACTIVE na název. V33 také povoluje druh referenčních dat `internal-rate-sheet` v `reference_records`.
