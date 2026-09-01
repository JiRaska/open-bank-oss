---
date: 2026-06-16
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [regulatory-reporting, compliance, accounting-close]
summary: "FINREP and COREP supervisory returns are derived from the attested statutory close via a dedicated openbank-finrep-service, starting with F 01.01, F 02.00 and C 01.00 and flagging missing data as explicit zeroes."
---

# 97. Supervisory / prudential returns (FINREP / COREP) derived from the attested close

Phase 1 implemented: openbank-finrep-service derives F01.01+F02.00 from ledger trial balance. Phase 2 (COREP+XBRL transmission) tracked separately.
Author(s): @JiRaska

**Delivery note (updated 2026-07-08):**
- **Phase 1 (FINREP core) — deployment** — ✅ Shipped: `openbank-finrep-service` is registered with ArgoCD
  (`openbank-infra/gitops/apps/finrep.yaml`) alongside its existing Deployment/Service/Namespace/NetworkPolicy
  manifests under `openbank-infra/gitops/components/finrep/` — the service now actually syncs instead of
  sitting code-only and undeployed.
- **Phase 1 (FINREP core) — report logic** — 🟡 Partial, unchanged by this update: `F0101Mapper`/`F0200Mapper`
  compute F 01.01 (balance sheet) and F 02.00 (P&L) cells from `GET /api/v1/ledger/trial-balance`, a
  **live/point-in-time** ledger query — not yet the frozen, attested `ClosedPeriod` this ADR calls for
  (ADR-0096's entity-level close is not yet consumed here). The statements tie-out check and the ADR-0096
  wiring remain open work.
- **Phase 2 (COREP)** — 🟡 First increment landed, most of the phase still not started: `openbank-finrep-service`
  now has a `C0100Mapper` + `GET /api/v1/corep/templates/{templateId}` producing **C 01.00 (Own Funds)** as
  structured Kotlin data (`CorepTemplate`/`CorepCell`), same live-trial-balance source as Phase 1 (same
  ADR-0096 gap applies). Own-funds template selection rationale: C 01.00 is the template ADR-0097 names as
  the Phase 2 starting point, and no *other* real COREP template turns out to be free of the same
  capital/risk-weighting dependency — geographical-breakdown and ALMM/maturity-ladder templates were
  evaluated and rejected for the same reason one layer down (exposure-class/RW data, or contractual
  maturity-bucket data, that this platform also does not have). Capital-structure data was originally
  absent, so every own-funds row was an explicit flagged zero. Ledger migration V25 now supplies
  dedicated 6000-6060 EQUITY source accounts and the mapper derives CET1, Tier 1 and own funds from
  their posted balances. It never substitutes assets minus liabilities. A trial balance without any
  recognised capital source remains an explicit gap; an absent optional category (for example AT1) in
  an otherwise recognised structure is a genuine zero. **Still not built, at all:** every other COREP
  template (C 02.00 requirements, C 05.01 transitional provisions, large exposures, leverage ratio, etc.),
  EBA XBRL/DPM taxonomy output, and the ČNB transmission channel. Tracked in issue #605.

Depends on: ADR-0096 (entity-level statutory close — the attested source of truth).
Relates to: ADR-0037 (AnaCredit render-only — the reporting-service template + its transmission gap),
ADR-0039 (ledger golden source), ADR-0023 (analytics). Closes the supervisory-returns part of #471.

## Context

Issue #471 names, as a distinct gap, **supervisory / prudential returns (FINREP/COREP-shaped, ČNB
výkazy)** mapping to *CRR/CRD; ČNB reporting*. The platform has **no FINREP/COREP capability** today:
the admin-ui `regulatory` page exists, and AnaCredit (ADR-0037) renders a statistical dataset, but
there is no prudential reporting.

Two hard constraints shape the decision:

1. **Prudential returns must derive from an *attested* close, not point-in-time data.** FINREP
   (financial reporting) and COREP (own funds / capital adequacy) are period returns reconciled to
   the statutory financial statements. Deriving them from a live trial-balance query would make them
   non-reproducible and non-reconcilable to the závěrka. They therefore **depend on ADR-0096's frozen,
   attested `ClosedPeriod`** as their source — this ADR cannot land before 0096.

2. **AnaCredit (ADR-0037) is the cautionary template.** It renders the dataset correctly but has **no
   transmission path to ČNB** — so the regulatory *output* is never actually produced. FINREP/COREP
   must not repeat that: transmission is in-scope, not a follow-up.

**Why now:** license-gated like ADR-0096, but recording it now fixes the layering (close → statements
→ prudential returns) and the explicit lesson "render is not report — transmission is the deliverable."

## Decision

Add FINREP/COREP as a **prudential-reporting capability in the regulatory-reporting bounded context**
(co-located with / sibling to the ADR-0096 close service; both read the attested close, neither posts
to the ledger). Reuse the AnaCredit render pattern (pure-domain mapper + builder, framework-free
`domain/`), and **add the transmission stage AnaCredit lacks**.

1. **Source = attested close (ADR-0096).** Returns are built from the frozen `ClosedPeriod` trial
   balance + financial statements, plus the reference datasets each return needs (e.g. AnaCredit
   exposures, FX positions, capital instruments). A return for a period is **reproducible** because its
   inputs are immutable. A return MUST reconcile to the statutory statements for the same period (a
   built-in tie-out check, mirroring ADR-0039's reconciliation discipline).

2. **Scope, phased.** Phase 1: **FINREP** core templates (balance sheet F 01.01, P&L F 02.00) derived
   directly from the rozvaha/VZZ of ADR-0096 — lowest marginal cost since the statements already exist.
   Phase 2: **COREP** own funds (C 01.00) + capital adequacy, which need risk-weighting + capital
   instrument data (larger; may pull from a future risk engine). Declarative **taxonomy mapping**
   (statement-line → EBA DPM cell), versioned config tracking the EBA reporting framework release.

3. **Output format + transmission (the deliverable).** Render to the **EBA XBRL** taxonomy (DPM) — the
   format ČNB/EBA consume — and provide a **transmission adapter** to the ČNB submission channel
   (out-port + adapter, same hexagonal shape as the sanctions/AnaCredit clients), plus a manual
   export for review. Submission attempts + acknowledgements are persisted (audit/ADR-0086).

4. **Surfacing.** The admin-ui `regulatory` page shows, per period: rendered returns, the
   statements-reconciliation tie-out result, and submission status. Derived, never hand-faked
   (ADR-0074/0079 house rule).

## Alternatives considered

- **Derive the returns from a live, point-in-time trial-balance query** — build FINREP/COREP straight off `GET /api/v1/ledger/trial-balance` instead of the attested close. Rejected as the target state — it would make the returns non-reproducible and non-reconcilable to the závěrka; this is exactly the interim gap the Phase 1/Phase 2 delivery note flags as open work against ADR-0096.
- **Reuse the AnaCredit render-only shape (ADR-0037) with transmission as a follow-up** — render the dataset and defer the submission channel. Rejected — AnaCredit is the cautionary template: without a transmission path the regulatory output is never actually produced, so transmission is in scope here.
- **Start Phase 2 on a COREP template other than C 01.00** — geographical-breakdown and ALMM/maturity-ladder templates were evaluated. Rejected — each carries the same missing-data dependency one layer down (exposure-class/risk-weight data, or contractual maturity-bucket data) that the platform also does not have.
- **Omit or estimate the own-funds rows the ledger cannot source** — silently drop the rows or supply a guessed value where no capital-structure GL data exists. Rejected — every such row is reported as an explicit, flagged zero (`isDataGap` + `gapReason`), never a silently omitted or guessed value.

## Consequences

**Positive:** real prudential returns reconciled to an attested close (CRR/CRD); transmission is
in-scope so the regulatory output is actually produced (avoids the AnaCredit render-only trap);
reuses the proven AnaCredit/hexagonal pattern.

**Negative / open:** hard dependency on ADR-0096 (cannot ship first); COREP needs risk-weighting +
capital data not yet modelled (Phase 2 is materially larger); EBA XBRL/DPM taxonomy is heavy and
versioned (a maintenance commitment); the ČNB transmission channel needs real credentials/onboarding
(prereq-gated, like the AnaCredit SDMX channel). License-gated → **Proposed**.

## Compliance impact

- PCI DSS: not applicable — aggregated prudential returns, no cardholder data in scope.
- DORA:    not applicable — supervisory financial reporting, not an ICT resilience control.
- GDPR:    not applicable — entity-level aggregate cells, not personal data.
- PSD2:    not applicable — no payment initiation or account-access interface involved.
- CNB:     CRR (Regulation (EU) 575/2013) / CRD and the EBA Implementing Technical Standards on supervisory reporting (FINREP/COREP, DPM/XBRL taxonomy); ČNB supervisory reporting is the transmission destination.

## References
- Issue #471; ADR-0096 (attested close), ADR-0037 (AnaCredit render-only + transmission gap), ADR-0039.
- CRR (Regulation (EU) 575/2013) / CRD; EBA Implementing Technical Standards on supervisory reporting
  (FINREP/COREP, DPM/XBRL taxonomy); ČNB supervisory reporting.
