# 05 — Provoz

## Příjem XML camt.053

Treasury zpracovává nedůvěryhodné soubory camt.053 pomocí `SecureXml`. Jeho DOM builder odmítne deklaraci DOCTYPE a externí entity ještě před vytvořením dokumentu. Odmítnutý či chybný soubor opravte u zdroje; nevypínejte ochranu parseru kvůli jeho přijetí. Služba neprovádí síťové načítání entit uvedených v souboru.

## Nasazení účetních tříd v ledgeru (ADR-0341 D3)

Treasury umí účtovat podle účetní třídy (GL účet určí ledger) nebo s explicitními GL účty.
Volí to `openbank.treasury.ledger.post-by-class` (env `OPENBANK_TREASURY_LEDGER_POST_BY_CLASS`); výchozí hodnota je `false` a posílá explicitní payload, kterému rozumí
každý ledger.

Pořadí nasazení, vždy:

1. Nasaďte ledger s migrací `V32__accounting_class_gl_map.sql` a ověřte, že odpovídá na
   `GET /api/v1/accounting-classes` (200).
2. Teprve potom nastavte `OPENBANK_TREASURY_LEDGER_POST_BY_CLASS=true` pro Treasury a službu restartujte. Příznak se čte při startu.

Nasazení Treasury před ledgerem je bezpečné, dokud je příznak `false`.

Odsouhlasení nostra se řídí stejným příznakem: s `false` se nostro účty berou z vestavěné náhradní osnovy,
takže 404 starého ledgeru na mapě tříd nezastaví nahrávání ani odsouhlasení; s `true` se mapa ledgeru čte
jednou na každý upload či běh odsouhlasení. Storno vyrovnává po jednotlivých GL účtech přesně to, co
na ně žurnály obchodu zaúčtovaly (`deal_journals.resolved_accounts`), ať mapa dnes říká cokoli: časové
rozlišení, které po přesměrování mapy dopadlo na dva účty, se odúčtuje dvěma řádky.

**Storno odmítnuto s 409 `REVERSAL_ACCOUNTS_UNKNOWN`.** Některý stornovaný žurnál nemá zapsané účty
(zaúčtován před `V15`, nebo odpověď ledgeru nešlo spárovat s řádky) nebo zápis jeho řádkům neodpovídá.
Treasury třídu podle dnešní mapy znovu nevyhodnocuje, nic nezaúčtuje a obchod zůstává `SETTLED`. Postup:

1. Z chybové zprávy vezměte id žurnálu a v ledgeru přečtěte jeho řádky (záznam ledgeru, kam který
   řádek dopadl, v pořadí `sequence`). Žurnály z doby před ADR-0341 mají pevné účty
   `a0000000-0000-0000-0000-00000000<kód>`; ověřte, nepředpokládejte.
2. V rámci změnového záznamu s druhým schvalovatelem zapište do `deal_journals.resolved_accounts`
   pro daný řádek jeden řádek `třída|role|měna|glAccountId` na každou nohu, v pořadí z účtovací tabulky.
3. Storno zopakujte (neúspěšný pokus neuložil klíč příkazu, stejný `Idempotency-Key` tedy projde).

**Rollback.** S příznakem `true` řádky účet neobsahují, takže starší ledger (pod `V32`) odmítne každý
řádek. Samotnou konfigurací se vrátit nelze: nejdřív nastavte `OPENBANK_TREASURY_LEDGER_POST_BY_CLASS=false` a znovu nasaďte Treasury,
teprve potom vraťte ledger. Pokud se ledger vrátí jako první, každé zaúčtování selže, dokud se Treasury
znovu nenasadí.

## Sdílené závislosti sestavení

Soubor `build.gradle.kts` této služby neuvádí závislosti, které sdílí každá Quarkus služba: Quarkus BOM, quarkus-kotlin, RESTEasy Reactive s Jacksonem, SmallRye Health a OpenAPI, OIDC, registr Micrometer Prometheus a config-yaml a testovací knihovny quarkus-junit5, AssertJ a REST Assured. Deklaruje je konvenční plugin `openbank.quarkus-service` v `build-logic`. Výsledná classpath je beze změny. `build.gradle.kts` uvádí jen to, co služba přidává navíc.

## Odmítnutí fixingu po změně stavu obchodu

Před provedením fixingu repository znovu načte stav obchodu a verzi splátkového kalendáře pod
zámkem řádku obchodu (`FOR UPDATE`). Stavy `CANCELLED`, `REVERSED` a `DRAFT` vyvolají typovanou
výjimku `DealNotLiveForFixingException` ještě před změnou peněžního toku nebo zápisem události
`treasury.cashflow.fixed.v1` do outboxu. Odmítnutí zvýší čítač
`openbank.treasury.fixings{outcome="deal_not_live"}`; `FixingService` obchod přeskočí, aniž by započetl
selhání indexu. Stav `MATURED` zůstává způsobilý podle stávajícího filtru stavů a tvar REST odpovědi
se nemění.

`FixingApiIT` pokrývá zrušení potvrzené po načtení splatných toků a před zamčeným zápisem:
odpověď uvádí nulový počet fixovaných toků i selhání, projektované toky zůstanou nezměněné,
událost fixingu nevznikne a čítač odmítnutí se zvýší o jedna.

## Účast na uzávěrce dne (ADR-0342, #13167)

Treasury je účastníkem uzávěrky dne v ledgeru pod jménem `treasury`.
`TreasuryCloseParticipationScheduler` běží každou hodinu ve 40. minutě, Europe/Prague
(`openbank.treasury.close.report-cron`). Při každém běhu znovu nabídne posledních
`openbank.treasury.close.lookback-days` (3) dokončených účetních dnů přes
`PUT /api/v1/ledger/accounting-days/{date}/participants/treasury`. Hlásí na vlastní m2m identitě treasury
(`service-account-openbank-treasury`), kterou ledger pustí pod `ledger.close.participate` jen pro tohoto
účastníka.

- **Doklad.** Každý průchod hodinového časového rozlišení (`InterestAccrualScheduler`) pracuje k jednomu
  pražskému účetnímu dni. Než cokoli udělá, označí se v `treasury_close_job_run` (V26) jako
  rozběhnutý. Po skončení zapíše počet posouzených obchodů. Odmítnutí zapíše, když se obchod nepodařilo
  rozlišit, když ledger odpověděl `409 ACCOUNTING_DAY_CLOSED`, když vyčerpal rozpočet a obchody zbyly,
  nebo když průchod selhal. Odmítnutí uzavřeným dnem má vlastní kategorii. Pokud nejde zapsat značka
  rozběhnutí, průchod se nespustí.
- **Datování žurnálů.** Ledger přijímá účtování jen do otevřeného dne. Každý žurnál treasury se
  proto účtuje s `entryDate` = dnešní otevřený pražský den a nese svůj ekonomický den jako `valueDate`
  (den rozlišení, datum valuty vypořádání). Stejně se rozhodl lending. Dohánění za dřívější den tak už
  nemůže narazit na `409 ACCOUNTING_DAY_CLOSED` jen proto, že ten den přešel do CUTOFF. Každý žurnál
  se zapisuje do `deal_journals` se svým dnem valuty (V26 `value_date`).
- **Jak se den nahlásí.** Pozdržení kvůli outboxu a kontrola DEAD běží vždy, ať je splatné cokoli.
  - Je-li rozlišení vypnuté, den je REFUSED při událostech DEAD, RAN má-li žurnály, a SKIPPED jen
    tehdy, když nemá ani jedno.
  - Je-li rozlišení zapnuté a pro den žádný průchod nedoběhl, nehlásí se nic a ledger den drží
    v CUTOFF.
  - Jinak rozhoduje poslední průchod: RAN, nebo REFUSED se spočtenými důvody.
  - Počet žurnálů je počet řádků `deal_journals` s daným dnem VALUTY: rozlišení, vypořádání,
    splatnosti a storna.
- **Pozdní žurnály.** Žurnál s valutou do již skončeného dne se počítá pod tento den a zvýší
  `openbank.treasury.close.late_journals`. Další běh den nahlásí znovu s novým počtem a ledger hlášení
  za uzavřený den uloží jako pozdní.
- **Pozdržení.** Den drží kterékoli z těchto:
  - průchod, který ještě běží;
  - událost `treasury.deal.settled|matured|reversed.v1` přiřazená přes stejný obchod a druh životního
    přechodu ke dni valuty žurnálu, dokud je v `treasury_outbox` ve stavu PENDING, DISPATCHING nebo
    FAILED (událost DEAD udělá z dne REFUSED). Událost bez odpovídajícího datovaného žurnálu drží den vytvoření;
  - potvrzený žurnál životního přechodu bez odpovídajícího potvrzeného přechodu obchodu. Pozdější stav
    obchodu nezneplatňuje jeho dřívější potvrzený přechod;
  - vypořádání nebo splatnost s valutou ten den, zapsané až po startu posledního dokončeného průchodu
    rozlišení. Počítá se i průchod zapsaný pod pozdější den, protože po půlnoci rozliší i tento den.
- **Práh zastarání.** Je odvozený, nikdy se nenastavuje samostatně. Průchod kontroluje
  `openbank.treasury.accrual.pass-budget` (20 minut) před každým obchodem i před každým dnem rozlišení.
  Rozpočet tak nemůže překročit o víc než jedno účtování, a `TreasuryCloseJobs.staleAfter` je proto
  rozpočet plus 30 minut. Starší značka znamená, že průchod zemřel, a den se nahlásí jako REFUSED
  (`pass started and never completed`).
- **Hojení.** Úplný průchod (žádný obchod neselhal ani nezbyl) rozlišil i všechny dřívější dny.
  Každý den v okně zpětného pohledu, který je REFUSED nebo nezapsaný, proto znovu zapíše jako RAN,
  ledaže jeho vlastní průchod ještě běží.
- **Zapsáno před potvrzením.** Vypořádání, splatnost a storno zapíšou žurnál ve vlastní transakci,
  jakmile ho ledger přijme, ještě před potvrzením změny stavu obchodu. Když se toto potvrzení vrátí
  zpět, žurnál se přesto započítá, ale den čeká na potvrzení odpovídajícího přechodu. Opakování účtuje pod stejným idempotentním klíčem, dostane zpět
  původní žurnál a najde ho zapsaný.
- **Záměry zaúčtování (#13883).** Před voláním ledgeru zapíšou vypořádání, splatnost a storno řádek
  `deal_posting_intent` (V27) s idempotentním klíčem žurnálu. Uložení obchodu ho dokončí v transakci
  změny stavu. Otevřený záměr znamená, že ledger může držet žurnál, který treasury nepromítlo, a proto
  pozdrží svůj den hodnoty. `PostingIntentReconcileScheduler` (každých 5 minut) si zabere záměry starší
  než `openbank.treasury.posting-intent.grace` (`FOR UPDATE SKIP LOCKED` a zápůjčka, takže dva pody
  nikdy neobnoví tentýž) a znovu spustí příkaz pod stejným klíčem; ledger odpoví původním žurnálem.
  Jménem uloženého aktéra obnoví záměr jen mladší než `max-resume-age` (4 h, podle stáří, ne podle
  kalendáře: den hodnoty si záměr nese sám). Starší eskaluje: zůstane otevřený, drží svůj den a už
  se znovu nezabírá, takže eskalované záměry nemohou vyhladovět ty obnovitelné. Dokud má obchod
  otevřený záměr, akruál přeskočí JEN tento obchod (počítá se jako odložený; ostatní obchody se
  rozliší a průchod doběhne), takže obnovené storno vyrovná přesně to, proti čemu bylo rozhodnuto. Kódované `409 ACCOUNTING_DAY_CLOSED` ledgeru uzavře záměr jako
  REFUSED (žurnál neexistuje), je-li záměr čerstvý nebo starší než grace; každé jiné selhání ho nechá
  otevřený. Služba odmítne start, pokud grace není aspoň čtyřnásobkem jednoho volání ledgeru
  (connect + read timeout, bez opakování).
- **Uvízlý záměr.** `TreasuryPostingIntentStuck` se spustí, když je nejstarší otevřený záměr starší
  než 20 minut (gauge `openbank.treasury.posting_intents.open` a `.oldest_age_seconds`, -1 když je
  nelze přečíst; ověřeno promtoolem). Přečtěte `last_error` na řádku. Buď příkaz obchodu zopakujte přes
  API, které účtuje pod stejným klíčem a záměr dokončí, nebo ho uzavřete:
  `POST /api/v1/treasury/deals/{id}/posting-intent/close` s
  `{"event": "SETTLED|MATURED|REVERSED", "reason": "..."}`. Vyžaduje akci OPA
  `treasury.posting-intent.close` (schvalovatelé, nikdy agent), osobu a čtyři oči: ne aktéra záměru ani
  schvalovatele obchodu. Záměr mladší než grace nebo zabraný reconcilerem vrátí 409
  `POSTING_IN_FLIGHT` (zkuste později); 404 znamená, že otevřený není. Uzavření nastaví
  CLOSED_BY_OPERATOR (kdo a proč zůstává na řádku) a uvolní pozdržení dne. Žurnál přesto může
  existovat, takže pozdější zopakování záměr znovu otevře s PRVNÍM dnem hodnoty; stane se rozhodnutím
  toho, kdo ho zopakoval (jeho aktér a čas), takže grace i čtyři oči platí znovu. Každý přechod se
  připíše do `deal_posting_intent_history`, jejíž triggery odmítají UPDATE, DELETE i TRUNCATE ze
  služby (ne od vlastníka tabulky, který trigger může vypnout). Žurnál, který dorazí po uzavření, se
  tam zapíše jako `JOURNAL_APPLIED_AFTER_CLOSE` s předchozím stavem, započítá do
  `openbank.treasury.posting_intents.applied_after_close`, spustí alert
  (`TreasuryPostingIntentJournalAfterClose`: znovu nahlaste ten den hodnoty) a nikdy se nepřepíše přes uzavření.
- **Provoz.** V režimu `shadow` ledger pozdržení jen počítá. V režimu `enforce` operátor den REFUSED
  posoudí a na stránce konce dne v ledgeru mu udělí výjimku (waiver).
