# CZ pension (DPS) and DIP onboarding: legal duties, market benchmark, questionnaire design

> **Not legal advice. The CZ question set is REQUIRES_LEGAL_REVIEW.**
> Research date: 2026-10-09. All sources accessed 2026-10-09.
> Labels: **VERIFIED** = read in the primary/official source during this research.
> **REPORTED** = taken from a secondary source or from general knowledge of the instrument, not re-read
> in the primary text today. Where nothing was found we say **UNKNOWN**; nothing here is invented.

## 0. Summary

- The ZDPS (zákon č. 427/2011 Sb.) has its own suitability-style regime. It is not MiFID, but it has a
  similar shape: collect information (§ 136(1)), record needs and the reasons for the recommendation
  (§ 130(4)), warn on a mismatch (§ 136(3)), and handle refusal (§ 136(4)), with a reliance safe-harbour
  (§ 137).
- A participant **may** choose a riskier strategy against the recommendation. The company must warn,
  recommend a more suitable strategy and explain the risks. If the participant insists, it proceeds.
  The exception is an alternative participant fund ("alternativní účastnický fond"), where the company
  is not obliged to comply.
- **No 2024 amendment introduced a lifecycle or default strategy.** The "2024 lifecycle amendment"
  premise in the brief did not hold up. The current law has only the pre-retirement de-risking rule
  (§ 114: move savings to the conservative fund at least 5 years before pension age, with a 60-day
  notice and an opt-out). A mandatory lifecycle strategy, offered as the default, is in the government
  bill "Lepší penzijko". The government approved it on 2026-08-24; it is proposed to take effect on
  2027-01-01 and is **not yet law** as of the research date.
- DIP (from 2024-01-01) is a MiFID product delivered by investment firms under ZPKT. Full MiFID II
  suitability and appropriateness apply, including the sustainability-preference step.

## 1. Legal requirements

### 1.1 ZDPS — zákon č. 427/2011 Sb., o doplňkovém penzijním spoření

Source: https://www.zakonyprolidi.cz/cs/2011-427 (consolidated version 20, in force 2026-08-01).

| # | Duty | Section | Status |
|---|------|---------|--------|
| 1 | Before a contract is concluded and before any change to it, the company records the participant's requirements and needs (based on what the participant says) and the reasons for the strategy it recommends. This is done in writing, and the participant gets a copy. | § 130 odst. 4 | **VERIFIED** (text read; heading of § 130 not visible in the extract, number inferred from position before § 131) |
| 2 | Before the contract, the company obtains information on (a) financial knowledge and experience, (b) knowledge and experience of the instruments the funds invest in, (c) investment risk tolerance and preferences, (d) savings goals and strategy preferences. Financial situation is **not** listed as a separate category, unlike MiFID. | § 136 odst. 1 písm. a–d | **VERIFIED** |
| 3 | Mismatch warning: if the contract or the chosen strategy does not match the information, goals or experience, the company warns, recommends a more suitable strategy, and explains the risks if the participant insists. It need not comply where an alternative participant fund is involved. | § 136 odst. 3 | **VERIFIED** |
| 4 | Refusal, or plainly incomplete or false information: the company states that it cannot assess suitability, recommends a more suitable option or advises against concluding the contract, and complies if the participant insists (again with the alternative-fund exception). | § 136 odst. 4 | **VERIFIED** |
| 5 | Reliance: the company may rely on the information provided unless it knew, or should have known, that it was incomplete, inaccurate or false. | § 137 | **VERIFIED** |
| 6 | Published pension recommendations: facts must be kept apart from opinions, forecasts labelled, the author identified and conflicts disclosed. | §§ 138–139 | **VERIFIED** |
| 7 | The participant chooses the savings strategy in the contract and may change it later. "Strategie spoření" is defined as the allocation of the participant's money across participant funds. | § 5 odst. 7; § 3 písm. a) | **VERIFIED** |
| 8 | The contract must not be unclear, misleading, incomplete or contrary to the participant's interest. | § 5 odst. 3 | **VERIFIED** |
| 9 | A strategy change may be charged at most CZK 500, and one change per calendar year is free. | § 61 odst. 1 písm. a), odst. 2; § 62 odst. 4 | **VERIFIED** |
| 10 | Mandatory conservative fund: every company runs one (§ 94(1)), with an investment universe and limits (§ 98). | §§ 94, 98 | **VERIFIED** |
| 11 | Pre-retirement de-risking: at least 5 years before the state pension age, the company moves the funds to the conservative fund (or an equal or lower-risk fund) and directs new contributions there. Written notice is due at least 60 days ahead; the participant may refuse or choose another fund. | § 114 odst. 1, 3, 4 | **VERIFIED** |
| 12 | Termination by the participant: notice period of at most 1 calendar month. | § 6 | **VERIFIED** |
| 13 | Withdrawal (odstoupení) for distance contracts: nothing was found in ZDPS. The general consumer-law distance financial-services rules (Civil Code, §§ 1841 ff. — 14-day withdrawal) are the likely basis. | — | **REPORTED / needs legal review** |
| 14 | Latest amendments: the history lists 417/2024 Sb., and a law 128/2026 Sb. (Sbírka dated 2026-08-01) appears on mf.gov.cz. Its PDF could not be text-extracted, so its content is **UNKNOWN**. | — | **REPORTED** |

### 1.2 Lifecycle / default strategy ("Lepší penzijko")

Source: Ministry of Finance press release, 2026-08:
https://mf.gov.cz/cs/ministerstvo/media/tiskove-zpravy/2026/vlada-schvalila-lepsi-penzijko-64961 — **VERIFIED (official, but a bill, not law)**.

- Lifecycle strategy: the dynamic/conservative mix is set by age or expected saving horizon, within
  statutory limits, and de-risks gradually toward retirement.
- Under-50s: at least 75 % in dynamic investments. Up to 10 % may go to the alternative fund.
- It is offered as the default to existing and new clients. Clients may still choose their own
  allocation. Automatic enrolment of employees was discussed and dropped.
- Fees: the performance fee is abolished and the management fee capped at 0.5 % (the alternative fund
  is excluded from both).
- Status: the government approved the bill on 2026-08-24 and sent it to the Chamber of Deputies, with
  proposed effect from 2027-01-01. Secondary reporting (praceamzda.cz, finance.cz) adds a 40 % state
  contribution for young savers, partial early withdrawal for ages 18–36, and the wind-down of
  transformed funds by 2036 — **REPORTED**.
- **Design impact:** build the strategy engine so that "lifecycle default" is a feature flag that can
  be switched on when the law passes. The § 114 de-risking already in force applies today.

### 1.3 DIP — dlouhodobý investiční produkt

- Introduced with effect from 2024-01-01 as a tax-supported retirement product (§ 15a ZDP, "produkt
  spoření na stáří"). Sources disagree on the amending act (462/2023 vs 349/2023 Sb.) — **REPORTED**
  (epravo.cz, dauc.cz).
- Product rules sit in ZPKT (zákon č. 256/2004 Sb.) § 134g ff., according to a provider's product
  terms (investona.cz) — **REPORTED**.
- Because DIP is delivered as an investment service by an investment firm or bank, the standard MiFID
  II conduct rules apply as transposed in ZPKT. That means suitability when advice or portfolio
  management is given, and appropriateness otherwise, plus target-market checks — **REPORTED**
  (inference from the instrument; no CZ source states it explicitly).

### 1.4 MiFID II and Delegated Regulation 2017/565 (all REPORTED: not re-read today)

- **Art. 25(2) MiFID II:** for advice or portfolio management, obtain knowledge and experience,
  financial situation including **ability to bear losses**, and investment objectives including **risk
  tolerance** and **sustainability preferences**; recommend only suitable products.
- **Art. 25(3):** for other services, an appropriateness test of knowledge and experience, with a
  warning if the product is inappropriate or if information is missing.
- **Del. Reg. 2017/565:** Art. 54 (suitability assessment, reliance, no recommendation if information
  is insufficient, suitability report), Art. 55 (common provisions), Art. 56 (appropriateness).
- **Del. Reg. (EU) 2021/1253**, applicable since 2022-08-02, added sustainability preferences.
  New Art. 2(7) defines them as the client's choice whether, and to what extent, to include:
  (a) a minimum proportion of **Taxonomy-aligned** investments;
  (b) a minimum proportion of **SFDR Art. 2(17) sustainable investments**;
  (c) products that **consider principal adverse impacts (PAI)**, with the client able to specify which.
  Preferences are gathered only after suitability has been established on the other criteria. If no
  product matches, the client may adapt their preferences, and this must be documented.

### 1.5 ESMA guidelines (REPORTED)

- **ESMA35-43-3172 (2022), suitability guidelines.** The design-relevant points:
  - Do not rely on self-assessment ("do you understand bonds?"). Use objective questions.
  - Questionnaire design: plain language, no leading questions, no pre-ticked answers, explain why the
    information is needed.
  - Run consistency checks across answers (for example, a short horizon combined with a high-risk
    appetite).
  - Assess **loss capacity** in money terms, not only as attitude.
  - Take reasonable steps to make sure the information is reliable.
  - Keep information up to date, with periodic refresh and a trigger on material change.
  - Ask about sustainability preferences in a neutral, granular way, after the core profile.
  - Keep records and a suitability report.
- **ESMA appropriateness / execution-only guidelines (ESMA35-43-3006, 2023):** the same principles
  for the knowledge-and-experience test — avoid self-assessment, give a clear warning, and do not let
  clients retake the test until they pass.

### 1.6 ČNB

- No specific ČNB interpretative opinion (výkladové stanovisko / úřední sdělení) on the DPS
  questionnaire was found in this research — **UNKNOWN**. ČNB supervises compliance with §§ 130–137
  ZDPS and with ZPKT suitability. Check the cnb.cz Q&A pages for DPS and the investment-services
  sections before go-live.

## 2. Market benchmark (CZ)

Evidence is thin. Only what was found is listed; everything else is UNKNOWN.

| Provider | Online? | Questionnaire / strategy | BankID | Status |
|---|---|---|---|---|
| UNIQA penzijní společnost | Mainly branches and external partners; the online app is a supplementary channel. | After basic parameters, the app moves to a pension questionnaire covering needs, risk tolerance and preferences. | UNKNOWN | REPORTED (mesec.cz / company accessibility statement) |
| Conseq penzijní společnost | Yes. The "ZaVodou" project uses an AI chatbot to guide the sign-up. | Contract terms let the participant refuse the investment questionnaire, consistent with § 136(4). | UNKNOWN | REPORTED (cc.cz; conseq.cz terms PDF) |
| KB Penzijní společnost | Contract can be concluded in the bank app (with a promotional bonus). | UNKNOWN | UNKNOWN (likely bank-app login) | REPORTED (finmag.cz) |
| Rentea (Partners group) | Has launched online sign-up; previously advisers only. | UNKNOWN | UNKNOWN | REPORTED |
| ČSOB penzijní společnost | UNKNOWN | Offers a "dynamický zodpovědný" (ESG) participant fund. | UNKNOWN | REPORTED |
| NN, Allianz, Penzijní společnost ČS, Generali/ČPP | UNKNOWN | UNKNOWN | UNKNOWN | — |
| DIP: Portu, Fondee, Conseq DIP, bank DIPs | Robo-advisers onboard online with a MiFID suitability questionnaire (general knowledge). | Exact step and question counts: UNKNOWN | UNKNOWN | REPORTED |

Observations: no provider's step count or question count could be confirmed. Transfer-in UX (moving
from another pension company) was not documented publicly. A hands-on mystery-shop is needed before
making any "best in class" claim.

## 3. Design implications for our questionnaire

1. **Two regimes, one engine.** The DPS track implements ZDPS §§ 130(4), 136, 137. The DIP track
   implements MiFID suitability or appropriateness. Shared question bank, regime-specific rules and
   outputs.
2. **Collect everything § 136(1) a–d requires:** financial knowledge, instrument knowledge and
   experience, risk tolerance, goals and strategy preference. For DIP, add financial situation and
   loss capacity.
3. **Derive the horizon; do not ask for it.** Compute it from the birth date (from KYC or BankID
   prefill) and the statutory pension age. This feeds the § 114 five-year de-risking date and a future
   lifecycle glide path.
4. **Loss capacity in CZK.** Ask "what fall in value, in CZK, could you absorb without changing your
   plans?" and show the CZK impact of each strategy's historical drawdown on the user's projected
   balance. Do not ask only for a percentage or a self-rating.
5. **Objective knowledge questions.** No "do you understand…?" self-assessment. Use 2–3 short factual
   items (for example, which fund can fall in value), per ESMA.
6. **Consistency checks.** Flag contradictions — for example, a horizon under 5 years with a dynamic
   preference, a loss capacity of 0 with high risk tolerance, or no experience with a choice of the
   alternative fund — and ask a clarifying question before scoring.
7. **Recommendation plus written reasons** (§ 130(4)). Generate a stored, versioned record of needs,
   the recommended strategy and the reasons. Deliver a copy (PDF or in-app document) to the participant.
8. **Warning and acknowledgement flow** (§ 136(3)). If the chosen strategy deviates from the
   recommendation, show the warning, the more suitable alternative and the risks, then capture an
   explicit acknowledgement (timestamp, text version) and proceed. **Block or require an extra
   decision for the alternative participant fund**, since the company need not comply there.
9. **Refusal path** (§ 136(4)). Allow skipping the questionnaire. Show the "cannot assess suitability"
   notice and a recommendation (for example, the conservative or lifecycle default), then proceed on
   insistence and record it.
10. **ESG as a separate, optional step after the core profile,** mapped to the three 2021/1253
    categories: (a) minimum % Taxonomy-aligned, (b) minimum % SFDR sustainable, (c) PAI consideration.
    Allow "no preference". If no fund matches, offer an adaptation and record it. For DPS this is good
    practice rather than a ZDPS duty.
11. **Validity and refresh.** Version the profile. Prompt a refresh at each strategy change (§ 130(4)
    applies "before any change"), on a periodic cycle (suggest 24 months — REQUIRES_LEGAL_REVIEW), on
    material life events, and at least 60 days before the § 114 transfer.
12. **Minimise questions through prefill.** Take identity, birth date and address from BankID / KYC.
    Pre-select the lifecycle strategy behind a flag until the law passes; until then, recommend.
13. **At most 5 steps for DPS:** (1) identity via BankID, (2) contribution and employer, (3) profile
    questions, (4) recommendation, with an optional ESG step and a deviation warning, (5) review, sign,
    documents and optional transfer-in. Keep the profile step to about 8–10 questions.
14. **Fees and switching transparency.** Show that one strategy change per year is free and that the
    maximum charge is CZK 500 (§§ 61–62).
15. **Audit trail.** Store the question-set version, answers, computed scores, the recommendation, any
    deviation and the acknowledgement. This is needed for § 137 reliance and for ČNB supervision.
16. **Distance-contract withdrawal.** Show withdrawal information under the general consumer
    distance-financial-services rules — REQUIRES_LEGAL_REVIEW, as no ZDPS-specific rule was found.

## 4. Sources (accessed 2026-10-09)

- ZDPS consolidated text: https://www.zakonyprolidi.cz/cs/2011-427
- MF press release, Lepší penzijko: https://mf.gov.cz/cs/ministerstvo/media/tiskove-zpravy/2026/vlada-schvalila-lepsi-penzijko-64961
- Zákon 128/2026 Sb. (PDF, not text-extractable): https://mf.gov.cz/assets/attachments/2026-08-01_zakon-c-128-2026-Sb.pdf
- Reform coverage: https://www.praceamzda.cz/novinky/17275/novela-penzijniho-sporeni-pocita-s-vyssi-podporou-pro-mlade-a-koncem-transformovanych-fondu ; https://www.finance.cz/aktuality/penzijni-sporeni-ceka-revoluce-snizi-se-poplatky-a-pro-nekoho-se-zvysi-statni-prispevky/
- DIP: https://www.epravo.cz/top/clanky/dlouhodoby-investicni-produkt-zajisteni-na-stari-na-kapitalovem-trhu-117182.html ; https://www.dauc.cz/clanky/14639/uplatneni-dlouhodobeho-investicniho-produktu ; https://investona.cz/static/produktove_podminky_pro_dlouhodoby_investicni_produkt.pdf
- Market: https://www.mesec.cz/n/penzijni-sporeni/?pi=2 ; https://www.finmag.cz/tema/penzijni-spolecnosti ; https://penze.uniqa.cz/doplnkove-penzijni-sporeni/Documents/Uniqa/Prohlaseni_k_pristupnosti_DPS.pdf ; https://www.conseq.cz/getmedia/e7f2d6a0-9e63-417d-956b-644af41dfab5/Zenit-smluvni-a-obchodni-podminky-sazebniky-C2401.pdf.aspx?ext=.pdf
- EU (not re-read today): MiFID II 2014/65/EU Art. 25; Del. Reg. 2017/565 Arts. 54–56; Del. Reg. 2021/1253; ESMA35-43-3172; ESMA35-43-3006 — via eur-lex.europa.eu and esma.europa.eu.
