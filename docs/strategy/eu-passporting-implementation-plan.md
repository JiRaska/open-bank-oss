# CZ-to-EU FOS and branch expansion — implementation plan

Decision: [ADR-0380](../adr/0380-eu-passporting-common-foundation-and-country-packs.md).
Status: proposed programme; no country/product is approved for launch by this document.
Prepared 2026-11-15 against `b777e9d9d639800aa26f4b391d68c34ac5e90392`.

The deliverable is one Czech-bank platform able to enable any other EU member through FOS,
BRANCH, or both, using a reviewed pack for each selected market. The initial acceptance
scenarios are SK/FOS and AT/BRANCH. The common implementation is built once; country work
repeats for every host and applicable product. Delivery order below is a dependency order,
not an invented calendar estimate or confirmation of supervisor readiness.

## 1. Boundaries and ownership

| Layer | Built once | Repeated/approved for each country |
|---|---|---|
| Platform | Typed market/booking context, admission, durable evidence, approval, version compatibility, observability, migration tooling | Actual bank passport scope, branch registration, products, start date and responsible owners |
| Domain | Credit/KYB/tax/reporting pack interfaces; scheme and document adapter ports | Rules, templates, identifiers, calendars, tax/treaty treatment, reporting schemas and external adapters |
| FOS | Home-book routing, remote onboarding and market restrictions | Host consumer/conduct rules, notifications, servicing and complaint routes |
| BRANCH | Establishment/books, branch-scoped access, reconciliation, home/host reporting scope | Branch identity, local tax/AML/reporting duties, operating model and supervisory evidence |
| Assurance | Shared conformance vectors, E2E scenarios, reconciliation and launch gate | Legal approval, complete input data, test results, external filing/rail acceptance where applicable |

Accountability is by role, not an implied assignment to a person: architecture owns the common
contract; product-catalog owns market admission; party/KYC/KYB own customer facts; ledger/finance
own books; interest/tax own income treatment; reporting owns filings; compliance/legal approve
applicability; operations own launch and exit. A role must be assigned to an actual accountable
person before a slice is READY. No build ticket can substitute for legal approval.

## 2. Shared libraries: concrete reuse and extraction

Use existing `openbank-libs-domain` implementations for money/rounding, business-calendar
interfaces, person-bound `MakerChecker`, audit `DecisionRecord` and identifiers where their
meaning matches. Reuse the runtime outbox/auth/metrics facilities. Keep lending's existing
`openbank-libs-lending` evaluator and KYB's domain pack model.

Add a focused `openbank-libs-market` module with this proposed layout:

```text
src/main/kotlin/com/openbank/libs/market/
  MarketKey.kt             # bank, host, mode and declared activity/product scope
  BookingContext.kt        # typed bank, establishment and book references
  PackReference.kt         # immutable id/version/hash and schema compatibility
  MarketAdmission.kt       # input/result/reasons; no network or storage
  EffectivePackSelection.kt# deterministic interval/scope selection, rejects ambiguity
  CountryPackManifest.kt   # common metadata and references to domain-owned packs
src/testFixtures/...       # shared conformance vectors with synthetic identities
```

The exact file split may change; these are responsibilities, not a requirement for empty files.
Introduce the module together with real product-catalog and account-service consumers in C1/C2.
`service -> libs-market -> libs-domain`; never `libs-domain/runtime -> libs-market`, and never
market dependencies exported transitively to every service. Keep JSON/DB/CDI mapping in the
consuming services. Do not add an all-purpose national-law DSL, tax engine or mutable global registry.

Tests must prove: deterministic results for explicit time/facts; no match/overlap rejects;
FOS and BRANCH are distinct; pack hash canonicalisation; unsupported schemas reject; customer
ids cannot be used as bank ids; and the same fixtures produce the same result in both production
consumers. Run ABI/dependency checks and clean recompilation after interface changes. A country
rate/template update must not require rebuilding the shared library. Public wire schemas remain
versioned independently of library ABI. Register a new released module only according to the
existing release policy; do not manually invent a version/changelog bump.

## 3. Common foundation backlog

Each row is a bounded delivery slice; cross-service slices may require producer/consumer PRs.
Create implementation issues when the ADR is accepted, reusing existing work after verifying
current issue/PR state. Do not label these slices shipped based on this plan or an old ADR.

| ID / accountable owner | Concrete change and existing home | Depends on | Acceptance evidence |
|---|---|---|---|
| C0 / architecture + compliance | Inventory all product commitment entry points and current evidence gaps, including PID-to-party mapping and national identity assertions. Extend ADR-0367 obligation inventory with country/mode/product applicability and owner references. Record an explicit initial product/customer scope. | ADR acceptance | Entry-point matrix includes API, operator, agent, async, renewal and amendment; each obligation has an owner/source/date, or launch is blocked. |
| C1 / architecture + catalogue/account | Introduce `libs-market` contract and executable fixtures; add market-context mapping to catalogue and account boundaries. Reuse existing primitives listed above. | C0 | Both services consume the same fixture suite, including a party with multiple national assertions; unknown/ambiguous required context refuses. Domain purity, ABI and dependency graph pass; no unused new module. |
| C2 / product-catalog | Persist market grants, establishment references, immutable manifests, approval/evidence and state; add OpenAPI admission/administration endpoints, outbox changes and strong-auth four-eyes activation. | C1 | Same person via two credentials cannot self-approve; unknown country, missing passport evidence, expired readiness or wrong product/mode cannot activate. Replica convergence and durable audit proven. |
| C3 / account + onboarding + lending + customer-edge | Enforce admission at every commitment route; bind decision to subject, product revision, booking route and idempotency key. Add a durable pending operation and single-use permit consumption serialised with grant revocation in catalogue; reconcile crash windows idempotently. | C2 | Direct API cannot bypass catalogue filtering; caller cannot spoof jurisdiction; revocation racing commit has one defined outcome; timeout creates no live account/loan; completed retries return original result. |
| C4 / party + KYC/KYB | Expose purpose-limited verified identity, residence/seat/classification and tax-fact references. Preserve national assertions, source evidence, effective/recorded times and adjudication. Reassess on material changes. | C1 | Multiple citizenships and tax residencies survive ingestion. Names and statuses remain scoped and traceable. Required disputed facts block commitment. Missing declarations and statelessness remain distinct. |
| C5 / domain owners + catalogue | Compose domain packs through a shared manifest; schemas, effective intervals, hashes, approvals, compatibility and transactional activation. Adapt lending and KYB registries rather than replacing their domain rules. | C2, C4 | Mixed old/new replicas cannot commit using an unacknowledged pack-set; overlaps/conflicts reject; historical decisions replay; effective-dated servicing changes preserve original terms. |
| C6 / document + communication + dispute | Bind language, template hash, contractual party/establishment and mandatory disclosures to the decision; persist delivery/acceptance evidence. Configure complaint authority, route and clocks. | C3, C5 | Every supported signing flow reproduces exact agreed documents; missing mandatory template blocks signing; complaint deadline uses applicable calendar, not server locale. |
| C7 / interest + tax + party | Complete non-CZK income tax treatment and explicit home/branch/source/treaty resolution. Version evidence and tax decisions at the income event; route remittance by the correct establishment. | C4, C5; B1 for branch | EUR/CZK cases with CZ/non-CZ residents and treaty/no-evidence outcomes produce finance-approved amounts/rounding; no `DEFERRED_FX` reaches a product declared ready; corrections reconcile. |
| C8 / tax-reporting + FINREP/AnaCredit owners | Complete filing-specific wire rendering/schema validation, population-at-period logic, revisions, submission/ack evidence and reporting-scope routing. Remove ignored entity filters. | C4, C5; B2 for branch | Report includes applicable closed/dormant/frozen accounts and historical residency/income; missing source refuses; schema-valid export and reconciled totals; external acknowledgement or approved operational procedure demonstrated. |
| C9 / account + balance + interest + party + FX + reporting | Close GSFT source gaps in owning services; use complete historical principal, accrual, holder shares, identities, exclusions and rates. Add bank-wide DGS aggregation and host payout exchange. | C4, C7, C8 | One depositor with CZ+FOS+branch holdings receives one applicable bank-level limit; joint/special balances and relevant-day rates reconcile; missing data blocks LIVE; test delivery evidence exists. |
| C10 / security + operations | Market-scoped permissions and denial metrics; dependency health, revocation/freshness alarms, banking continuity and data-access/retention controls. Integrate compliance trace instead of parallel dashboard. | C2–C9 as applicable | Unauthorized employee/agent cannot change scope or file reports; outage suspends new commitment while permitted servicing works; stale evidence visibly blocks readiness; recovery drill reconciles state. |
| C11 / account + ledger + reporting | Backward-compatible context/event migrations; evidence-backed CZ attribution, unresolved-record queue, replay and bank-wide reconciliation. Reader-first deployment and rollback rehearsal. | C1, C3, C4; B2 for branch | No fabricated historic branch or timestamps; old clients/events remain readable; all relevant legacy holdings included before launch; rollback retains booked context and pack history. |

C8 must distinguish data assembly, valid wire artefact, transmission and authority acceptance.
A stored submission reference is not proof the authority accepted a file. C9 must not be called
complete merely because a domain renderer passes tests: `GsftSnapshotAdapter`'s live sources
and `requireComplete()` are part of the acceptance path. C7 must use reviewed national/treaty
rules; this plan deliberately supplies no tax rates as defaults for another country.

## 4. FOS-specific backlog (reusable across hosts)

| ID / owner | Deliverable | Dependencies | Acceptance |
|---|---|---|---|
| F1 / catalogue + compliance | FOS grant binds notified activities, host, customer/product scope and evidenced permissible start; booking selects approved CZ establishment. | C2, C5 | SK/FOS approval does not permit AT/FOS, SK/BRANCH or another product; no foreign establishment is invented. |
| F2 / onboarding + document + conduct | Remote sales/identification/disclosure flow applies selected host pack and validated customer facts; local support/complaints are routable. | F1, C3–C6 | Target-country client completes legal-document delivery and onboarding; unsupported identification or missing disclosure cannot fall back to Czech defaults. |
| F3 / tax + reporting + operations | Reconcile host-served population to CZ books, tax/reporting/DGS obligations and legal FOS operating model. | F2, C7–C11 | End-to-end origination, payment, accrual, tax, report, complaint and closure pass; suspension blocks new sales but permits required servicing. |

Host-country requirements are not assumed absent because there is no branch. Legal owns
whether a marketing/distribution arrangement remains FOS or becomes establishment/another
regulated arrangement. A country toggle cannot decide that legal question.

## 5. Branch-specific backlog (reusable across hosts)

| ID / owner | Deliverable | Dependencies | Acceptance |
|---|---|---|---|
| B1 / catalogue + finance + compliance | Establishment registry references, branch start/closure evidence, local identifiers, responsible roles and reporting/tax scopes. Keep bank identity separate from branch and physical office. | C1, C2 | Two offices in one regulatory branch do not become two banks or two DGS limits; home and branch states resolve distinctly. |
| B2 / account + lending + ledger + balance | Propagate immutable booking context to accounts/contracts, journal lines or their immutable parent context, events/projections and reconciliation. Define inter-branch clearing and bank-wide elimination. | B1, C3, C11 schema stage | Branch and bank trial balances agree by currency; deliberate wrong-branch attribution fails even when the bank total balances; replay/reversal retains provenance. |
| B3 / finance + tax/reporting | Add establishment-scoped close, income/cost allocation, reporting populations, host schemas and tax-remittance routes. Reuse existing close/filing ownership. | B2, C7, C8 | Host report reconciles to branch books, home report to correct bank scope; duplicate/omitted intra-bank balances detected; same report code cannot mix entities. |
| B4 / AML + security + operations | Branch staff scope, local AML/FIU responsibilities, support and operating calendars, record access, incident/outsourcing roles and closure/transfer runbook. | B1, C4–C6, C10 | Branch operator cannot access unrelated restricted books; central controls remain effective; lawful servicing and bank-level aggregation survive branch outage/closure. |
| B5 / payments + account (conditional) | Local IBAN/BBAN issuer and routing adapter, national scheme access or approved sponsor model, only if the launch proposition requires them. | B1, C5 | National validation plus real assigned identifiers and routing acceptance; no prefix-only generator; existing CZ IBANs stay valid; unsupported rail is unavailable explicitly. |
| B6 / operations + finance + compliance | Branch E2E and readiness approval; migration/exit rehearsal. | B2–B4, C9, C11; B5 only if applicable | AT/BRANCH scenario reconciles contract -> book -> tax -> host/home report -> DGS; approval names actual unresolved exclusions and scope; no live activation while mandatory gaps remain. |

An AT branch does not imply a new licensed bank or Austrian deposit protection by default.
A separate deployment, if selected, still needs bank-wide aggregation and explicit cross-stack
reconciliation; deployment isolation does not solve the accounting model.

## 6. Country-specific work package (repeat for each host)

Instantiate this checklist once per `host × mode × product/activity × customer scope`, reusing
country artefacts where legally identical. Store public legal rules and synthetic fixtures in
the repository; store actual notifications, bank/branch identifiers, staff identity, contracts
and regulator correspondence in controlled runtime evidence stores. Proposed logical roots:
`openbank-libs/governance/market-access/` for schemas/public applicability manifests, with
domain payloads in the existing owning service's pack directories. These roots are proposed,
not already implemented. The catalogue holds approved activation snapshots, not another editable
copy of every domain rule. Changes to source artefacts produce new immutable hashes.

| Package item | Exact output | Accountable role / verification |
|---|---|---|
| K1 — legal scope | Home licence/activity mapping, host FOS/branch procedure, effective date, product/customer/channel exclusions and source citations | Legal/compliance; required notification/start evidence recorded separately from software state |
| K2 — identity/AML | Typed identity attributes by purpose, issuing schemes and status mappings, acceptable documents/registries, representation/UBO rules, identification methods, local FIU routing and retention | KYC/KYB + AML; realistic positive/negative fixtures and external adapter checks |
| K3 — contract/conduct | Localised pre-contract/contract/DGS/fee disclosures, withdrawal/termination, debt servicing, complaint and ADR-body routes, accessibility | Legal + document/dispute owners; exact template versions and delivery evidence |
| K4 — taxes | Income/source and establishment applicability, residency/treaty evidence, rounding, withholding/exemption, remittance/calendar, information-exchange classifications | Tax; worked cases approved by tax owner, including missing evidence and multi-residency |
| K5 — reporting | Home/host/statistical/tax/DGS obligations with population, threshold, period, schema/version, authority and correction/ack path | Reporting/finance; official validator or agreed recipient acceptance and reconciliation |
| K6 — payments | Available currencies/rails, settlement partner, permitted IBAN issuance, national BBAN validation, cut-offs and calendars | Payments/treasury; scheme/partner evidence, rejection/return and holiday scenarios |
| K7 — operations/data | Local roles, customer support, outsourcing/record access, privacy/retention, incident responsibilities, recovery and exit procedure | Operations/security/privacy; recovery, access and exit drill |
| K8 — launch | Named product scope, common/mode slice evidence, pack hashes, approval, planned start and review dates | Accountable launch owner + legal/compliance; no unresolved mandatory applicability item |

Country rules are not copied from a neighbouring country or inferred from EU membership.
Where official interpretation or regulator acceptance is pending, mark the item UNKNOWN/BLOCKED,
not READY. Applicability changes on a future date need scheduled review and a new activation.

### Identity acceptance cases for C0–C4 and K2

- One synthetic party has two verified citizenships, several documented names (including a
  non-Latin original and a transliteration), and distinct residence and tax-residency facts.
  Admission remains bound to the same party. Contracting and reporting select the required
  name variant and replay it after a profile change.
- The same identifier value from two national schemes remains distinct. Similar names or
  transliterations alone never merge people. A conflicting required fact routes to documented
  adjudication. Screening evaluates aliases according to the applicable reviewed policy.
- Concurrent local statuses, an expired permit, missing citizenship evidence and verified
  statelessness produce distinct outcomes. Do not translate unknown statuses into another
  country's vocabulary. Test a national attribute required for one purpose and unnecessary
  for another, including denied access to the underlying PII.
- Existing single-name and single-nationality rows migrate as sourced assertions with explicit
  unknowns. Historical contracts retain their original evidence. Test Unicode round trips,
  including diacritics and script changes, across persistence, documents and authorised exports.

### First two country instances

| Instance | Country work that remains specific | Explicitly not assumed |
|---|---|---|
| CZ -> SK / FOS | NBS-facing notified activity scope through the home authority; reviewed Slovak consumer/product rules and language artefacts; SK identity/registry support as required; residence/treaty and reportable-account cases; complaint handling; EUR product/rail tests | Czech-language UI, recognised SK IČO or a CZ IBAN proves legal/commercial readiness; SK/BRANCH is authorised by SK/FOS |
| CZ -> AT / BRANCH | FMA host procedure/branch evidence; reviewed Austrian conduct and AML/FIU allocation; German-language artefacts as applicable; local establishment tax/reporting obligations and calendars; branch books, personnel and operating/exit model | Branch is a subsidiary; Austrian taxes apply merely because currency is EUR; each branch receives another deposit-protection limit |
| Every additional EU member / either mode | K1–K8 with the selected country's sources/adapters and explicit mode/product applicability | SK/AT test success is approval for the next country |

At programme start, create two NOT_ENABLED mode entries for each current non-CZ EU member:
AT, BE, BG, CY, DE, DK, EE, ES, FI, FR, GR, HR, HU, IE, IT, LT, LU, LV, MT, NL, PL, PT,
RO, SE, SI, SK. This list is the 2026-11-15 planning baseline, not an unversioned runtime
allowlist; launch code uses the reviewed effective-dated membership reference. Readiness
expands further by product/customer scope. A default/empty country set never means all countries.

## 7. Delivery sequence and release gates

1. **Foundation vertical slice:** C0–C3 with synthetic country data, no live market. Demonstrate
   one product from discovery to refused/accepted commitment with durable evidence and revocation.
2. **Complete domain inputs:** C4–C10, C11 compatibility stages. Build the reusable country
   manifest around actual owners and prove historical reporting completeness before activation.
3. **SK/FOS pilot:** K1–K8 for a selected, explicitly approved product set; F1–F3. Keep lending,
   investment/insurance/pension products disabled until their own controls and passport scope pass.
4. **Branch foundation and AT pilot:** B1–B4 can be implemented alongside domain completion;
   B2 requires the compatible context propagation. Finish AT K1–K8 and B6, with B5 only when
   local issuance/rail participation is required by the selected proposition.
5. **Second-country portability check per mode:** use synthetic fixtures for another member
   to prove there is no SK/AT branch in generic code. This is a technical conformance test,
   not permission for real sales. Actual additional launches repeat K1–K8.

Before marking a market ACTIVE, require all of the following for its scope:

- Legal applicability, licence/passport/branch start conditions and responsible sign-offs recorded.
- Required packs effective, approved and compatible on every committing consumer; missing/expired
  pack, wrong mode, missing fact, rejected approval and stale decision tests pass.
- All relevant direct/async/operator/agent paths enforce admission at commitment; revocation races
  and idempotent retry are tested through real service boundaries.
- Contract/document replay, change-of-residence, renewal, cancellation, refund/repayment and
  complaint scenarios work; suspension preserves explicitly allowed servicing.
- Tax amounts, accounting and historical reporting reconcile, including legacy/closed holdings;
  validated filing and DGS test delivery are executable for all mandatory obligations.
- For BRANCH: branch attribution, access scope, intra-bank movements, scoped close and bank totals
  reconcile; a deliberately misattributed posting makes the test fail.
- Privacy/threat-model review, capacity/latency budget for admission, outage/recovery and rollback
  evidence are current. Existing money-path approval and threat-model rules still apply.

Test artefacts must record commit, configuration/pack hashes, environment, scenario, outcome and
evidence time. Metrics should have bounded dimensions (market/mode/operation/reason), never
customer ids or TINs. Track unresolved attribution, pack skew, admission errors, stale readiness,
filing rejection and reconciliation breaks. A dashboard green without fresh evidence is not a gate.

## 8. Migration, rollback and stop conditions

C11 uses expand -> populate with evidence -> reconcile -> enforce -> retire old writers.
Additive schemas/readers precede context-emitting writers; old events retain unknown context until
an approved enrichment can prove it. Backfill operates idempotently in bounded batches with counts,
source refs and reconciliation. Do not rewrite original journal entries; use provenance/enrichment
records or explicit corrective postings according to the ledger owner.

Rollback first suspends new market commitments, then reverts to an application version compatible
with already-written context/events. Preserve pack history, accepted contracts and statutory
obligations. Maintain servicing and reporting or invoke their separate continuity procedures.
A change of booking establishment requires an approved transfer workflow, customer notice/consent
where applicable, reconciliation and an effective date; it is never a bulk country-field update.

Stop a launch if any mandatory legal item is unknown, any required data population is incomplete,
any required filing cannot be produced/accepted through the approved channel, or branch totals
cannot reconcile. Do not relax coverage, disable admission or enable a Czech fallback to obtain a
successful pilot. Programme completion means common/mode capabilities plus evidence-backed
readiness for the approved markets; it never means that every EU country is automatically active.

## 9. Documentation-change validation and subsequent PR discipline

This proposal changes only the ADR and this plan. It activates no passport, product, flag or
country. Validate ADR front matter/references, generated registry behaviour, local links and
whitespace. Runtime builds and country E2E belong to their implementation slices, not to a
claim that this design is already implemented.

Each implementation PR links its accepted scope issue, changes OpenAPI/event schemas where
needed, includes migration rollback notes, and runs relevant unit/contract/integration/Quarkus
build and governance checks. Shared interface changes require clean consumer recompilation;
new admission behaviour needs real HTTP/DB tests, not only a mocked evaluator. Changes touching
money paths retain required approvals and threat models. Verify existing tax/GSFT follow-ups
before opening duplicate issues; the repository assessment is dated, not a live backlog status.
