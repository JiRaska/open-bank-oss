---
date: 2026-11-08
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [regulatory-reporting, compliance, governance, ai-agents]
summary: "Every regulatory return is a declared spec in the repo, computed only in a backend and filed on the ADR-0336 lifecycle; the admin-ui catalogue derives from specs. AI explains and drafts, never files. CEÚ daily feed is the pilot."
---

# ADR-0367 — Regulatory reporting as code: declared return specs and the CEÚ daily feed pilot

## Context

ADR-0097 decided that FINREP/COREP are derived from the attested close by a stateless
`openbank-finrep-service`. ADR-0180 and ADR-0336 decided that a statutory filing is a system of
record in `openbank-tax-reporting-service` with one lifecycle (assemble, validate, four-eyes
attest, record submission, deadline gauge), and ADR-0340 adds CRS/FATCA as a further return
family there. Each of those decides **where one family of returns lives**. None of them decides
what a return *is* to the rest of the platform, and that gap is visible on `origin/main`:

- The regulatory catalogue is a hand-kept array (`REPORT_CATALOG`) inside
  `openbank-admin-ui/src/app/regulatory/page.tsx`. Nothing ties an entry to a backend that can
  produce it, so the UI can offer a return no service computes.
- Six catalogue returns (`sdat-payments`, `sdat-accounts`, `aml-far`, `ecb-payments`,
  `fatca-report`, `dora-ict`) are aggregated in the Next.js BFF
  (`openbank-admin-ui/src/lib/regulatory/generatedReports.ts`). A figure filed with a supervisor
  is therefore computed in the presentation tier, without a domain model, an audit record of what
  was computed, or a reproducible re-run. ADR-0056 makes the BFF the browser's only path to the
  cluster; it never made it a place for regulatory arithmetic.
- The deadline vocabulary (`DeadlineRule`) has no daily kind, and every catalogue entry is a
  periodic aggregate. Duties that are not periodic aggregates have no home at all.

The concrete missing duty is the **Centrální evidence účtů (CEÚ)** under zákon č. 300/2016 Sb.
and vyhláška č. 30/2017 Sb. (as amended by 163/2021 Sb.): every working day an institution
submits to ČNB, through SDAT, the creations, changes and corrections of the account and
safe-deposit-box data the act lists (the daily change overview; ČNB's current methodology names
it `CEUSIFE10`). It is a register feed, not a period total: what is reported is the *delta* of a
register state, per working day, keyed on accounts and the persons tied to them (holder,
authorised disposers, beneficial owner). There is no code, catalogue entry, ADR or issue for it
on `main`. Queries by authorised bodies are answered by ČNB from the register itself, so the
bank's duty is the outbound feed, not an inbound query service.

A second gap applies to every granular return: ADR-0252 runs permanent synthetic customers in
production, tainted end to end. Nothing obliges a regulatory return to exclude them, so the first
CEÚ feed built naively would report fictitious accounts to the central bank.

## Decision

We will treat regulatory reporting the way the platform already treats gates, agents and
governance metadata: **the declaration in the repo is the source of truth, CI enforces it, and
every surface is derived from it.**

1. **Declared return specs.** Each return is one file under `openbank-libs/governance/returns/`
   declaring: id, legal basis with effective-from/to, authority, return kind, cadence and
   deadline rule, owning service, template/taxonomy version, transport (SDAT, EPO, data box,
   manual), validation rule set, and `synthetic_policy`. Return kinds are a closed set:
   `periodic-aggregate` (FINREP, COREP, SDAT statistics), `granular-feed` (AnaCredit, CEÚ),
   `event` (AML suspicious-transaction report, DORA incident), `register` (DORA register of
   information).
2. **Backend-only computation.** A return's figures and records are computed only by its owning
   backend service. The admin-ui renders, previews and downloads what a backend produced; it does
   not aggregate. The six BFF-computed returns move to backends on the ADR-0336 lifecycle; until
   each moves, its spec says `computed_in: admin-ui-bff` so the debt is declared, not hidden.
3. **One filing lifecycle.** Every return that is filed is a record on the ADR-0336 statutory
   return lifecycle. This ADR adds no second lifecycle; it adds a `granular-feed` variant whose
   unit is one working-day delta, and a daily deadline rule.
4. **Synthetic exclusion is mandatory.** Every `granular-feed` and `event` spec declares
   `synthetic_policy: exclude`; the assembler drops ADR-0252-tainted parties and accounts and
   records how many it dropped, so the exclusion is evidenced rather than assumed.
5. **Derived catalogue.** The admin-ui catalogue and the deadline gauges are generated from the
   specs, the same way ADR-0071 derives the governance manifest. A hand-written catalogue entry is
   not permitted.
6. **AI boundary.** AI agents (ADR-0031 machinery, scoped by #7144) may read return specs,
   lineage and filing records, explain movements and gaps with citations, flag deadline and
   liveness risk, triage a supervisor rejection, and draft a spec change as a pull request. They
   never compute an authoritative figure, clear a validation failure, attest, submit, or change a
   spec outside a reviewed PR. A regulatory-change agent that turns a published taxonomy or
   decree change into a draft spec PR is in scope; its PR passes the same review as a human's.
7. **Pilot: the CEÚ daily feed.** CEÚ is the first return built natively on this model, in
   `openbank-tax-reporting-service` as the ADR-0336 reporting home: a register projection fed
   from account, party and mandate events, kept bitemporally (valid time and record time) so a
   correction can be re-derived as of any day; a daily job that diffs day D against D-1 into the
   change overview; schema validation before attest; submission recorded with ČNB's
   acknowledgement or rejection; an initial full-load path; and a workflow-liveness metric so a
   missed working day alerts rather than goes silent.

The ECB BIRD dictionary as an intermediate semantic layer is a **direction, not a decision** here:
specs carry a `semantic_ref` field so a later ADR can map to it without rewriting specs.

## Alternatives considered

- **Keep the catalogue in the UI and add CEÚ as one more BFF-generated report.** Smallest change
  and matches how six returns ship today. Rejected: CEÚ is a stateful register delta that needs a
  projection, bitemporal correction and acknowledgement handling, none of which a request-scoped
  BFF can hold; and it would extend the regulatory-arithmetic-in-the-UI shape this ADR exists to
  stop.
- **A dedicated new `openbank-regulatory-reporting-service` owning every return.** Clean single
  home. Rejected for now: ADR-0336 already rejected a parallel reporting module for pension
  returns on the same grounds (a duplicated lifecycle), and finrep's ledger-close data source is
  legitimately separate. The specs give a single *view* of all returns without a single *service*.
- **Buy a commercial regulatory-reporting suite.** Rejected: the platform is an open reference
  implementation, a closed engine cannot be inspected or gated in CI, and it would not see the
  synthetic taint.

## Consequences

**Positive**
- A return that exists in the UI provably has a backend, an owner and a deadline gauge.
- CEÚ — a daily statutory duty with no implementation — gets one, without fictitious accounts.
- Regulatory change becomes a reviewed diff to a spec, which is what lets an AI agent draft it.

**Negative**
- Six BFF returns must be migrated; until then the specs carry declared debt.
- The register projection duplicates account, party and mandate facts inside
  tax-reporting-service, deliberately, as a reporting copy fed by events (ADR-0009).

**Neutral**
- Enforcement: a new checker `check-regulatory-return-specs.py` (gate
  `regulatory-return-specs`) fails when a catalogue entry has no spec, a spec has no owning
  service directory, a granular or event spec lacks `synthetic_policy`, or the derived catalogue
  differs from the specs. It starts advisory with a `target_enforce_date` per ADR-0144.

### Delivery check

```bash
ls openbank-libs/governance/returns/*.yaml | wc -l              # >= catalogue size + ceu
grep -c 'REPORT_CATALOG = \[' openbank-admin-ui/src/app/regulatory/page.tsx   # 0 (derived, not hand-kept)
grep -n 'id: regulatory-return-specs' -A4 .github/gates/gates.yaml | grep mode   # mode: enforced
grep -rl 'synthetic_policy: exclude' openbank-libs/governance/returns/ | grep ceu  # ceu spec listed
```

The CEÚ pilot is delivered when the workflow-liveness gauge for the daily CEÚ job exists and a
sandbox filing record carries a ČNB acknowledgement reference.

## Compliance impact

- PCI DSS: not applicable — no cardholder data enters any return in scope.
- DORA: not applicable to this decision; the DORA incident and register-of-information returns are
  only catalogued as return kinds here.
- GDPR: the CEÚ feed transfers personal data of account holders, disposers and beneficial owners
  to ČNB under a legal obligation; the register projection is subject to the ADR-0118 retention
  and erasure model.
- PSD2: not applicable — no payment-initiation or account-access surface changes.
- CNB: CEÚ daily change reporting under zákon č. 300/2016 Sb. and vyhláška č. 30/2017 Sb.; the
  existing SDAT statistical and prudential returns move under declared specs.

## References

- ADR-0031, ADR-0056, ADR-0071, ADR-0097, ADR-0118, ADR-0144, ADR-0180, ADR-0252, ADR-0336, ADR-0340
- #7144 (read-only AI control plane for regulatory reporting)
- ČNB, Centrální evidence účtů — methodology and information for institutions
