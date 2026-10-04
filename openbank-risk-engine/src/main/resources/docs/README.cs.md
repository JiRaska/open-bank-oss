# openbank-risk-engine — Dokumentace

> **Co to je:** read-only engine pro riziko bilance (ADR-0313, ADR-0314). Snapshot run zmrazí obratovou předvahu hlavní knihy, podúčty vkladů a smluvní instrumenty (úvěry, obchody treasury na peněžním trhu) k určitému datu; každé rizikové číslo se odvozuje z runu na požádání a neukládá se. **Co to NENÍ:** evidenční systém. Zapisuje jen zmrazené runy a operátorem nahrané sady křivek.

Tuto dokumentaci publikuje služba na management endpointu `/q/openbank/docs` (Docs-as-Service, ADR 0019).

## TL;DR

- **Technologie:** Kotlin / Quarkus 3.x / PostgreSQL (migrace Flyway `V1`..)
- **Porty:** 8159 (aplikace), 8085 (management)
- **Autorizace:** čtení pro operátory, `ROLE_RISK` a `ROLE_FINANCE`; zápisy (snapshot runy, sady křivek) pouze pro lidi s rolí `ROLE_RISK`, `ROLE_OPERATOR` a `ROLE_ADMIN`.

## Rizikové čtecí endpointy

Všechna čtení visí na snapshot runu: `GET /api/v1/risk/snapshots/{id}/...` — `positions`, `instruments`, `cash-flows`, `irrbb`, `liquidity`, `liquidity-forecast`, `capital`, `min-reserves`, `limits`. Run, který není TIED_OUT, vrací 409; neznámý run nebo sada křivek vrací 404.

## IRRBB (`GET /snapshots/{id}/irrbb`)

Repricingová mezera, ΔEVE v šesti scénářích BCBS d368, ΔNII za 12 měsíců při paralelním posunu nahoru/dolů, nejhorší případ a předpoklady, ze kterých čísla vznikla.

- **Sada křivek.** `curveSetId` je nepovinný. Bez něj se použije nejnovější sada zaznamenaná přesně k datu runu, nikdy ne sada sousedního dne; není-li žádná, vrací se 400. Sada s jiným datem je vždy 400.
- **Obchody treasury na peněžním trhu.** Umístění, vklady u ČNB, přijaté půjčky a lombard ČNB vstupují do knihy jako obchody s pevnou sazbou: celá jistina se přeceňuje v den splatnosti (aktiva kladně, pasiva záporně, tak jak je znaménkuje snapshot) a toky jsou jistina plus úrok ACT/360 při splatnosti. Jsou ZAHRNUTY v mezeře, ΔEVE i ΔNII. Pole `treasury[]` rozpadá jejich podíl podle měn (počet obchodů, umístění, půjčky jako velikost, základní PV a ΔEVE podle scénáře); je to rozpad, nikdy dodatečný příspěvek. Pro run bez obchodů je prázdné.
- **Velikosti šoků.** Dodaná konfigurace obsahuje pro CZK i EUR paralelní 200 / krátký 250 / dlouhý 100 bp (nařízení v přenesené pravomoci (EU) 2024/856, příloha, část A). Měna bez velikostí je uvedena v `shockNotConfigured` a scénáře nedostane.
- **Test odlehlé hodnoty (supervisory outlier test).** Tier 1 je `tier1Capital` od volajícího, pokud je zadán, jinak Tier 1 z vlastních zdrojů runu (CZK), tedy stejné číslo, které používá limit `irrbb-eve-outlier`; hodnota z vlastních zdrojů se použije jen pro agregát v CZK. Práh a včasné varování pocházejí z deklarovaného limitu `irrbb-eve-outlier` (dodáno 15 % / 12 %) a výsledek nese `status` (`OK`, `EARLY_WARNING`, `BREACH`, `NOT_EVALUABLE`), `tier1Source` (`caller` nebo `own-funds`) a `tier1Gap` (proč nelze Tier 1 použít). Chybějící údaj je `NOT_EVALUABLE`, nikdy `OK`.
- **Datové mezery.** `dataGaps[]` uvádí, co čísla nezachycují, se stabilním `code`: `CURVE_EXTRAPOLATED_FLAT` (toky po posledním pilíři křivky se oceňují poslední nulovou sazbou drženou konstantně; uvádí počet toků za pilířem a jejich základní PV), `PREPAYMENT_NOT_MODELLED`, `NMD_BEHAVIOUR_SIMPLIFIED`, `COMMERCIAL_MARGIN_INCLUDED` a `INSTRUMENTS_NOT_PROJECTED`. Poslední z nich se vyvolá jen pro druhy instrumentů, které projekce stále nečte (úvěry a obchody na peněžním trhu se čtou), a detail je pojmenuje. Jde o modelová zjednodušení nebo chybějící vstupy, ne o chyby; čísla se přesto vypočtou.

## Kontrakt

Kontrakt API je `openapi.yaml` (`info.version` se řídí ADR-0048). Výše popsané změny IRRBB jsou aditivní (1.23.0 a 1.24.0).

## Nostro zůstatky ve standardizovaném výpočtu kapitálu pro úvěrové riziko

Účty hlavní knihy 1001 (nostro CZK) a 1002 (nostro EUR) jsou v
`openbank.risk.capital.sa.classification.gl-accounts` klasifikovány jako `nostro` (dříve `bank`). Nová třída
`NOSTRO` (`CapitalGlClass`, hodnota na drátě `nostro`) se chová takto:

- **Debetní zůstatek:** pohledávka za korespondenční bankou, vážená stejně jako třída `bank` (expoziční třída BANK,
  rizikovou váhu určuje nakonfigurovaný `bankScraGrade`).
- **Kreditní zůstatek:** nostro je přečerpáno, tedy dlužíme korespondentovi. Jde o závazek, nikoli úvěrovou
  expozici, takže nevytváří žádnou expozici a kapitálové poměry kvůli němu už nejsou nevyhodnotitelné (obecné
  pravidlo pro kreditní zůstatek na expozičním účtu se na něj neuplatní). S jinou expozicí se nezapočítává.

Jde o politické rozhodnutí (#11107).

### Verze sad parametrů

| Sada | Verze | Změna |
|------|-------|-------|
| `bcbs-d424-sa` | 2 na 3 | GL 1001/1002 klasifikovány jako `nostro` |
| `eu-crr3-sa` | 1 na 2 | GL 1001/1002 klasifikovány jako `nostro` |

### Testy

Změna přidává případy do `SnapshotServiceTest` k párování úvěrové knihy s GL 1200 (prázdná kniha při úvěrech v hlavní knize je nespárovaná; prázdná kniha při úvěrech s nulovým součtem se spáruje; selhání čtení úvěrové knihy snapshot selže a nic se neuloží) a rozšiřuje
`CreditRiskCapitalTest` o debetní a kreditní nostro zůstatky.
