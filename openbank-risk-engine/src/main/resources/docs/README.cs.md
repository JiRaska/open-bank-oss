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

## Likviditní klasifikace zbývajících účtů hlavní knihy

Pět účtů hlavní knihy dosud nebylo pro likviditu klasifikováno, takže LCR, NSFR a jejich limity nebylo možné
vyhodnotit. Nyní jsou klasifikovány v `openbank.risk.liquidity.classification.gl-accounts` (politická rozhodnutí,
#11107):

| Účet | Třída | Zacházení |
|------|-------|-----------|
| 1100 Customer Cash Clearing | `technical-or-clearing` | podle znaménka zůstatku, viz níže |
| 1990 / 1991 FX Position CZK / EUR | `technical-or-clearing` | podle znaménka zůstatku, viz níže |
| 1995 FX Position Counter-Value EUR (CZK) | `technical-or-clearing` | podle znaménka zůstatku, viz níže |
| 2200 Withholding Tax Payable | `other-liability` | závazek splatný v 30denním horizontu: 100% odtok LCR, 0% ASF |

Nová třída `TECHNICAL_OR_CLEARING` (hodnota na drátě `technical-or-clearing`) je určena pro zůstatky, u nichž není
zaznamenána protistrana, splatnost ani směr. Řeší se podle znaménka zůstatku, každá strana nejkonzervativnějším
faktorem, který engine má, takže žádná strana nemůže poměr zlepšit:

- **Debet (aktivum):** zachází se jako s `other-asset`: není HQLA, žádný příliv LCR, 100% RSF.
- **Kredit (pasivum):** zachází se jako s `other-liability`: 100% odtok LCR, 0% ASF. Kreditní zůstatek se nikdy
  nestane zápornou řádkou RSF, která by snižovala požadované stabilní financování.

### Verze sad parametrů

| Sada | Verze | Změna |
|------|-------|-------|
| sada režimu BCBS (`regime: bcbs`, d238/d295) | 4 na 5 | klasifikace GL 1100, 1990, 1991, 1995, 2200 |
| `eu-2015-61-crr2` | 3 na 4 | klasifikace GL 1100, 1990, 1991, 1995, 2200 |

### Testy

`ResidualGlLiquidityClassificationTest` pokrývá novou klasifikaci; aktualizovány jsou aserce verzí sad parametrů
v existujících likviditních testech a v `RiskLiquidityApiIT`.
