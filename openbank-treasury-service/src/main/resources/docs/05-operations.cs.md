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
jednou na každý upload či běh odsouhlasení. Storno vždy vyrovnává účty zapsané na žurnálech obchodu
(`deal_journals.resolved_accounts`), ať mapa dnes říká cokoli.

**Rollback.** S příznakem `true` řádky účet neobsahují, takže starší ledger (pod `V32`) odmítne každý
řádek. Samotnou konfigurací se vrátit nelze: nejdřív nastavte `OPENBANK_TREASURY_LEDGER_POST_BY_CLASS=false` a znovu nasaďte Treasury,
teprve potom vraťte ledger. Pokud se ledger vrátí jako první, každé zaúčtování selže, dokud se Treasury
znovu nenasadí.

## Sdílené závislosti sestavení

Soubor `build.gradle.kts` této služby neuvádí závislosti, které sdílí každá Quarkus služba: Quarkus BOM, quarkus-kotlin, RESTEasy Reactive s Jacksonem, SmallRye Health a OpenAPI, OIDC, registr Micrometer Prometheus a config-yaml a testovací knihovny quarkus-junit5, AssertJ a REST Assured. Deklaruje je konvenční plugin `openbank.quarkus-service` v `build-logic`. Výsledná classpath je beze změny. `build.gradle.kts` uvádí jen to, co služba přidává navíc.
