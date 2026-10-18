# 05 — Provoz

## Příjem XML camt.053

Treasury zpracovává nedůvěryhodné soubory camt.053 pomocí `SecureXml`. Jeho DOM builder odmítne deklaraci DOCTYPE a externí entity ještě před vytvořením dokumentu. Odmítnutý či chybný soubor opravte u zdroje; nevypínejte ochranu parseru kvůli jeho přijetí. Služba neprovádí síťové načítání entit uvedených v souboru.

## Sdílené závislosti sestavení

Soubor `build.gradle.kts` této služby neuvádí závislosti, které sdílí každá Quarkus služba: Quarkus BOM, quarkus-kotlin, RESTEasy Reactive s Jacksonem, SmallRye Health a OpenAPI, OIDC, registr Micrometer Prometheus a config-yaml a testovací knihovny quarkus-junit5, AssertJ a REST Assured. Deklaruje je konvenční plugin `openbank.quarkus-service` v `build-logic`. Výsledná classpath je beze změny. `build.gradle.kts` uvádí jen to, co služba přidává navíc.
