# CZ state contribution (státní příspěvek) to supplementary pension savings

Research note for ADR-0334, issue #12382. It records how the state contribution flows between a
pension company (penzijní společnost) and the Ministry of Finance (Ministerstvo financí ČR, "MF"),
so the pension-service CZ claim channel can be checked against it. **Nothing here is legal advice;
the jurisdiction pack stays `REQUIRES_LEGAL_REVIEW`.**

All entries were retrieved on **2026-10-09**. The text is summarised, not quoted. Labels:

- **VERIFIED**: taken from the consolidated text of the act.
- **REPORTED**: taken from a secondary source (a professional newsletter or the press) and not
  checked against the act.
- **UNKNOWN**: no public source found. The implementation uses a documented placeholder.

Sources:

- [S1] Act 427/2011 Coll., on supplementary pension savings (ZDPS), consolidated text:
  https://www.zakonyprolidi.cz/cs/2011-427
- [S2] Forvis Mazars payroll newsletter 2024, "Změny státního příspěvku ve III. penzijním pilíři":
  https://www.forvismazars.com/cz/cs/prehledy/newslettery/newslettery-ze-mzdove-oblasti/payroll-newsletter-2024/payroll-newsletter-archiv-2024/zmeny-statniho-prispevku-ve-iii.-penzijnim-piliri
- [S3] Finance.cz, "Šest pravidel, která se změnila u spoření na důchod":
  https://www.finance.cz/549596-sporeni-na-duchod-zmeny/
- [S4] Forvis Mazars, about Novinky.cz of 13 June 2024 (pensioners lose the contribution):
  https://www.forvismazars.com/cz/cs/prehledy/forvis-mazars-v-mediich/archiv-2024/posledni-statni-prispevky-na-penzijni-sporeni

## 1. Eligibility

| # | Fact | Status | Source |
|---|---|---|---|
| E1 | The participant is entitled only if no old-age pension from the pension insurance system has been granted to them. | VERIFIED | [S1] §13(1) |
| E2 | The participant must also either have permanent residence in the CZ, or live in another EU/EEA state while covered by Czech pension insurance or Czech public health insurance. | VERIFIED | [S1] §13(1) |
| E3 | The participant must give the company a birth number (rodné číslo). Without one, the health-insurance number is used. | VERIFIED | [S1] §13(2) |
| E4 | The participant must report changes to these facts in writing without undue delay. | VERIFIED | [S1] §13(3) |
| E5 | §13 sets no minimum age for the state contribution. The pack's `minAge: 18` concerns contract opening, not this incentive. | VERIFIED (absence in §13) | [S1] |
| E6 | The exclusion of old-age pensioners applies from 1 July 2024. About 750,000 of 4.2 million participants are affected, per the MF figure quoted in the press. | REPORTED | [S2], [S4] |
| E7 | Transformed funds (penzijní připojištění, Act 42/1994) follow the same amounts and pensioner exclusion from 1 July 2024. | REPORTED | [S2], [S3] |

## 2. Amount

| # | Fact | Status | Source |
|---|---|---|---|
| A1 | The contribution is due for each calendar month in which the participant meets §13(1) and paid at least 500 CZK in time. | VERIFIED | [S1] §14(1) |
| A2 | For a monthly participant contribution of 500–1,699 CZK, the state contribution is 20 % of it. | VERIFIED | [S1] §14(2)(a) |
| A3 | For a monthly participant contribution of 1,700 CZK or more, the state contribution is a flat 340 CZK, which is the monthly maximum. | VERIFIED | [S1] §14(2)(b) |
| A4 | If the participant pays for more than one month at once, the monthly amount is the average. | VERIFIED | [S1] §14(3) |
| A5 | The contribution is rounded down to whole crowns for the calculation. | VERIFIED | [S1] §14(4) |
| A6 | Until 30 June 2024 the rules were: 300 CZK minimum, a maximum of 230 CZK reached at 1,000 CZK. | REPORTED | [S2], [S3] |
| A7 | Employer contributions do not count towards the participant contribution. | VERIFIED | [S1] §14 counts the participant contribution and §16(3) lists the employer contribution separately |

Golden values for A2, A3 and A5: 499 → 0; 500 → 100; 1,000 → 200; 1,234 → 246 (246.8 rounded
down); 1,699 → 339 (339.8 rounded down); 1,700 → 340; 5,000 → 340.

## 3. Claim (žádost o státní příspěvek)

| # | Fact | Status | Source |
|---|---|---|---|
| C1 | The **company** applies to **MF** for all its eligible participants together, electronically with remote access. Participants never apply themselves. | VERIFIED | [S1] §16(1) |
| C2 | Filing is **quarterly**. The company files in the calendar month that follows each calendar quarter, using data from the state information system for supplementary pension savings. Entitlement is still computed per month (A1). | VERIFIED | [S1] §16(2) |
| C3 | The application carries a header: the company's name and ID number (IČO), the year and the quarter. | VERIFIED | [S1] §16(3) |
| C4 | Each participant line carries: identity and birth data, postcode of residence, EU state of residence, contract number and dates, termination data, the savings period, whether the employer contributes, the contribution amounts, the requested state contribution, suspension or deferral periods, and transfer data. | VERIFIED (field list); field codes UNKNOWN | [S1] §16(3) |
| C5 | An incomplete or incorrect application is corrected by the company, on its own initiative or at MF's request. The correction goes with a **later** quarterly application. | VERIFIED | [S1] §16(4) |
| C6 | The act has no formal rejection procedure. A line MF does not pay is handled through correction (C5) or a return (R1). | VERIFIED (absence in §§16–18) | [S1] |

## 4. Payment by MF

| # | Fact | Status | Source |
|---|---|---|---|
| P1 | MF pays for each calendar quarter in **one aggregate payment** into the company's account at its depositary. | VERIFIED | [S1] §18(1) |
| P2 | Payment is due by the end of the **second month after the quarter**. Time limits do not run for lines that are being corrected. | VERIFIED, needs legal review: the reading of "second month" comes from a machine summary of §18(1) | [S1] §18(1) |
| P3 | MF gives participant-level data to the company on request, on paper or electronically. Unprompted, it gives data only for processing applications and return reports. The per-line split of the aggregate payment therefore arrives as a processing result. | VERIFIED | [S1] §15(4) |

## 5. Returns (vratky)

| # | Fact | Status | Source |
|---|---|---|---|
| R1 | **Unlawfully received** contribution (for example, ineligibility found later): the company returns it by the end of the calendar month in which one month has passed since it found the error. It must also return within 8 days of a binding MF decision. MF's right lapses 10 years after payment. | VERIFIED | [S1] §18(2) |
| R2 | **Unused** contribution when the contract ends (surrender/odbytné, or no transfer requested): returned by the end of the calendar month in which **six months** have passed since the contract ended. The same 8-day rule and 10-year limit apply. | VERIFIED | [S1] §18(3) |
| R3 | The company files a **monthly return report** electronically by the **10th day** of each month. It is prepared from the state information system. | VERIFIED | [S1] §18(4)–(5) |
| R4 | MF processes the report and sends the result electronically by the **20th day of the following month**. | VERIFIED | [S1] §18(6) |
| R5 | The company returns the money by the end of the month in which it receives the result. It must time its reports so that R1 and R2 deadlines are met. | VERIFIED | [S1] §18(7) |

## 6. Technical specification and transport

| # | Fact | Status | Source |
|---|---|---|---|
| T1 | The act requires electronic exchange with remote access but sets no file format. | VERIFIED (absence) | [S1] §§16, 18 |
| T2 | No public MF technical specification (schema, file naming, codes, channel) for the DPS state-contribution exchange was found on mfcr.cz or in the searched decrees. The similar building-savings exchange has published technical conditions in a decree annex (XML, fixed file names). The DPS exchange presumably has non-public conditions agreed between MF and the companies. | **UNKNOWN** | search 2026-10-09 |
| T3 | No official MF rejection reason codes were found. | **UNKNOWN** | — |

## 7. Consequences for the implementation

- Pack `cz-dps-v1`: rate 0.20, minimum 500 and cap 340 match A1–A3 (VERIFIED). A5 (rounding
  down to whole CZK) is now in the pack (`roundDownToUnit: 1`) and in the engine.
- Claims stay **per month** (A1) but are **filed per quarter** (C2) in the month after the quarter.
  Late lines go with a later application (C5).
- The aggregate payment (P1) is reconciled per line. A line paid below the claimed amount is a
  partial payment. A line not paid is a rejection with a reason code. The codes are placeholders
  (T3).
- Returns are monthly reports due by the 10th (R3). R1 and R2 give due dates, and a return is
  settled after MF's result (R5).
- Wire format and transport are **placeholders** (T2): the `cz-mf-state-contribution-v1` text
  format, behind the transport-agnostic `StateAgencyGateway` port. The production gateway only
  hands the document to an operator. It transmits nothing and claims no delivery.
- Identity fields (C4: birth number, postcode, EU state) are not held by pension-service. The
  placeholder line carries the participant party id instead. Resolving the real identity data is
  a follow-up.
