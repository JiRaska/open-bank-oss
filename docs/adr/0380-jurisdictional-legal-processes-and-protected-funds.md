---
date: 2026-11-15
decision-status: accepted
delivery-status: partial
authors: [OpenBank contributors]
supersedes: []
superseded-by: []
delivery-repos: []
followup: "#16324 — Shared revision fencing and design contracts exist; operational owners, adapters, protection rules and trusted activation evidence remain unimplemented."
tags: [accounts, payments, authz, compliance]
summary: "Separate legal cases from operational enforcement, with versioned jurisdiction packs for CZ, SK and DE; enforce protected funds and scoped legal capacity through existing balance ownership and payment admission."
---

# ADR-0380 — Jurisdictional legal processes, protected funds and legal capacity

## Context

ADR-0039 assigns booked money to Ledger and reservations to Balance; ADR-0374,
ADR-0375 and ADR-0377 define common admission for instructed value movements.
ADR-0232 and ADR-0284 distinguish voluntary delegation from legal-entity mandates.
None defines legally compelled disclosure, attachment, protected funds or court-defined
capacity and representation of natural persons. This ADR extends those boundaries;
it does not replace their services or introduce a second admission chain.

The architectural question is where jurisdiction-specific legal effects live, and how
they constrain existing money and authority owners without turning every country into
a fork of the bank. A transport adapter plus an account freeze is insufficient even
for CZ/SK. Germany additionally requires protected-credit cohorts, calendar-month
transitions and representation independent of a consent reservation.

Repository inspection against main commit `0ed07139a6` found reusable foundations,
not a delivered legal-process capability:

| Existing surface | Evidence and limit |
| --- | --- |
| Account freeze | `openbank-account-service/src/main/kotlin/com/openbank/account/domain/model/Account.kt`: one status and freeze origin; no independent legal-effect set. |
| Freeze notification | `openbank-account-service/src/main/kotlin/com/openbank/account/application/usecase/AccountService.kt`: freeze requests notify customers; unsuitable for orders with notification restrictions. |
| Balance reservation | `openbank-balance-service/src/main/kotlin/com/openbank/balance/domain/model/Balance.kt`: holds carry amount, reason, reference and optional expiry; no statutory protection cohorts or creditor priority. |
| Party mandates | `openbank-party-service/src/main/kotlin/com/openbank/party/domain/model/PartyMandate.kt`: human-to-legal-entity authority; not a natural person's court-defined capacity. |
| Voluntary delegation | `openbank-delegation-service/src/main/kotlin/com/openbank/delegation/domain/model/DelegationGrant.kt`: useful grants and limits, but a court appointment is not the client's revocable consent. |
| Pack activation | `openbank-lending-service/src/main/kotlin/com/openbank/lending/application/usecase/CompliancePackActivationService.kt`: reuse version/hash/approval patterns, not lending semantics. |

Adjacent decisions remain authoritative within their scopes:

| Decisions | Boundary retained; additional choice here |
| --- | --- |
| ADR-0002, ADR-0039 | Hexagonal domains and single money owners; add typed legal effects. |
| ADR-0052 | ADR records architecture; implementation slices belong in issues. |
| ADR-0101, ADR-0115 | Temporal and deterministic simulation; add legal workflows and clock scenarios. |
| ADR-0118, ADR-0161, ADR-0162 | Retention, object storage and document rendering; add original service evidence, purpose restrictions and legal retention holds. |
| ADR-0152, ADR-0337 | One regulated entity per deployment and separate books; jurisdiction is not a tenant. |
| ADR-0317 | Shared legal contracts live in `openbank-libs-legal`; platform identifiers stay in `libs-domain`, with no core-to-context dependency. |
| ADR-0207, ADR-0318 | Ledger business dates and financial allocation primitives; legal civil calendars and statutory priority remain explicit inputs. |
| ADR-0232, ADR-0249, ADR-0284, ADR-0308 | Delegation, exposure, entity mandates and historic authority evidence; add natural-person legal constraints without conflating their sources. |
| ADR-0247 | Fraud adverse-state handling; a fraud signal is not an enforceable legal instrument. |
| ADR-0346, ADR-0352, ADR-0354, ADR-0355 | Versioned runtime data, product IR, compliance packs and workflow compatibility; legal rights are not commercial product eligibility or arbitrary executable pack code. |
| ADR-0364, ADR-0367 | Domain ownership and regulatory reporting; order-specific disclosure is not another statutory-return engine. |
| ADR-0374, ADR-0375, ADR-0377 | Common admission, authority and value-sink coverage; extend the authority and standing checks instead of bypassing them. |
| ADR-0379 | Obligation/control/evidence tracing; legal packs contribute evidence to that system. |

Research date: **2026-11-15**. No programme launch date or regulatory delivery deadline
has been supplied. Each jurisdiction's production activation is conditional on legal
validation and the delivery evidence below. This is an architectural design, not a
claim that the repository or any deployment already complies with all three laws.

## Decision

### 1. Separate legal instruments, their effects and operational owners

Introduce `openbank-legal-process-service` as the owner of legal cases, instrument
revisions, verified issuing authority, service evidence, deadlines and interpretation
into typed effects. Reuse existing document storage, audit, outbox and Temporal
infrastructure. Do not add a new workflow platform or a synchronous case-service call
to every payment.

| Concept | Authoritative owner | Meaning |
| --- | --- | --- |
| LegalCase / LegalInstrument | Legal Process | Proceeding, original evidence, validity, service, amendments, disputes and deadlines. |
| LegalEffect | Legal Process | Scoped obligation, legal basis, priority, applicable law and desired revision; application acknowledgements are separate from legal validity. |
| CapacityConstraint / LegalRepresentation | Party | What the person can do themselves, who may represent them, required consent or court approval, scope and validity. |
| AccountProtectionRegime | Account | Protected-account status, linked accounts and activation/termination facts. |
| ProtectedFundsAllocation / LegalReservation | Balance | Amounts, cohorts, consumption, encumbrances and creditor allocations; one concurrency owner. |
| Remittance instruction / posting | Transaction / Ledger | Controlled execution and immutable booked financial consequences. |
| DisclosureRequest / Response | Legal Process | Purpose-limited snapshot, response approval, delivery and receipt. |

Effects distinguish disclosure, attachment, disbursement, release, stay, protected-funds
entitlement, account-regime change, representation, consent requirement and notification
restriction. A stay may suspend remittance while retaining attachment. Cancellation
removes only the named effect. No aggregate `hasLegalProblem` boolean controls banking.
Preservation of evidence and freezing money are separate legal holds.

Maintain issuer, subject identity, instrument id/revision, original hash, service
channel and receipt, `issuedAt`, `servedAt`, `effectiveFrom/To`, `knownAt`, `appliedAt`,
legal timezone/calendar, pack revision and interpretation approvals. Service and legal
effect are not inferred from when a queue happened to process the message.

Resolve applicable law using the servicing legal entity/branch, account and proceeding,
not nationality, UI language or IBAN alone. Foreign recognition and instruments such as
the European Account Preservation Order are explicit overlays, not automatic acceptance
of any foreign court document. ADR-0152 remains: no `tenant_id` or multi-bank deployment.

### 2. Make protection and allocation first-class money semantics

Balance owns a single atomic allocation calculation for affected balances. Ordinary
payment reservations and legal encumbrances are different types with distinct release
authority. Payment TTLs, the admission chain's HELD timeout and a payment cover-release
event cannot release a legal attachment. A protection exempts money only from the
specified process; it does not override unrelated sanctions or fraud controls.

Represent actual funded amounts and competing claims, not a subtraction per case.
Overlapping claims cannot make money disappear twice. Allocation respects statutory
priority, equal-rank sharing, protected amounts, currency and rounding. A legal order
never draws on an unused overdraft. Reuse existing arithmetic primitives with legal
priority supplied explicitly, not a UUID sort order.

For one order affecting multiple accounts, Balance owns the order-wide allocation
budget and serializes budget changes with reservations. Partitioned deployments use
fenced sub-budgets whose sum cannot exceed the cap; a saga may not independently reserve
the entire claim on each account. Cross-service changes use durable outbox, idempotent
commands and acknowledgement reconciliation, not a fictitious distributed transaction.
Every effect has a monotonic revision; an old release cannot undo a new restriction.

Protection supports distinct operators, rather than one global exemption amount:

- source-qualified income routed to a linked protected account;
- fixed or income-derived exemptions and their claim/notice conditions;
- period-based protection of actual credits, carry-forward cohorts and consumption order;
- certificate/court adjustments, effective dates and permitted retrospective treatment;
- co-owner shares, transfer of attachment to debtor shares and remittance moratoria.

For DE, preserve each credit cohort's credited time, protected remainder, legal expiry
and consumption order. Unused *funded credit* may carry; unused entitlement creates no
money. Reserve and consume entitlement atomically with payment reservations and commit;
release restores only what remains legally valid. Failed payments, reversals, refunds,
fees, set-off and backdated corrections each need explicit jurisdictional treatment.
Recomputation preserves original decisions and adds corrective events/postings; it does
not rewrite ledger history. The legal calendar month is not Ledger's business date.

Calculate date-dependent rights on reads/commands as well as durable timer processing,
so a missed month-end scheduler cannot extend a restriction or destroy protection.
An unavailable or stale projection has an explicit outcome and escalation, never an
empty list interpreted as unrestricted. Define per-channel ingestion freshness and
reconciliation evidence; an event sequence alone cannot prove that an external mailbox
contains no unprocessed order. Containment must be scoped and legally justified, not
an automatic freeze of all customers during a connector outage.

### 3. Extend common authority admission without equating guardianship to incapacity

Party stores capacity constraints and representation independently. A diagnosis is not
an authorization rule. A guardian does not own the represented person's money. Record
specific acts, account/asset scope, monetary thresholds, joint representation, required
consents/court approvals, source evidence, validity and revocation. Distinguish supported
decision-making, voluntary power of attorney, court-appointed representation and minors.
Institutional guardians require an authenticated human-to-institution authority chain.

This proposal narrows ADR-0375's owner-or-active-grant rule for affected natural persons:
ownership identifies the principal but is not sufficient authority for an act outside
that person's legal capacity. Court authority is an additional evidenced authority
source, not a voluntary DelegationGrant. ADR-0232 consent and SCA cannot substitute for
court consent or expand the court's appointment. Retain the existing common chain;
implementation must update its contracts, authority graph and live caller coverage.

At payment execution and release, evaluate the current actor's authority and applicable
legal effects through the existing authority/standing stages, and reserve funds through
Balance. Include account opening/closing, delegation changes, credit agreements and
information access where their legal rules apply, not just payments. Preserve rights to
ordinary daily acts where law and the individual order allow them; no blanket FROZEN
party status for limited capacity. Expiry of evidence triggers the legally specified
review, not an assumption that capacity or a court order automatically vanished.

A forced remittance has an explicit legal-instrument authority, lawful beneficiary,
amount cap and single-use execution reference. It is neither customer self-authority
nor a generic service-account bypass. Applicable checks and any legally required
exceptions are named, evidenced and tested. Staff approval cannot override a binding
restriction. All instructed value sinks under ADR-0377 participate, including APIs,
standing orders, direct debit, cards and cash where supported. Existing chain delivery
is not assumed complete merely because its ADR exists.

### 4. Separate delivery channels from legal semantics

Channel adapters capture originals and receipts before parsing; authenticate sender,
validate integrity/schema, deduplicate and map to versioned canonical instruments.
Authenticated transport alone does not prove the sender's competence, correct debtor
identity, enforceability or requested disclosure scope. Parser confidence is not legal
approval. Ambiguous identity or conflicting attachments go to a deadline-aware legal
operations queue with a controlled, legally justified provisional action if required.

CZ ISDS is a reference adapter, not the universal envelope. Preserve message and delivery
evidence as well as XML/PDF attachments. Support the relevant bank/executor schema by
message class; a police request need not use the executor's structured format. Test
against the supported ISDS test facilities before activation. SK's bank/executor exchange
and Germany's supported electronic or paper service channels are separate adapters.
Do not claim all orders arrive as structured XML or through a single national mailbox.

Disclosure uses a legally scoped point-in-time query and evidence of what was returned.
Separate case access, interpretation approval, response release and money authority.
Senders receive only authorized fields. Customer notification follows an explicit
notice permission/deadline, not the current automatic freeze notification path.
Protect raw orders and potentially sensitive capacity evidence with least privilege,
retention schedules and legal preservation exceptions; Kafka and routine telemetry carry
references and operational codes rather than full documents or medical details.

An unknown outbound outcome is reconciled using message identity/receipts before retry.
Report accepted, served, applied and remitted separately; a disabled connector or a
stored document is never reported as successful compliance.

### 5. Deliver country packs with explicit capabilities and effective dates

A jurisdiction pack contains legal source/version references, effective intervals,
calendar rules, supported instruments and transports, validation schemas, protection
operators, priority rules, evidence requirements, response mappings and executable
fixtures. Parameters are effective-dated data, not hardcoded current exemption amounts.

Reuse ADR-0346/ADR-0354 activation mechanics: schema validation, content hash, compatible
runtime version, simulation, maker-checker approval and activation evidence. Packs select
closed, reviewed domain operators; they do not upload arbitrary code or hot-swap JARs.
A new country's genuinely new legal semantics require reviewed runtime changes and an
ADR amendment if ownership changes. Country expansion is not promised to be config-only.

Pin the pack revision used for each decision, but apply later law to existing cases
when its transition rules require it. Preserve both old and new calculations. Withdrawal
of a defective pack prevents new use and triggers case reconciliation; rollback cannot
undo a served instrument or a completed payment. Workflow history compatibility remains
ADR-0355's responsibility, independently of the legal-rule revision.

| Country | Protection and authority requirements | Consequence for the shared design |
| --- | --- | --- |
| CZ | Distinct protected account linked to an attached account, qualified income sources and prescribed transfer timing; individual court-defined capacity, remaining daily acts and scoped guardianship. | Account linkage and income provenance, fee restrictions, separate self/representative permissions; not a DE-style monthly quota. |
| SK | Exempt funds under Exekucny poriadok §104, including a one-account fixed exemption and qualifying income after notice; order lifecycle and same-day creditor priority under §§95–104; court-defined representation and approvals for nonordinary property acts. | Evidence-conditioned exemptions, notice dates, equal-rank allocation and separate attachment/remittance/stay effects; do not invent a Czech-style protected-account product. |
| DE | P-Konto conversion and credit-only operation under ZPO §850k; actual protected credit carries for three following calendar months, oldest credited funds consumed first (§899); moratorium (§900), certificates (§903), joint-account treatment (§850l). Representation (§1823 BGB) and consent reservation (§1825) are separate. | Funded cohorts and legal calendars, conversion timing/retrospective eligibility, co-owner allocation, certificate deadlines, and consent constraints independently of appointment. |

DE extends the original CZ/SK-oriented model in four material ways: period cohorts,
protection conversion/backdating, co-owner transfer semantics, and independent
representation/consent. These are reusable capabilities, not German branches in every
payment service. Certificate increases and court adjustments must be supported before
calling DE protection complete; a base monthly threshold alone is insufficient.

Joint ownership is not delegation. If joint accounts are not offered, mark them outside
the product scope and block their creation; do not label joint-account enforcement
implemented. Before offering them, prove the §850l allocation and transfer scenarios.
Similarly, a local uniqueness constraint proves only one protected account in this
bank: countrywide uniqueness relies on the legally prescribed declaration/verification
procedure, not a nonexistent global customer database.

### 6. Acceptance scenarios and delivery sequence

Implement in slices: common evidence/effect model and Party authority contracts;
Balance protection/allocation and common admission integration; CZ adapter and pack;
SK and DE packs/adapters; then additional jurisdictions. Do not enable execution-only
country support while protection or representation remains a future phase.

Each fixture binds a versioned legal source to inputs, clock, expected decision,
allocated/protected amounts, allowed actor and observable postings/receipts. At minimum:

| Scenario group | Required proof |
| --- | --- |
| CZ income | Qualified versus unrelated payer, linked-account transfer deadline, protection and permitted fees; duplicate notification does not duplicate funds. |
| SK allocation | Competing orders received on the same day share lawfully; fixed exemption is not multiplied across accounts; income notice and stay variants differ. |
| DE periods | Credits at month boundary, partial consumption, three-month carry, FIFO, expiry and moratorium interplay; no carry of an unfunded allowance. |
| DE conversion | Already attached/debit-balance account, statutory conversion timing, eligible retrospective protection and termination; certificate changes apply on their legal clock. |
| DE shared ownership | Debtor/nondebtor shares, agreed split and transfer of attachment only where applicable; mandatory before joint-account launch. |
| All three: capacity | Daily permitted act succeeds, excluded act fails, scoped guardian acts, missing court approval fails; appointment alone does not imply total incapacity. |
| Combined rights | Protected account plus limited capacity: both actor authority and funds protection apply, including a guardian's attempted creditor remittance. |
| Order races | Two orders, duplicate/reordered revisions, cancellation of one, incoming credit racing payment/attachment and changing pack mid-case. |
| Failure/recovery | Crash between desired/applied effect, lost acknowledgement, missed timer, stale mailbox/projection, failed remittance and uncertain outbound receipt. |
| Reachability | Each instructed value sink and nonpayment restricted operation exercises the production guard; direct booking cannot bypass it. |
| Privacy | Unauthorized disclosure and prohibited notice fail; evidence is sufficient without leaking raw case data. |

Tests include real database concurrency/outbox integration and replay of workflow
histories, not only mocked domain tests. Simulations use synthetic identities. Prove
service boundaries and receipts with channel conformance tests and legally reviewed
examples. Track overdue application, unapplied revisions, allocation mismatch, missed
protection deadlines and disclosure delivery separately. Every finding has an owner;
manual review is a supported operating path, not an unmonitored exception queue.

## Alternatives considered

- **Country-specific legal services and payment forks.** Initially easier to mirror
  local vocabulary and delivery channels, but they duplicate balance/authority logic,
  diverge on overlapping rights and make every rail change a cross-country migration.
  Keep local semantics in packs and adapters with shared ownership invariants instead.
- **Existing account freeze plus voluntary delegation and ordinary holds.** Minimizes
  new storage, but cannot express independent orders, protected funds, statutory expiry
  rules or nonconsensual representation. Reject as the legal model; reuse underlying
  infrastructure after introducing the necessary domain types.
- **A universal central policy interpreter on every payment.** Maximizes runtime
  flexibility, but adds a synchronous failure domain and permits arbitrary money rules
  outside their concurrency owner. Choose typed operators at existing owners instead.
- **Extend only document/reporting workflows.** Good for receipt and disclosure, but
  those services do not own spendability or legal authority. Reuse their ports without
  making them money enforcement authorities.

## Consequences

**Positive:** one explainable path connects a served instrument to authority, protected
funds, allocation and execution; new countries can reuse mechanisms without equating
their laws. Protection and legal agency improve ordinary banking correctness as well.

**Negative:** adds one stateful service, richer Balance and Party models, legal operations
ownership, conformance work and jurisdiction maintenance. It requires admission contract
changes and migration of active cases/holds; it is not an adapter-only feature.

**Enforcement:** gate `legal-process-contracts` is registered as enforced for design
contract integrity and refusal of unsupported readiness claims, implemented by
`.github/scripts/check-legal-process-contracts.py`. **Full evidence verification is
required before the first production country activation**. Its eventual activation mode
must check the capability manifest, legal-source/effective-date
bindings, actually executed jurisdiction fixtures and production-caller coverage. Missing,
skipped or stale evidence fails; a manifest saying "supported" is not proof. Existing
`enforcement-reachability` and instructed-value-movement gates are enforced for their
current scopes; extend them for these guards. They do not already enforce this ADR.
Feed obligation/control/evidence into ADR-0379. The current checker validates 63 design
requirements across CZ/SK/DE; its evidence mode refuses readiness because trusted
execution/approval verification has not been implemented. It cannot activate a country.
The shared `LegalEffectRevisionGuard` in `openbank-libs-legal` implements pure revision
fencing, with duplicate, stale and conflicting delivery tests. It has no production
consumer yet and does not itself constrain a payment or prove transaction atomicity.
Party, Account and Balance now implement domain policies for scoped debit authority,
protection-regime linkage, and funded reservation with already-resolved attachments.
The simulation calls those policies together for CZ, SK and DE using synthetic verified
facts, including a DE ordinary-allowance cohort crossing a Berlin calendar boundary.
This scope assumes current court effects and preallocated protections. It does not
provide intake, persistence, trusted identity resolution, court-approval workflows
or production payment enforcement. Delivery is partial and tracked in issue #16324.

Identity context belongs to Party/PID. The legal process needs an unambiguous verified
PartyId and an opaque evidence reference. Name variants, scripts, citizenship and
national registry attributes help identity resolution. They neither confer authority
to act nor determine the applicable legal jurisdiction. Ambiguity requires review
before any effect is applied.

Money-path changes require ADR-0030's two approvals and updated models for Account,
Balance, Transaction, Ledger and every affected rail (existing files under
`docs/threat-models/`). Add a model for Legal Process and explicit Party capacity/agency
coverage. Threats include forged orders, wrong-person matches, unlawful disclosure,
excessive restraint, protection depletion, stale authority, duplicate remittance and
staff bypass. No production activation until these controls and legal acceptance are
proved. Expand `money_path_services` appropriately when the new enforcement service is
introduced; absence from today's list is not an exemption.

## Delivery check

From repository root, run the following delivery interface. The current checker
implements design validation and a fail-closed refusal; trusted operational evidence
verification remains to be delivered. Default-mode success is not this delivery check.

```bash
python3 .github/scripts/check-legal-process-contracts.py --require-jurisdictions CZ,SK,DE --require-evidence # LEGAL_PROCESS_CONTRACTS_OK
```

Expected: exit 0 and exactly `LEGAL_PROCESS_CONTRACTS_OK` after validating an enforced
registration in `gates.yaml`, all mandatory capabilities for the declared product scope,
executed fixtures above, live admission bindings, real-DB concurrency evidence, connector
conformance, current legal sign-off and the commit/pack hashes those proofs cover.
An absent checker, unsupported required capability, missing source transition, skipped
suite or absent legal approval exits nonzero. Today the evidence-verification refusal
makes this fail, correctly falsifying any claim of delivered support. Individual country
activation may precede completion of all three only when that country's full scoped evidence passes;
full delivery of this ADR requires CZ, SK and DE.

## Compliance impact

- PCI DSS: no new card-data collection; card debit enforcement reuses existing payment
  paths. Channel implementation must preserve existing card-data boundaries.
- DORA: connector failure, replay, deadline monitoring and recovery evidence extend
  operational resilience controls; this ADR is not an attestation.
- GDPR: orders and capacity evidence require purpose-limited access, minimized disclosure,
  retention decisions and protected originals; diagnostics must not expose medical data.
- PSD2: legal authority is distinct from authentication and SCA; any payment exception
  requires an explicit legally reviewed execution path, not an implicit bypass.
- CNB: CZ protected-account and disclosure obligations inform the CZ pack. CNB sources
  do not establish SK/DE compliance; those packs need their own legal acceptance.

## References

Primary legal/operational sources consulted on 2026-11-15; revalidate consolidated law,
transitional provisions and supported interfaces at pack approval. This list motivates
the capability model, not an exhaustive legal implementation specification.

- [CNB: protected accounts](https://www.cnb.cz/cs/casto-kladene-dotazy/Co-je-chraneny-ucet-a-jak-jej-zridit-a-uzivat).
- [Czech Civil Code, Act 89/2012](https://www.e-sbirka.cz/sb/2012/89), including §64 and guardianship provisions.
- [EKCR bank/financial-institution data exchange](https://www.ekcr.cz/financni-instituce-vymena-dat) and [FAQ](https://www.ekcr.cz/FAQ-financni-instituce).
- [ISDS technical information](https://datovka.gov.cz/info/cs/74.html).
- [Slovak Banking Association: electronic communication](https://www.sbaonline.sk/projekt/elektronicka-komunikacia/).
- [Slovak Execution Code 233/1995, §§95–104](https://static.slov-lex.sk/static/SK/ZZ/1995/233/20251227.print.html).
- [Slovak Civil Code 40/1964](https://static.slov-lex.sk/static/SK/ZZ/1964/40/20260731.print.html) and [Civil Non-Contentious Procedure Code 161/2015](https://static.slov-lex.sk/static/SK/ZZ/2015/161/20251001.print.html).
- [ZPO §850k: P-Konto conversion](https://www.gesetze-im-internet.de/zpo/__850k.html), [§850l: joint accounts](https://www.gesetze-im-internet.de/zpo/__850l.html), [§835: transfer and payment timing](https://www.gesetze-im-internet.de/zpo/__835.html).
- [ZPO §899: protected amounts and carry](https://www.gesetze-im-internet.de/zpo/__899.html), [§900: moratorium](https://www.gesetze-im-internet.de/zpo/__900.html), [§903: certificates](https://www.gesetze-im-internet.de/zpo/__903.html).
- [BGB §1823: representative authority](https://www.gesetze-im-internet.de/bgb/__1823.html) and [§1825: consent reservation](https://www.gesetze-im-internet.de/bgb/__1825.html).
- [EU Regulation 655/2014: European Account Preservation Order](https://eur-lex.europa.eu/eli/reg/2014/655/oj/eng).
