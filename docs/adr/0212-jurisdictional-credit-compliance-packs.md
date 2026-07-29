---
date: 2026-07-29
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [lending, compliance, product-catalog]
summary: "Jurisdictional credit law (disclosures, cooling-off, APR labels, termination rules) ships as versioned, effective-dated, four-eyes-activated data packs pinned on each contract; origination without an active pack fails closed."
---

# ADR-0212 — Jurisdictional credit compliance packs as versioned effective-dated data

## Context

Consumer credit is one of the most jurisdiction-bound products a bank sells. The
*process duties* differ materially per country, and they differ along more than one
axis — disclosure content, waiting periods, withdrawal rights, pricing disclosure
conventions, and termination rules:

| Duty | CZ (zákon č. 257/2016 Sb.) | DE (BGB §§ 491 ff.) | EU floor |
|---|---|---|---|
| Pre-contractual disclosure | standardní předsmluvní informace | SECCI (Standard European Consumer Credit Information) | CCD2 (EU) 2023/2225; MCD 2014/17/EU (ESIS for mortgages) |
| APR convention | RPSN | effektiver Jahreszins | CCD2 harmonised APR |
| Withdrawal / cooling-off | 14 days | 14 Tage Widerruf | 14 days (CCD2) |
| Early-repayment compensation | capped per act | capped per BGB | capped (0,5–1 % CCD2) |
| Bank termination | statutory notice + grounds | Kündigung rules | national transpositions |

Hard-coding these per country produces an unmaintainable branch-per-jurisdiction code
base; spinning up a service per country is absurd; and a bank that cannot *prove* which
legal rule set a given contract was originated under cannot evidence compliance to its
supervisor. The platform already owns the pattern for this class of problem:
**ADR-0138** (configuration-driven fee rules) established "declarative config → pure
stateless evaluator → fail-closed decision" with a closed vocabulary and no scripting;
the product catalog (ADR-0105) is the product source of truth. This ADR applies the
same pattern to credit law. **Why now:** ADR-0211 makes origination a governed
multi-state process — the *variability* of that process across countries must be
decided before the states are coded, or jurisdiction logic will leak into the state
machine and never come out.

## Decision

**D1 — Jurisdictional obligations are data, not code: the credit compliance pack.** A
pack is a versioned, effective-dated, declarative record keyed by
`(jurisdiction, productType, version)` carrying, in a closed schema:

- `requiredSteps` — the mandatory origination states/waits for this
  jurisdiction × product (subset of the ADR-0211 canonical graph, e.g. `COOLING_OFF`,
  `AWAITING_SIGNATURE`), each with its statutory duration;
- `disclosures` — the mandatory pre-contractual and contractual document set
  (id, template key, language(s), delivery + acknowledgement requirements);
- `aprConvention` — the pricing-disclosure **label and locale**, not the math: the
  APRC formula is the single harmonised CCD2 Annex I computation (one pure function
  in `openbank-libs`); RPSN / effektiver Jahreszins / APR are national-language
  disclosures of the *same* number. The pack selects the label, locale and any
  national disclosure add-ons — never a different formula;
- `coolingOffDays`, `earlyRepaymentCompensationCap`, `terminationRules`
  (notice periods, permitted grounds, payout-quote rules consumed by ADR-0215);
- `mandatoryChecks` — jurisdiction-mandated decision-engine inputs (ADR-0213),
  e.g. a statutory affordability floor.

**D2 — Fail-closed, closed vocabulary, no scripting** (the ADR-0138 invariants). An
origination request for a `(jurisdiction, productType)` pair with **no active pack is
rejected** — the bank never originates under an unmodelled law. Unknown keys are
rejected at pack validation; nothing is coerced into a guessed rule.

**D3 — The pack version is pinned on the contract.** The `LoanApplication` and the
resulting `Loan` store the exact `packVersion` they were originated under, immutably;
the ADR-0214 evidence chain records it on every transition. A supervisor can therefore
reconstruct "under which version of which law was this loan sold" without consulting
code history.

**D4 — Packs are governed as code, activated four-eyes at runtime — no service
release per pack.** Packs are *authored* as reviewed YAML/JSON in the repo (compliance-
officer review is the pull request), but *delivery* is a runtime operation: a role-
gated, four-eyes admin endpoint (`ROLE_COMPLIANCE` maker-checker, ADR-0116 mechanics)
validates and activates a pack into the service's pack table with an `effectiveFrom`
date. Flyway migrations create the **schema only**; pack *content* never rides a
migration, so adding a jurisdiction is a data activation, not a release of the
lending service. Two pack versions may coexist across the effective boundary;
in-flight applications complete under their pinned version. **Bootstrap:** fail-closed
enforcement (D2) ships behind a config flag; the CZ pack is seeded and four-eyes-
activated *before* the flag flips, so production never sees a packless window.

**D5 — Evaluation is pure and shared.** The pack interpreter (`CompliancePackEvaluator`:
given a pack, what steps/disclosures/limits apply) is a pure function in
`openbank-libs` (zero framework imports, ADR-0002), unit-tested against the seeded CZ
pack first; DE/EU-floor packs follow as data-only additions — new jurisdictions must be
**data + templates, not code**.

**D6 — Documents are rendered from pack templates and stored immutably.** Every
disclosure/contract artefact (SECCI, pre-contractual information, the contract itself,
termination notices) is rendered from the pinned pack's template version, in the pack's
language(s), and stored in the service's document store with its **content hash
recorded in the ADR-0214 evidence chain** — the artefact a customer signed is
provably the artefact the pack version required, and re-rendering later cannot
rewrite history. Templates are versioned alongside the pack (same PR, same four-eyes
activation); a pack referencing a missing template fails validation (fail-closed).

## Alternatives considered

- **Hard-code per-country branches in the service.** Simplest first commit; the
  classic path to an unshippable N-country mess, and jurisdiction logic leaks into the
  state machine. Rejected.
- **Full BPMN/DMN per jurisdiction (Kogito).** See ADR-0211 — rejected platform-wide;
  legal duties are *data with a small interpreter*, not workflow models.
- **External compliance-content SaaS.** Maintained legal content as a service, but
  exports applicant context, breaks cloud-agnostic in-cluster OSS (ADR-0027), and makes
  a regulator-facing artefact depend on a vendor's availability. Rejected; a future
  *authoring aid* is compatible with packs-as-data.
- **General expression language in packs (scripting).** Rejected per ADR-0138 —
  closed vocabulary; a richer layer can be reconsidered only when real packs outgrow it.

## Consequences

**Positive**
- New jurisdictions are a compliance-reviewed data PR plus document templates — no
  service code, no release of the lending service, no redeploy of decision logic.
- Pinned pack versions make jurisdiction compliance *provable per contract* — the
  single most common supervisor question about cross-border lending.
- Reuses the ADR-0138 pattern exactly: one mental model for "config → executable rule
  → fail-closed decision" across fees, credit policy (ADR-0213) and legal duties.

**Negative**
- The pack schema is a bet: a jurisdiction whose law genuinely cannot be expressed in
  the closed vocabulary forces a schema *extension* (a code change after all). The
  schema must be designed against CZ + DE + the CCD2/MCD floor up front to minimise
  this.
- Legal content authorship is real, recurring compliance work — the platform makes it
  *executable*, it does not make it free.

**Neutral**
- Decision-only ADR; ships with the CZ pack as the reference content.
- Packs are reference data, not secrets; no new infra.

## Compliance impact

- PCI DSS: not applicable.
- DORA:    pack activation is a governed change (four-eyes + audit, ADR-0214).
- GDPR:    disclosure/acknowledgement records are personal data — retention per the
           pack and ADR-0118; data minimisation in evidence payloads.
- PSD2:    not applicable.
- CNB:     zákon č. 257/2016 Sb. (CZ reference pack); CCD2 (EU) 2023/2225 and MCD
           2014/17/EU as the EU floor; EBA/GL/2020/06 creditworthiness duties surface
           here as `mandatoryChecks`.

## References

- ADR-0138 — configuration-driven rule engine (the pattern this ADR instantiates)
- ADR-0211 — origination orchestration (the canonical state graph packs parameterise)
- ADR-0213 — credit policy decision engine (consumes `mandatoryChecks`)
- ADR-0214 — audit evidence (pack version recorded per transition)
- ADR-0215 — termination lifecycle (consumes `terminationRules`, compensation caps)
- ADR-0105 — unified product identity (product source of truth)
- ADR-0116 — four-eyes mechanics (pack activation)
- ADR-0118 — GDPR data lifecycle (retention)
- Zákon č. 257/2016 Sb. (CZ consumer credit); CCD2 (EU) 2023/2225; MCD 2014/17/EU
