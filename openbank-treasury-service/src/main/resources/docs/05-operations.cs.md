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
  pražskému účetnímu dni. Než cokoli udělá, označí se v `treasury_close_job_run` (V21) jako
  rozběhnutý. Po skončení zapíše počet posouzených obchodů. Odmítnutí zapíše, když se obchod nepodařilo
  rozlišit, když ledger odpověděl `409 ACCOUNTING_DAY_CLOSED`, když vyčerpal rozpočet a obchody zbyly,
  nebo když průchod selhal. Odmítnutí uzavřeným dnem má vlastní kategorii. Pokud nejde zapsat značka
  rozběhnutí, průchod se nespustí.
- **Jak se den nahlásí.** Je-li rozlišení vypnuté, den je SKIPPED. Pokud pro den žádný průchod
  nedoběhl, nehlásí se nic a ledger den drží v CUTOFF. Jinak rozhoduje poslední průchod: RAN, nebo
  REFUSED se spočtenými důvody. Počet žurnálů je počet řádků `deal_journals` zaúčtovaných v daném
  pražském dni: rozlišení, vypořádání, splatnosti a storna.
- **Pozdržení.** Den drží průchod, který ještě běží. Drží ho také událost
  `treasury.deal.settled|matured|reversed.v1` vytvořená ten den, dokud je v `treasury_outbox` ve stavu
  PENDING, DISPATCHING nebo FAILED. Událost ve stavu DEAD udělá z dne REFUSED
  (`journal event(s) never delivered`).
- **Práh zastarání.** Je odvozený, nikdy se nenastavuje samostatně. Průchod přestane začínat nové
  obchody, jakmile vyčerpá `openbank.treasury.accrual.pass-budget` (20 minut), takže
  `TreasuryCloseJobs.staleAfter` je rozpočet plus 30 minut. Starší značka znamená, že průchod zemřel, a
  den se nahlásí jako REFUSED (`pass started and never completed`). Další úplný průchod téhož dne ho
  nahradí.
- **Provoz.** Den REFUSED kvůli `ACCOUNTING_DAY_CLOSED` znamená, že rozlišení zkusilo účtovat do dne,
  který ledger už uzavřel. V režimu `shadow` ledger pozdržení jen počítá. V režimu `enforce` operátor
  den posoudí a na stránce konce dne v ledgeru mu udělí výjimku (waiver).
