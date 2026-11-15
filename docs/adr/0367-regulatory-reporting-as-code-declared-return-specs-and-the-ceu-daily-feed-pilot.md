---
date: 2026-11-08
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
followup: "#16356 — isolated execution, source contracts and multi-context workbench; family delivery also tracked by #15356 and #7144"
tags: [regulatory-reporting, compliance, governance, ai-agents]
summary: "Return specs and entity/regime contexts govern reproducible backend filings. Reporting uses isolated data and bounded workers, with customer-SLO evidence before rollout. The officer workbench derives coverage from specs; AI never files."
---

# ADR-0367 — Regulatory reporting as code: declared return specs and the CEÚ daily feed pilot

## Context

### Original assessment (2026-11-08)

ADR-0097 decided that FINREP/COREP are derived from the attested close by a stateless
`openbank-finrep-service`. ADR-0180 decided that a statutory filing is a system of record in
`openbank-tax-reporting-service`; ADR-0336 (proposed) puts it on one lifecycle (assemble,
validate, four-eyes attest, record submission, deadline gauge), and ADR-0340 (proposed) would add
CRS/FATCA as a further return family there. Each of those decides **where one family of returns lives**. None of them decides
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

## Amendment — operational isolation and reporting workbench (2026-11-15)

### Critical assessment and current delivery

Backend computation alone does not protect customer services. Moving a synchronous account scan
from the BFF into a backend merely moves the load. A separate cluster also leaves shared databases,
brokers, storage, networking and authentication as possible contention points. Isolation must cover
the complete source-to-artifact path and be demonstrated under failure, not inferred from topology.

The UI catalogue currently lists 11 report families; the obligations register contains 70 duties.
These are different units, not a coverage fraction. The statutory catalogue also includes pension
returns, whose presence does not establish applicability to a banking entity. Existing BFF generators
scan operational APIs, cap account rosters and use current account state for historical requests.
These previews are not evidence of a complete, reproducible filing for three million clients.
The CEÚ projection and attested ledger snapshots are reusable foundations, not proof of fleet-wide
isolation. The return-spec catalogue and checker in the original decision remain incomplete.

### Binding constraints

8. **Separate reporting data and execution.** Keep domain ownership in existing services, but run
   ingestion, computation and export workers with independent deployment, bounded queues, database
   pools and CPU/memory/IO budgets. Customer requests must never wait for reporting. No report job,
   preview, export or drill-down may fall back to a synchronous scan of operational services.
   Low-priority scheduling is additional protection, not sufficient isolation.
9. **Bounded asynchronous acquisition.** Reuse transactional outbox/domain events where they carry
   the required facts; declare a versioned source contract for missing fields. Measure the overhead
   of publication too. Backfills and initial snapshots use a bounded, resumable extraction path,
   separately budgeted and paused when client-service headroom is exhausted. A stopped consumer must
   not retain unbounded WAL/outbox data on the operational database. Specify retention, disk budgets,
   quotas and an independent replay archive. If retention is exceeded, mark the reporting dataset
   incomplete and rebuild through that bounded path; never silently skip a gap or block banking.
10. **Reproducible evidence.** Freeze each run against an immutable snapshot with per-source
    watermarks, completeness checks and reconciliation to the ledger/control totals. Preserve valid
    and recorded times for historical corrections. Store spec/taxonomy/rule versions, source offsets,
    snapshot and artifact hashes, rounding and FX policy, exclusion counts and validation results.
    Money uses decimal arithmetic with explicit currency; missing data is unknown, never zero.
    Retries are idempotent; corrections create new revisions. Ingestion lag is shown separately from
    business as-of time. A completeness or reconciliation failure blocks attestation and filing.
11. **Least privilege and lifecycle.** Long-running jobs use scoped service identities, not the
    initiating operator's token. Authorize job creation and every artifact download separately;
    enforce entity boundaries, four-eyes approval and auditable access. Reporting copies have explicit
    retention, encryption and erasure/legal-retention treatment under ADR-0118. AI cannot certify
    completeness or bypass a failing validation.
12. **Explicit identity and status.** Distinguish obligation, return definition, applicability
    (entity, solo/consolidated scope, effective dates), reporting period, filing and revision.
    Keep implementation capability, data readiness, deadline verification and submission/ACK state
    independent. Latest-revision selection always includes entity. Unknown applicability remains
    visible; it is not equivalent to not applicable. Do not derive deadlines from display frequency.
13. **Officer workbench.** Default to compact tables with search, combined authority/area/coverage/
    verification filters, bounded pages and accessible controls. Subsequent filing views add entity,
    period, owner and lifecycle filters, saved views and explicit missing-data states. Preview the
    actual artifact's sheets/cells and validation findings, with separate metadata and lineage tabs.
    XLSX is an officer working format; it does not replace the authority's required wire format.
    Large exports run asynchronously with progress/cancellation, expire under retention policy, and
    read the same frozen dataset as the preview. Never send millions of client rows to the browser.

### Multiple institutions, FOS and foreign branches

Reuse ADR-0380's `BankLegalEntityId`, `EstablishmentId`, `MarketKey` and `BookingContext`;
do not invent a parallel bank/branch registry. Its initial CZ-to-EU scope is narrower than this
reporting model: reporting must represent multiple licensed companies, incoming foreign-bank
branches and outgoing operations. EU/EEA passporting and third-country branch authorisation must
not share an implicit permission rule. Third-country and EEA applicability require explicit review.
A branch is an establishment of a legal entity, not automatically a new legal entity; a subsidiary
with its own licence is a separate entity. FOS and BRANCH may coexist in the same host country.

A versioned `ReportingContext` references the legal entity, establishment where relevant,
reporting-unit identifier, home/host jurisdictions, operating mode (domestic/FOS/branch), authority,
solo/sub-consolidated/consolidated perimeter and its version, and effective interval. A reporting
unit may be a bank, branch or approved group perimeter. Bind it to the applicable reviewed country
pack and return-spec version. The reporting model consumes these facts; it does not grant a licence.
An applicability decision stores applicable/not-applicable/unknown, rule/evidence versions, reason,
reviewer and as-of time. Both applicable home and host obligations can attach to one business flow.
Do not assume FOS means no host reporting: CESOP is one documented counterexample; each return
needs its own reviewed applicability rule.

The durable filing identity includes reporting context, return/spec, period or event window and
revision. Deduplication, idempotency, snapshot lookup, caches, artifact paths, authorization and
latest-revision selection must use that identity. The first UI fix preserves today's `entityId`;
it does **not** claim that the current API already distinguishes two regimes of the same entity.
The next backend slice must version the API and persist the context before enabling those regimes.
Legacy records retain their original identity and display context as unknown until an evidenced
mapping is approved; never silently assign domestic/solo or rewrite historical filings.

Keep historical booking context on source facts. A transfer from FOS to a branch requires
an effective-dated, evidenced reassignment and reconciled opening/closing balances. Re-running
an earlier period uses that period's context. Consolidation explicitly versions membership and
eliminations; it does not sum already overlapping bank and branch filings. Deposit-guarantee
aggregation follows the applicable legal-bank/scheme perimeter, not a UI branch filter.

The workbench will select reporting context before assembly and show company, unit, mode,
home/host state and perimeter on each filing and artifact. Tenant/permission boundaries apply
server-side, with per-institution quotas and fair scheduling so one entity's backfill cannot starve
another's deadline. Filters improve navigation; they are not authorization controls.

Required scenario tests: two banks using the same return code/period; one bank simultaneously
using FOS and a branch in one host; an incoming EU branch versus a third-country branch; a
mid-period route transfer and historical correction; solo and consolidated runs with overlapping
members; unknown legacy context; cross-context job/artifact access denial. Freeze reviewed source
references and applicable dates in each country pack before enabling a regime.

Sources for applicability review (not a complete inventory):
- [ČNB: reporting duties of banks and foreign-bank branches](https://www.cnb.cz/cs/statistika/vykaznictvi-sber-dat/vykaznictvi-banky-PZB/index.html).
- [Czech Financial Administration: CESOP, including providers without a local branch](https://financnisprava.gov.cz/cs/mezinarodni-spoluprace/mezinarodni-spoluprace-a-dph/cesop/zakladni-informace-k-cesop).

### Source contracts before additional report implementation

| Family | Authoritative input and acquisition | Required proof before filing |
| --- | --- | --- |
| FINREP / COREP / liquidity | Attested ledger close; risk, capital and treasury snapshots owned by their services | Balance reconciliation, taxonomy and scope; existing templates are partial coverage |
| SDAT / payment statistics | Versioned settled-payment facts, reversals and instrument/channel classifications in reporting projections | Authority definitions, period boundaries, complete populations and deduplication |
| Deposits / GSFT | Historical account and party relationships, balances, accrued interest and eligibility | As-of joins, depositor aggregation, FX and exclusion rules, mandated file validation |
| CEÚ / credit registers | Bitemporal account/party/mandate or credit projections | Event completeness, corrections, initial-load reconciliation and authority acknowledgement |
| CRS / FATCA | Effective-dated tax residency, reportability evidence, account balances and income | Missing tax profiles block affected scope; currency conversion and schema validation |
| AML / DORA incidents | Case-management and incident lifecycle records with regulatory classification | Authorized classification and event-specific deadlines; severity labels alone are insufficient |
| DORA information register | Contract, supplier, ICT service and dependency records | Complete entity relationships and versioned authority templates |

For each concrete return, the owning team must record exact source fields/endpoints/events, keys,
history and retention, units/currencies, data owner, quality checks, lawful access and missing inputs
in its return spec before implementing calculations. This table identifies source domains, not a
claim that all necessary contracts or legally complete mappings already exist.

### Delivery sequence and acceptance

1. Workbench foundation: table/filter the obligations register and preserve entity identity when
   choosing latest revisions. This delivers presentation correctness only; it adds no isolation.
2. Source-contract pilot: select one return with bounded scope and existing immutable inputs;
   document missing fields, implement independent ingestion/snapshot manifests and reconciliations.
3. Isolated execution: deploy queued workers, artifact storage, quotas, cancellation, liveness and
   backlog/deadline monitoring. No production switch before load and failure evidence is reviewed.
4. Shadow comparison: compare snapshot output to approved reference fixtures and independent control
   totals, test corrections/replay/duplicates and synthetic exclusions. Preserve the old artifacts;
   rollback stops new workers and filing rather than re-enabling operational scans.
5. Expand family by family; derive the catalogue from versioned specs and retire BFF arithmetic only
   after each replacement has passed reconciliation and lifecycle tests.

Three million clients is a population target, not a capacity specification. Record accounts/client,
transactions/day and peak rate, history depth, correction rate, concurrent jobs, export size and
regulatory deadline windows. Run realistic peak customer traffic concurrently with reporting peak,
backfill and replay. Test reporting database/worker/broker-consumer outages, full reporting disks,
source lag and recovery/catch-up. Capture baseline and loaded customer latency percentiles, errors,
availability and source resource saturation against agreed existing service SLOs and explicit
incremental resource budgets. Reporting recovery must also meet its own deadlines; protect capacity
for urgent filings rather than allowing permanent starvation. Until measured, capacity and
non-interference are **unproven**. Absolute zero resource cost and absolute availability are not
credible guarantees; enforce bounded overhead and contain reporting failures to reporting.

Delivery remains `partial`. UI tests cannot certify source accuracy, regulatory completeness or
operational isolation. Those require separate contracts, reconciliations, deployed controls and
reviewed load/failure evidence.

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

**Intended benefits (subject to the delivery checks below)**
- A return that exists in the UI provably has a backend, an owner and a deadline gauge.
- CEÚ provides a daily filing lifecycle with evidenced synthetic exclusions.
- Regulatory change becomes a reviewed diff to a spec, which is what lets an AI agent draft it.

**Negative**
- Six BFF returns must be migrated; until then the specs carry declared debt.
- The register projection duplicates account, party and mandate facts inside
  tax-reporting-service, deliberately, as a reporting copy fed by events (ADR-0009).

**Neutral**
- Planned enforcement (not yet shipped): a new checker `check-regulatory-return-specs.py` (gate
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
- DORA: reporting isolation, recovery and dependency controls contribute to operational resilience;
  their implementation and failure-test evidence remain pending. Incident and register-of-information
  filings still require their own reviewed classification, applicability and submission rules.
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
