---
date: 2026-10-04
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ledger, payments, regulatory-reporting, accounting-close]
summary: "Add GL 1010 ČNB settlement/reserve account; scheme net settlement and the interbank leg of payments post to it, 1100 becomes intra-day clearing that must net to zero, and 1010 above the minimum reserve counts as Level 1 HQLA."
---

# ADR-0332 — ČNB settlement and reserve account in the ledger chart

## Context

ADR-0281 decided the net-settlement ledger leg for cleared batches, ADR-0108 that rail
settlement runs through transaction-service, ADR-0315 that treasury posts its own deals (incl.
the ČNB deposit facility, GL 1510, and lombard, GL 2320) and tracks minimum reserves, and
ADR-0314 how the risk engine classifies GL balances. None of them decides **where the bank's
central-bank settlement money lives**: ADR-0315 D8 tracks "the daily holding at the ČNB
account", but the chart has no such account. This ADR is needed because that gap makes the
LCR structurally zero.

Measured on the sandbox on 2026-10-04: LCR = 0, because the bank holds no HQLA other than an
empty 1510. Its ~22.4M CZK of settlement money sits on GL 1100 "Customer Cash Clearing"
(ledger `V3__ledger_governance.sql`), which transaction-service payments, domestic-payment
settlement, clearing net settlement (ADR-0281) and lending disbursement funding
(`LendingGlChart.FUNDING_CLEARING`) all post to. #11107 had to classify 1100 as
`technical-or-clearing` — not HQLA, no inflow, 100% RSF — a conservative POLICY CHOICE
documented in `openbank-risk-engine/src/main/resources/application.yaml`. That choice is right
for what 1100 *is* (funds in transit, no counterparty, no maturity); what is wrong is that
1100 has become the bank's de-facto settlement account.

In a Czech bank, CZK scheme settlement (CERTIS) and EUR settlement (TARGET2 / T2 via a
correspondent or direct participation) run over the bank's **settlement account at ČNB**.
That balance is a central-bank reserve: Level 1 HQLA under Delegated Regulation (EU) 2015/61
Art. 10(1)(b)(iii), *to the extent the central bank's policy allows it to be withdrawn in
times of stress*. The same account carries the minimum reserve requirement (ČNB minimum
reserves, held on average over the maintenance period — #11546 reserve averaging), which is
where the "withdrawable" qualifier bites.

## Decision

D1. **New GL account `1010` "Settlement Account at ČNB (CZK)"**, ASSET, CZK, seeded by a new
ledger Flyway migration with the fixed id convention
`a0000000-0000-0000-0000-000000001010` (same as V29/V30). `1010` sits next to cash `1000` and
nostro `1001`/`1002`, before clearing `11xx`. No EUR twin now: the sandbox models EUR
settlement through the EUR nostro `1002`; a direct T2 participation, if ever modelled, takes a
new code by a later decision (POLICY CHOICE — T2 cash accounts are held at the ECB/NCB system,
not the ČNB CZK account, so a shared code would be wrong).

D2. **1100 is strictly intra-day customer clearing and must net to zero by end of day.** Every
debit to 1100 is matched by a settlement credit the same business day. A non-zero 1100 at the
end-of-day cut-off is a break, not a balance.

D3. **Posting rules per flow** (Dr / Cr):

| Flow | Customer leg | Interbank leg |
|---|---|---|
| Payment out (domestic, CERTIS) | Dr customer account / Cr 1100 | at batch settlement: Dr 1100 / Cr 1010 |
| Payment in (domestic, CERTIS) | Dr 1100 / Cr customer account | at batch settlement: Dr 1010 / Cr 1100 |
| Clearing net settlement (ADR-0281) | — | net debit position: Dr 1100 / Cr 1010; net credit: Dr 1010 / Cr 1100 |
| Internal (on-us) transfer | Dr payer / Cr payee (no 1100, no 1010) | none |
| SEPA / EUR payment | as today via 11xx EUR clearing | Dr/Cr 1002 EUR nostro (unchanged) |
| Lending disbursement funding | Dr loan / Cr customer account | funding source moves from 1100 to 1010 only when paid out to another bank; on-us disbursement does not touch 1010 |
| Treasury ČNB deposit facility placement | — | Dr 1510 / Cr 1010 (today Cr nostro 1001) |
| Deposit facility maturity | — | Dr 1010 / Cr 1510 (+ interest per ADR-0315 D5) |
| ČNB lombard draw / repay | — | Dr 1010 / Cr 2320; repay Dr 2320 / Cr 1010 |
| Nostro funding from ČNB | — | Dr 1001 / Cr 1010 (and reverse) |

The idempotency keys of ADR-0281 are unchanged; only the contra account of the net
settlement journal changes.

D4. **Opening-balance migration on the sandbox is a journal, never a DB edit.** One balanced
manual journal through the ledger API, maker-checker approved: Dr 1010 / Cr 1100 for the
measured end-of-day 1100 balance, with a deterministic idempotency key
(`adr-0332-opening-1100-to-1010`) and a description citing this ADR. It runs after D3 is live
so 1100 does not refill under the old rules. A DB `UPDATE` is forbidden: it would bypass the
hash-chained journal and break reconciliation.

D5. **Risk-engine classification.** `"1010": hqla-l1-cash-or-reserves` in the LCR/NSFR GL map
and `central-bank` in the counterparty map, like 1510. **POLICY CHOICE (minimum reserve):**
Art. 10(1)(b)(iii) admits reserves only to the extent withdrawable in stress; whether the ČNB
releases the requirement in stress is not something this repo can evidence. So the engine
deducts the current maintenance period's **minimum reserve requirement** (from treasury's
reserve tracking, ADR-0315 D8) from the 1010 balance when counting HQLA, and reports the
deduction on the result. If the requirement is unknown, the whole 1010 balance is excluded and
the result carries a note — never the optimistic reading. 1100 keeps `technical-or-clearing`.

D6. **Reconciliation and alert.** An end-of-day check asserts `balance(1100) == 0` per
currency; a non-zero balance raises `LedgerClearingNotNetted` with the amount and the
unmatched journals. A second check reconciles 1010 against the (simulated) ČNB statement, the
same break mechanism as nostro reconciliation (ADR-0315 D7).

D7. **Rollout order, each behind a flag defaulting off:**
1. ledger — migration adding 1010 (additive, no flag needed).
2. risk-engine — classify 1010 (harmless while 1010 is empty).
3. clearing — `openbank.clearing.settlement.cnb-account-enabled`: net settlement contra → 1010.
4. transaction-service and domestic-payment — `openbank.<svc>.settlement.cnb-account-enabled`:
   interbank leg → 1010.
5. lending — `openbank.lending.funding.cnb-account-enabled`.
6. treasury — `openbank.treasury.cnb-settlement-account-enabled`: facility/lombard contra → 1010.
7. D4 opening journal on the sandbox, then D6 alert enabled.

Flags are removed once the sandbox has run a full reserve maintenance period on the new path.

## Alternatives considered

- **Reclassify 1100 as Level 1 HQLA.** Rejected. It would make the LCR non-zero with a config
  line, but 1100 mixes customer funds in transit, lending funding and net settlement with no
  counterparty or maturity; calling it central-bank reserves asserts a fact the ledger does
  not record (Art. 10(1)(b) requires a claim on the central bank). It would also hide the
  real defect — 1100 not netting — behind a better ratio, and an auditor reading the chart
  would find "clearing" counted as reserves.
- **Use 1510 (deposit facility) as the settlement account.** Rejected: the deposit facility is
  an overnight placement with its own rate and treasury deal lifecycle; settlement cannot run
  over it, and mixing them breaks treasury accrual.
- **Use 1000 "Cash and Cash Equivalents".** Rejected: non-posting header in V1, and its content
  (banknotes vs reserves) is not known, as the risk-engine comment already states.
- **Do nothing and keep LCR = 0.** Rejected: the ratio is then structurally uninformative and
  every LCR limit is permanently breached in the sandbox.

## Consequences

- LCR becomes meaningful. **Sandbox estimate:** HQLA moves from 0 to
  `22.4M − MRR` CZK, where MRR = 2% × reserve base (ČNB rate, POLICY CHOICE to deduct it; the
  reserve base is the primary-deposit balance from the ledger snapshot). Net outflows are
  unchanged by this ADR, so LCR = (22.4M − MRR) / net outflows; with MRR unknown on day one
  the conservative D5 rule keeps LCR at 0 until treasury publishes the requirement.
- NSFR improves: 22.4M leaves 1100 (100% RSF) for 1010 (0% RSF as central-bank reserves), so
  RSF falls by ~22.4M CZK; ASF is unchanged.
- 1100 becomes a control: a non-zero end-of-day balance is a visible break instead of a
  silent liquidity buffer.
- Money-path services touched: ledger, transaction, domestic-payment, clearing, lending
  (money-path per `rules.yaml`), plus treasury and risk-engine. Each implementation PR needs
  the two approvals and threat-model update of ADR-0030; this ADR itself changes no code.
- Gate: none new for the decision itself. D6 is a runtime alert; the posting rules are
  covered by each service's posting tests. A follow-up may add a chart-consistency check that
  every GL code posted by a service exists in the ledger seed.
- **Follow-up — nostro overdraft alert:** treasury can place more at 1510 / with banks than
  its nostro or 1010 holds (it posts the deal without a funding check). Add a pre-booking
  funding check and a `TreasuryFundingExceeded` alert when the contra account would go
  negative. Tracked as a follow-up issue to #11107.

### Delivery check

```
git grep -n "'1010'" openbank-ledger-service/src/main/resources/db/migration   # one seed row
grep -n '"1010": hqla-l1-cash-or-reserves' openbank-risk-engine/src/main/resources/application.yaml
git grep -n '1010' openbank-clearing-service/src/main openbank-transaction-service/src/main \
  openbank-domestic-payment/src/main openbank-treasury-service/src/main   # each non-empty
```
On the sandbox: `balance(1100)` at end of day is 0 and `balance(1010)` equals the simulated
ČNB statement. Until all of these hold, the honest status is `planned`/`partial`.

## Compliance

- CNB / EBA: LCR per Delegated Regulation (EU) 2015/61 Art. 10(1)(b)(iii); NSFR per CRR
  Part Six Title IV (exact RSF article for central-bank reserves UNVERIFIED in this repo, the
  same caveat the risk-engine config carries). ČNB minimum reserve rules govern D5.
- PCI, DORA, GDPR, PSD2: not engaged.

Refs #11107, #11546, ADR-0108, ADR-0281, ADR-0313, ADR-0314, ADR-0315.
