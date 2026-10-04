# openbank-risk-engine

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

## Verze sad parametrů

| Sada | Verze | Změna |
|------|-------|-------|
| `bcbs-d424-sa` | 2 na 3 | GL 1001/1002 klasifikovány jako `nostro` |
| `eu-crr3-sa` | 1 na 2 | GL 1001/1002 klasifikovány jako `nostro` |

## Testy

Změna přidává případy do `SnapshotServiceTest` k párování úvěrové knihy s GL 1200 (prázdná kniha při úvěrech v hlavní knize je nespárovaná; prázdná kniha při úvěrech s nulovým součtem se spáruje; selhání čtení úvěrové knihy snapshot selže a nic se neuloží) a rozšiřuje
`CreditRiskCapitalTest` o debetní a kreditní nostro zůstatky.
