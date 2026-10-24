# Data

Tato stránka zatím popisuje jen životní cyklus outboxu; zbytek datového modelu ještě není sepsán.

## Retence outboxu (řádky SENT)

`billing_outbox` je doručovací buffer, ale jeho SENT řádky ročních souhrnů jsou zatím jediným trvalým záznamem, že byl souhrn pro `(accountId, year)` vydán. Sdílený job `OutboxSentRetentionJob` z libs-runtime repozitář vidí, ale `sentRetentionExempt=true` přeskočí **všechny** billing SENT řádky (ADR-0329, #12187). Na billing tedy zatím neplatí sedmidenní mazání flotily.

PR #12311 přidává nezávislý klíč `(account_id, calendar_year)`, migraci odmítající chybná historická data a databázové testy smazání a opakovaného běhu. Nejprve nasaďte tuto ochranu a ověřte pokrytí historie; teprve potom odstraňte výjimku v repozitáři i v governance pravidle. Do té doby billing SENT řádky nemažte ani nezapínejte samostatný billing purge.

Sdílený job běží každou noc a maže jen staré SENT řádky repozitářů bez výjimky. Řádků PENDING, FAILED, DISPATCHING a DEAD se nedotýká. Dokud výjimka platí, metrika `openbank_outbox_purged_total` nemá vykazovat smazání billing řádků.

## Připnutá revize katalogu (ADR-0341)

Poplatky se oceňují podle revize produktového katalogu, ke které je účet **připnut**, ne podle aktuální. `billing_account_pin` drží jeden řádek na účet (`account_id`, `product_revision_id`), zapsaný jednou při prvním vyměření; pin se nikdy nepřepisuje. `assessed_fee` získává nullable `product_revision_id` a `accounting_class`; poplatek se zaúčtuje s účetní třídou revize a GL účet určí ledger. Poplatky vyměřené před existencí pinu zůstávají na legacy cestě (`LegacyRevisionBridge`, počítáno), dokud nedoběhnou. Migrace V9 je aditivní; rollback je `DROP TABLE billing_account_pin` a zrušení obou sloupců.
