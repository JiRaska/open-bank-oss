# Czech pension company: regulatory reporting obligations

Research for ADR-0334 (pension platform, umbrella #12350), issue #12422. Researched 2026-10-10
from public sources only. This note is not legal advice. Every row records how far it was
checked:

- **VERIFIED**: read in the primary legal text at the URL given.
- **REPORTED**: taken from a secondary source or a search summary. The primary text was not read.
- **UNKNOWN**: no public source was found, or the question needs counsel.

The subject is a *penzijní společnost* (PS) under zákon č. 427/2011 Sb. (doplňkové penzijní
spoření, DPS) together with the funds it manages: the *účastnické fondy* and the *transformovaný
fond* (the former penzijní připojištění). DIP (dlouhodobý investiční produkt, §4c ZDP) is a tax
wrapper rather than a licensed product type, so it has no supervisory returns of its own. Its
duties follow the underlying provider and are covered under the tax rows below.

## 1. ČNB supervisory returns: vyhláška č. 425/2012 Sb. (PSP (ČNB) family)

**Basis.** Vyhláška č. 425/2012 Sb., o předkládání informací penzijní společností České národní
bance, issued under §170(1) of zákon 427/2011 Sb. and amended by 476/2017 Sb. and 404/2020 Sb.
Read in the consolidated text effective 1 January 2021 (VERIFIED):
<https://www.cnb.cz/export/sites/cnb/cs/legislativa/.galleries/vyhlasky/vyhlaska_425_2012_uplne_zneni_k_20210101.pdf>.
Whether any amendment after 2021 changed the list is **UNKNOWN**. Check e-Sbírka before relying on it.

| Code | Name | Scope | Period / reference date | Deadline | Status |
|---|---|---|---|---|---|
| PSP (ČNB) 10-12 | Měsíční rozvaha PS/fondu | PS **and** each fund | monthly, last day of month | 20 days after month end (§3(1)(a), §3(2)(a)) | VERIFIED |
| PSP (ČNB) 20-12 | Měsíční výkaz zisku a ztráty PS/fondu (year-to-date) | PS **and** each fund | monthly | 20 days | VERIFIED |
| PSP (ČNB) 30-12 | Hlášení o penzijní jednotce | PS (covers each účastnický fond) | monthly | 20 days | VERIFIED |
| PSP (ČNB) 50-04 | Hlášení o organizační struktuře PS | PS | quarterly, last day of quarter | 30 days (§3(1)(b)) | VERIFIED |
| PSP (ČNB) 31-04 | Hlášení o přijatých a vyplacených prostředcích PS | PS | quarterly (flows year-to-date) | 30 days | VERIFIED |
| PSP (ČNB) 32-04 | Hlášení o kapitálu a kapitálových požadavcích PS (§38 ZDPS) | PS | quarterly | 30 days | VERIFIED |
| PSP (ČNB) 34-12 | Hlášení o skladbě portfolia PS/fondu | PS: quarterly (§3(1)(b)4). Each fund: monthly (§3(2)(c)) | see scope | PS 30 days, fund 20 days | VERIFIED |
| PSP (ČNB) 40-01 | Doplňující informace pro vyhodnocování rizikovosti PS | PS | annual, 31 Dec | 30 days after year end (§3(1)(c)) | VERIFIED |
| Výroční zpráva | Annual report, for the PS and for each fund | PS + each fund | annual | per the accounting law. Sent signed electronically to the ČNB podatelna or datová schránka (§5) | VERIFIED (channel); deadline UNKNOWN |

**Format and transport (§6, VERIFIED).** Returns are electronic *datové zprávy* "in the format and
structure of data files". They go through the remote-access application or user interface of
ČNB's collection system, and each is signed with the uznávaný elektronický podpis of a registered
contact person. Corrections (§7, VERIFIED) are resubmitted "bez zbytečného odkladu" together with
the content of the correction and the reason for it. A correction prompted by the audit of the
year-end figures is due within 20 days of the audit, and any downstream period it affects must be
corrected too.

That collection system is generally referred to as **SDAT**, and its data-file methodology is
published separately as the ČNB "metodika" and "datový model". Status: **REPORTED**. The
cell-level datapoint dictionary for PSP/PEF was **not** read, so this repository does not render
the wire file (see the ADR).

**Data needed.** 10-12 and 20-12 need the trial balance per entity (PS ledger, and one book per
fund). 30-12 needs units outstanding, units issued and cancelled, the unit value and its period
maximum, and fund equity. 31-04 needs year-to-date contributions received by type (participant,
employer, state contribution), payouts by benefit type, counts, and the number of pensioners.
32-04 needs PS capital and capital requirements. 34-12 needs instrument-level holdings (ISIN,
classification, nominal, cost, carrying value, investment-limit mapping). 50-04 needs governance
and shareholder data above 5 %.

## 2. ČNB statistical returns and ECB pension-fund statistics: vyhláška 314/2013 Sb. (PEF (ČNB) family)

**Basis.** Vyhláška č. 314/2013 Sb., o předkládání výkazů ČNB osobami, které náleží do sektoru
finančních institucí, as amended by 217/2018 Sb. (effective 1 January 2019). The amendment cites
**Regulation (EU) 2018/231 (ECB/2018/2)** on the statistical reporting requirements for pension
funds. That is how ECB pension-fund statistics reach Czech DPS funds: ČNB collects the data
nationally and forwards it. Status: VERIFIED (amending text):
<https://www.epravo.cz/top/zakony/sbirka-zakonu/sb/2018/217>.

| Code | Name | Scope | Period | Deadline | Status |
|---|---|---|---|---|---|
| PEF (ČNB) 12-04 | Čtvrtletní bilance aktiv a pasiv PS/fondu PS | PS **and** each fund | quarterly | 30 days after quarter end | VERIFIED |
| PEF (ČNB) 13-04 | Čtvrtletní přehled o úvěrech fondu PS | each fund | quarterly | 30 days | VERIFIED |
| PEF (ČNB) 14-04 | Čtvrtletní přehled o penzijních nárocích fondu PS | each fund | quarterly | 30 days | VERIFIED |
| PEF (ČNB) 15-01 | Roční doplňkové informace o účastnících fondu PS | each fund | annual | 30 January | VERIFIED |

ECB/2018/2 itself defines "pension fund" by the ESA 2010 sector S.129. Whether every Czech
účastnický fond falls into S.129 or into S.124 (investment funds) for ČNB's purposes is
**UNKNOWN**. The 217/2018 text puts the PEF returns on "fondy penzijní společnosti", which in
practice settles that the PEF returns apply. Same transport as §6 above (REPORTED).

## 3. EIOPA / IORP II: not applicable

Directive (EU) 2016/2341 (IORP II) covers *occupational* retirement provision institutions. Czech
DPS is a *personal* voluntary scheme run by a licensed PS, and the Czech Republic has not
transposed IORP into DPS. EIOPA's IORP reporting package therefore does not apply. Status:
REPORTED, the standard position. Counsel should confirm it if the platform ever offers an
occupational scheme.

## 4. MF ČR: state contribution (státní příspěvek)

The monthly claim and settlement of the state contribution, and the return of unduly received
contributions, under §26 of zákon 427/2011 Sb. and its implementing decree. This is already built
in pension-service as F5 (#12396), on the `/funding/operations/state-contribution/*` and
`/claim-runs` endpoints, with its own deadlines endpoint. It is not repeated here. Status: see #12396.

## 5. Tax administration

| Obligation | Basis | Recipient | Frequency / deadline | Format | Status |
|---|---|---|---|---|---|
| Withholding (srážková daň) on lump-sum and pension payouts and on early termination, where the payout includes employer contributions, with the participant's own contributions reduced by the deduction claimed | ZDP §8, §36 (exact provisions: counsel) | správce daně | monthly odvod. Annual *Vyúčtování daně vybírané srážkou* (§38d) by the end of the following month for monthly filing, or annually | EPO XML (GFŘ) | REPORTED. Exact paragraph references need counsel. |
| Clawback of the tax deduction on early termination (dodanění), via the participant's own return and the PS certificate | ZDP §15a | participant (certificate), FÚ | on the event | certificate (already built: `/tax-years/{year}/certificate`) | REPORTED |
| Annual certificate of contributions for the §15a deduction (potvrzení) | ZDP §15a | participant / employer | annual | paper or PDF | REPORTED, built in pension-service |
| Employer contributions above the exempt cap (§6(9)(p)) | ZDP §6 | employer's payroll, not the PS | n/a | n/a | REPORTED |

This platform already has a §38d filing owner, `openbank-tax-reporting-service` (ADR-0180). The
pension payouts withheld at source must reach that same return. That needs a pension withholding
event (follow-up). The return itself is not a new mechanism.

## 6. CRS / FATCA: exempt within limits

Vyhláška č. 108/2016 Sb. §2, under §13d(6) of zákon 164/2013 Sb., lists **doplňkové penzijní
spoření** as an excluded (vyňatý) account while the year's contributions do not exceed the
equivalent of USD 50 000. Penzijní připojištění (the transformed fund) is excluded on the same
terms. Status: REPORTED. Search summary of <https://www.epravo.cz/top/zakony/sbirka-zakonu/sb/2016/108>.
The primary text was not read, and whether it has changed since is UNKNOWN. **Consequence:** the
platform must monitor the yearly contribution total per account against the USD 50 000 threshold,
because an account that exceeds it becomes reportable. That is a control to build, not a return to
file (follow-up). FATCA treatment under the CZ–US IGA (Annex II) is UNKNOWN.

## 7. AML / FAU

A PS is an obliged person under zákon 253/2008 Sb. (§2(1)(b)). Suspicious transaction reports go
to FAU "bez zbytečného odkladu" through MoneyWeb. The fleet's existing AML/STR flow covers this
(aml / sanctions services), and it is event-driven rather than periodic. Status: REPORTED.

## 8. Depositary

Under §116 and following of zákon 427/2011 Sb., each fund has a depositary (depozitář) that
controls the NAV and the unit calculation and reports breaches to ČNB. Those duties fall on the
**depositary**, not the PS. The PS only has to supply the data. Status: REPORTED. No return is
filed by the PS.

## 9. Publication duties (NAV, statute, key information)

| Obligation | Basis | Frequency | Status |
|---|---|---|---|
| Publish the unit value (hodnota penzijní jednotky) of each účastnický fond | ZDPS (publication duty) | each valuation day | REPORTED |
| Publish the statute and its changes, annual and semi-annual reports, and key information (sdělení klíčových informací) | ZDPS | on change / annual / semi-annual | REPORTED |
| PRIIPs KID | Reg. (EU) 1286/2014. DPS is a pension product **outside** PRIIPs scope (Art. 2(2)(e)-(g)). The Czech sdělení klíčových informací applies instead | n/a | REPORTED |
| SFDR pre-contractual and periodic disclosures (Art. 8/9 templates, PAI statement) | Reg. (EU) 2019/2088. A PS is a financial market participant offering a "pension product" (Art. 2(1)(g)) | periodic with the annual report, PAI statement by 30 June | REPORTED |

## 10. What the platform builds from this

- **Built now (issue #12422):** the PSP (ČNB) and PEF (ČNB) catalogue as versioned jurisdiction
  data, plus the return lifecycle (assemble → validate → four-eyes approval with content
  attestation → submission reference), deadlines and a breach gauge, in
  `openbank-tax-reporting-service` (see the ADR).
- **Placeholder:** the SDAT wire file. The datapoint dictionary was not verified, so no renderer
  is bound, and the API says so.
- **Follow-ups:** read models in pension-service and pension-fund-service that feed the data
  ports, a pension withholding event into §38d, a CRS threshold monitor, and admin-ui listing.
