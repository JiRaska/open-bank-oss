# 33. Withholding tax on credit interest at capitalization

Date: 2026-05-30

Status: Accepted
Delivery-Status: Shipped

## Context

The multi-currency rollout plan (`docs/strategy/multicurrency-implementation-plan.md`, Phase 4
item #10, finding **G3**) records a regulatory hole: **no withholding tax on credit interest**.
`openbank-interest-service` today accrues and capitalizes interest **gross** — it credits the full
`capitalizedAmount` and withholds nothing.

For a Czech bank this is non-compliant. Under the Income Tax Act (zákon č. 586/1992 Sb. o daních z
příjmů):

- **§36** — interest income from deposits/accounts of a resident **individual** is taxed by a
  *special tax rate* (zvláštní sazba daně) of **15 %**, levied as a **final withholding tax** at
  source. The customer does not declare it; the bank withholds and remits.
- **§38d** — the payer (the bank) withholds on the day the interest is *credited* (připsání), and
  remits to the tax office by the end of the following month, with an annual reconciliation
  (Vyúčtování daně vybírané srážkou).
- **Non-residents** — 15 % by default, **35 %** where the beneficial owner is resident in a
  non-cooperating / no-treaty state (§36 odst. 1 písm. c), reduced or zeroed by an applicable
  double-tax treaty.
- **Legal entities** — bank-account interest is **not** subject to this withholding; it enters the
  corporate income-tax base gross. So withholding is taxpayer-type dependent.
- **Tax rounding** — the withheld tax is rounded **down to whole CZK** per the daňový řád; the tax
  base (§36) is rounded down to whole CZK.

The interest domain has no concept of a taxpayer, a rate, a tax base, or a net-vs-gross split. The
gross `capitalizedAmount` is also what posts to the ledger. This ADR settles **where the tax is
computed, what it decides, how net/gross and the tax liability are recorded, and how it fails safe**
when the taxpayer profile cannot be resolved — deliberately mirroring the gate-as-pure-policy shape
established for screening in ADR-0032.

## Decision

### A. The taxable event is **capitalization**, not accrual

Withholding is computed inside `capitalize(...)` — the moment interest is *credited* — never at
`accrue(...)`. Daily `InterestAccrual` rows stay **gross** (they are an internal running total, not a
payment); §38d ties the withholding to the credit date, so a period's accruals are summed, taxed
once, and the **net** amount is what is capitalized and posted to the customer. This keeps the daily
accrual hot-path untouched and localizes tax to the once-per-period credit.

### B. Three-way decision, encoded as a pure domain policy

`WithholdingTaxPolicy.compute(grossInterest, profile, asOf)` (framework-free, unit-tested) returns a
`WithholdingResult(taxableBase, rate, taxAmount, netAmount, treatment, exemptCode?)`:

| Treatment | Trigger | Effect on the credit |
|-----------|---------|----------------------|
| **WITHHELD** | resident individual (15 %), or non-resident (15 % / treaty rate / 35 % non-cooperating) | `net = gross − tax`; record a `WithholdingTax` liability; credit **net** |
| **NOT_WITHHELD** | legal-entity beneficiary (interest enters CIT base gross) | `tax = 0`; credit **gross**; record the exempt reason |
| **EXEMPT** | a statutory/treaty exemption with evidence on file | `tax = 0`; credit **gross**; record the exempt code |

The tax base and tax amount are rounded **down to whole CZK** (`RoundingMode.DOWN`) inside the policy
so the rule and its rounding live in one tested place and cannot drift across call sites.

### C. Fail-safe to **withhold** when the taxpayer profile is unresolved

Interest-service has no party tax attributes today. The profile (taxpayer type, residency, treaty
rate, exemption) is resolved through a new outbound `TaxProfilePort`. If the port cannot resolve a
profile (party service unreachable, attributes missing), the policy **defaults to the
15 % resident-individual withholding** — the fiscally conservative choice that never *under*-withholds
(an over-withholding is correctable by the taxpayer; an under-withholding is a bank liability). This
is the tax analogue of ADR-0032's fail-closed stance. Account→party tax-attribute resolution is a
documented fast-follow; v1 ships the port with a CZ-resident-individual default provider.

### D. Net credit + an explicit tax-liability split in the ledger

Capitalization currently posts one gross leg. It becomes a split: **net** to the customer pocket and
**tax** to a *withholding-tax-payable* GL liability account, so the ledger always balances and the
remittable liability is queryable. `InterestCapitalization` gains `grossAmount`, `taxAmount`,
`netAmount` (the existing `capitalizedAmount` becomes the **net**, preserving its credit semantics);
a new `WithholdingTax` aggregate records `(accountId, partyRef, period, base, rate, taxAmount,
currency, treatment, status: RECORDED → REMITTED → RECONCILED)`.

### E. Currency scope — CZK now, foreign-currency interest deferred

Czech withholding is assessed in **CZK**. v1 withholds only on **CZK-denominated** interest. Interest
credited in a foreign-currency pocket records `treatment = DEFERRED_FX` (no withholding, flagged for
follow-up) rather than guessing a base — withholding a foreign base needs the §38 conversion (ČNB
rate at the credit date via `openbank-fx-service`), which is its own decision. This bounds v1 to the
common case (CZK savings interest) without silently mis-withholding foreign interest.

### F. Remittance & reporting are downstream, event-driven

The gate only **records** the withholding and emits a versioned `interest.withholding.recorded`
event (outbox, backward-compatible). Monthly remittance (Vyúčtování daně vybírané srážkou) and the
annual taxpayer confirmation (potvrzení o sražené dani) are assembled by the reporting capability
(finding **G7**) from these records — out of scope here. Capitalization **reversal** must reverse the
paired `WithholdingTax` record (status → `REVERSED`), kept transactional with the accrual reversal.

## Alternatives considered

- **Withhold at accrual (daily).** Pros: net accruals. Cons: contradicts §38d (tax is due at the
  *credit*, not as interest economically accrues); multiplies rounding error across daily fractions;
  pollutes the hot path. **Rejected.**
- **Put the tax split in ledger-service posting rules.** Pros: one place does money movement. Cons:
  tax treatment (rate, residency, treaty, rounding, exemptions) is *domain* policy, not GL plumbing;
  the ledger should record a split it is *told*, not derive tax. **Rejected** — keep policy in the
  interest domain, ledger records the split.
- **Integrate an external tax engine.** Overkill for a single-jurisdiction, two-rate (15 %/35 % +
  treaty) final withholding. Revisit if multi-jurisdiction deposit-taking arrives. **Rejected for v1.**
- **Fail *open* (credit gross) when the profile is unresolved.** Mirrors nothing we want: it creates
  an under-withholding liability for the bank. **Rejected** in favour of fail-safe-to-withhold (C).

## Consequences

**Positive**
- Closes the G3 compliance hole; interest credited to individuals is taxed at source per §36/§38d.
- Tax logic is one pure, unit-tested policy with its statutory rounding — no drift across call sites.
- The ledger always carries the remittable liability explicitly; reporting (G7) reads it directly.
- Fail-safe default means an outage never produces an under-withholding.

**Negative**
- Capitalization becomes a multi-leg posting and gains a paired tax aggregate — more moving parts and
  a mandatory reversal pairing.
- A conservative default over-withholds for unresolved legal-entity / exempt parties until
  account→party tax resolution lands (corrections needed downstream).

**Neutral**
- `InterestCapitalization` and the capitalization event change shape (additive, versioned).
- Foreign-currency interest is explicitly *not* withheld in v1 (flagged `DEFERRED_FX`).

## Compliance impact

- CNB / CZ tax: zákon č. 586/1992 Sb. o daních z příjmů **§36** (zvláštní sazba 15 %/35 %), **§38d**
  (srážka a odvod, lhůty), daňový řád (zaokrouhlení daně na celé Kč dolů). Vyúčtování daně vybírané
  srážkou + potvrzení o sražené dani assembled downstream (G7).
- GDPR: tax residency and taxpayer type are personal data — minimise, source from the party service,
  do not duplicate beyond the withholding record.
- DORA: `TaxProfilePort` is a new inter-service dependency — resilience (retry/timeout) and the
  fail-safe default per (C).
- PCI DSS / PSD2: not applicable.

## References

- `docs/strategy/multicurrency-implementation-plan.md` — Phase 4 item #10, finding **G3**.
- ADR-0032 — synchronous sanctions/AML screening gate (the pure-policy + fail-safe shape reused here).
- ADR-0024 / ADR-0025 — multi-currency single-IBAN pockets and per-currency ledger balancing (the
  currency-scope and ledger-split context).
- `openbank-interest-service` — `InterestService.capitalize`, `InterestCapitalization`.
