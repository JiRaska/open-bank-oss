# Architektura risk enginu

Služba počítá rizikové ukazatele ze zmrazeného bilančního snapshotu k určenému datu. Není účetní knihou ani druhým systémem záznamu. Aplikační vrstva řídí vytvoření snapshotu, kontrolu návaznosti na ledger, výběr křivek a výpočty; doménová vrstva obsahuje model instrumentů a peněžních toků; adaptéry čtou zdrojová data a ukládají manifest běhu.

## Tok dat

1. Požadavek určí datum `as-of` a časový řez záznamů. Služba čte hlavní knihu, podúčetní zůstatky a smlouvy o úvěrech přes API jejich vlastníků. Treasury money-market obchody vstupují do stejného modelu instrumentů.
2. Hash vstupů a odkazy na zdroje identifikují zmrazený běh. `PositionBuilder` převede zdroje na podepsané pozice a typované instrumenty.
3. Kontrola `tie-out` porovná pozice podle GL účtu a měny s ledgerem. Nesouhlasný běh se uchová pro analýzu, ale ukazatele z něj nelze publikovat; čtecí endpointy vrací 409.
4. Projektory převedou podporované instrumenty na datované toky jistiny a úroků. Křivky a scénáře pak při čtení počítají IRRBB a likviditu. Toky a ukazatele nejsou samostatné autoritativní záznamy.
5. PostgreSQL ukládá běhy, pozice a křivky dodané operátorem. Strukturu určují migrace v `src/main/resources/db/migration`.

Aktuálně implementované HTTP API popisuje `src/main/resources/openapi.yaml`. ADR-0313 a ADR-0314 popisují i cílový stav; jejich částečné dodání neznamená, že všechny plánované zdroje již běží.

## Hranice důvěry

Role oddělují čtení a zápisy operátorů. Snapshoty a křivky vytváří lidský operátor; služba nepíše přímo do databáze ledgeru. Provenience vstupů a výsledek kontroly zůstávají u běhu. Chybějící křivka nebo účinný parametr vede podle kontraktu k explicitnímu nevyhodnotitelnému výsledku, nikoli k domyšlené nule.
