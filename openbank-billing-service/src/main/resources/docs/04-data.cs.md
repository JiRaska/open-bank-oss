# Data

Tato stránka zatím popisuje jen životní cyklus outboxu; zbytek datového modelu ještě není sepsán.

## Retence outboxu (řádky SENT)

`billing_outbox` je doručovací buffer, ale jeho SENT řádky ročních souhrnů jsou zatím jediným trvalým záznamem, že byl souhrn pro `(accountId, year)` vydán. Sdílený job `OutboxSentRetentionJob` z libs-runtime repozitář vidí, ale `sentRetentionExempt=true` přeskočí **všechny** billing SENT řádky (ADR-0329, #12187). Na billing tedy zatím neplatí sedmidenní mazání flotily.

PR #12311 přidává nezávislý klíč `(account_id, calendar_year)`, migraci odmítající chybná historická data a databázové testy smazání a opakovaného běhu. Nejprve nasaďte tuto ochranu a ověřte pokrytí historie; teprve potom odstraňte výjimku v repozitáři i v governance pravidle. Do té doby billing SENT řádky nemažte ani nezapínejte samostatný billing purge.

Sdílený job běží každou noc a maže jen staré SENT řádky repozitářů bez výjimky. Řádků PENDING, FAILED, DISPATCHING a DEAD se nedotýká. Dokud výjimka platí, metrika `openbank_outbox_purged_total` nemá vykazovat smazání billing řádků.

## Připnutá revize katalogu (ADR-0341)

Poplatky se oceňují podle revize produktového katalogu, ke které je účet **připnut**, ne podle aktuální. `billing_account_pin` drží jeden řádek na účet (`account_id`, `product_revision_id`), zapsaný jednou při prvním vyměření; pin se nikdy nepřepisuje. `assessed_fee` získává nullable `product_revision_id` a `accounting_class`; poplatek se zaúčtuje s účetní třídou revize a GL účet určí ledger. Poplatky vyměřené před existencí pinu zůstávají na legacy cestě (`LegacyRevisionBridge`, počítáno), dokud nedoběhnou. Migrace V9 je aditivní; rollback je `DROP TABLE billing_account_pin` a zrušení obou sloupců.

## Shadow produktového IR (ADR-0352 fáze 3)

Migrace V10 přidává dvě tabulky, žádná se nečte na účtovací cestě. `billing_product_shadow_input` je lokální outbox: jeden řádek na každé nepřeskočené vyměření, zapsaný ve stejné transakci jako vyměření, se zmrazenými vstupy (zůstatek, měsíční obrat, zůstatek kapes, segment), připnutým `product_revision_id` a starým rozhodnutím pro každý poplatek. Comparator si splatné řádky zamyká přes `SKIP LOCKED`, označí je `DONE` s výsledkem (`AGREED`, `DIVERGED`, `RULE_UNMAPPED`, `INPUT_UNKNOWN`, `NO_FEES`, `NO_IR`, `VERIFICATION_DISABLED`, `VERIFICATION_FAILED`, `IR_EVALUATION_FAILED` nebo důvod opakování s koncovkou `_EXHAUSTED`). Zmrazené údaje o účtu vymaže jen tehdy, když IR vstup skutečně znovu rozhodlo; nesrovnatelný výsledek jako `NO_IR` nebo `VERIFICATION_DISABLED` je ponechá, aby šel vstup porovnat po odstranění příčiny. Řádek, který nejde převést zpět, nebo jehož vyhodnocení vyhodí výjimku, skončí jako `IR_EVALUATION_FAILED` a frontu neblokuje. `billing_product_revision_ir` je neměnná lokální kopie produktového IR připnuté revize a jejího podpisu, zapsaná jednou na revizi a chráněná triggerem; zaznamená se i revize bez IR. Rollback odstraní obě tabulky a funkci triggeru; předchozí verze je nezapisuje ani nečte.

V11 ukládá do shadow řádku okamžik a zónu vyměření, skutečné otevření účtu a kadenci volání. Starší řádky ponechají tyto údaje neznámé a skončí jako `INPUT_UNKNOWN`; čas workeru je nenahrazuje. Měsíční plánovač předává svou skutečnou kadenci, ruční volání žádnou. Úplné historické snímky událostí a zůstatků a jednoznačná identita vyvolaného tagu jsou nadále nutné před započítáním časových pravidel jako porovnaných. Rollback: zastavit shadow worker, nasadit předchozí zapisující verzi a odstranit pouze pět sloupců V11; řádky fronty a rozhodnutí V10 zachovat.
