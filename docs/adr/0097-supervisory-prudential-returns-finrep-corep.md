# 97. Supervisory / prudential returns (FINREP / COREP) derived from the attested close

Date: 2026-06-16
Status: Accepted
Delivery-Status: Planned

Phase 1 implemented: openbank-finrep-service derives F01.01+F02.00 from ledger trial balance. Phase 2 (COREP+XBRL transmission) tracked separately.
Author(s): @JiRaska

**Delivery note (updated 2026-06-30):**
- **Phase 1 (FINREP core)** — ✅ Designed: F 01.01 balance sheet and F 02.00 P&L templates sourced from attested close; tie-out to statements designed; ready to ship once ADR-0096 entity-level close lands.
- **Phase 2 (COREP)** — ⬜ Deferred: C 01.00 own funds, capital adequacy, risk-weighting data, EBA XBRL/DPM taxonomy mapping, and ČNB transmission channel depend on ADR-0096 close; Phase 2 explicitly deferred.

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

## Consequences

**Positive:** real prudential returns reconciled to an attested close (CRR/CRD); transmission is
in-scope so the regulatory output is actually produced (avoids the AnaCredit render-only trap);
reuses the proven AnaCredit/hexagonal pattern.

**Negative / open:** hard dependency on ADR-0096 (cannot ship first); COREP needs risk-weighting +
capital data not yet modelled (Phase 2 is materially larger); EBA XBRL/DPM taxonomy is heavy and
versioned (a maintenance commitment); the ČNB transmission channel needs real credentials/onboarding
(prereq-gated, like the AnaCredit SDMX channel). License-gated → **Proposed**.

## References
- Issue #471; ADR-0096 (attested close), ADR-0037 (AnaCredit render-only + transmission gap), ADR-0039.
- CRR (Regulation (EU) 575/2013) / CRD; EBA Implementing Technical Standards on supervisory reporting
  (FINREP/COREP, DPM/XBRL taxonomy); ČNB supervisory reporting.
