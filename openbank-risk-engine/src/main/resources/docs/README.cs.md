# openbank-risk-engine

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

## Verze sad parametrů

| Sada | Verze | Změna |
|------|-------|-------|
| sada režimu BCBS (`regime: bcbs`, d238/d295) | 4 na 5 | klasifikace GL 1100, 1990, 1991, 1995, 2200 |
| `eu-2015-61-crr2` | 3 na 4 | klasifikace GL 1100, 1990, 1991, 1995, 2200 |

## Testy

`ResidualGlLiquidityClassificationTest` pokrývá novou klasifikaci; aktualizovány jsou aserce verzí sad parametrů
v existujících likviditních testech a v `RiskLiquidityApiIT`.
