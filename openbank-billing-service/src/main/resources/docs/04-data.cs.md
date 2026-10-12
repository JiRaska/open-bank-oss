# Data

Tato stránka zatím popisuje jen životní cyklus outboxu; zbytek datového modelu ještě není sepsán.

## Retence outboxu (řádky SENT)

`billing_outbox` je doručovací buffer. PR #12311 sloučil samostatný databázově unikátní klíč `(account_id, calendar_year)` pro roční souhrny; řádek SENT outboxu už ve zdrojovém kódu není ochranou proti opakovanému vydání. Sdílený job `OutboxSentRetentionJob` z libs-runtime repozitář vidí, ale `sentRetentionExempt=true` stále přeskočí **všechny** billing SENT řádky (ADR-0329, #12187). Na billing tedy zatím neplatí sedmidenní mazání flotily.

Sloučená migrace doplňuje klíče z dochovaných historických událostí a odmítá chybné nebo duplicitní identity; databázové testy pokrývají smazání a opakovaný běh. Před odstraněním výjimky v repozitáři i v governance pravidle ověřte nasazení klíče a porovnejte jej s nezávisle prokazatelně úplným archivem historických událostí, včetně těch, jejichž řádky outboxu už byly odstraněny. Samotné porovnání dodaných exportů úplnost historického archivu prokázat neumí. Do dokončení těchto kontrol a schváleného nasazení billing SENT řádky nemažte ani nezapínejte samostatný billing purge.

Sdílený job běží každou noc a maže jen staré SENT řádky repozitářů bez výjimky. Řádků PENDING, FAILED, DISPATCHING a DEAD se nedotýká. Dokud výjimka platí, metrika `openbank_outbox_purged_total` nemá vykazovat smazání billing řádků.
