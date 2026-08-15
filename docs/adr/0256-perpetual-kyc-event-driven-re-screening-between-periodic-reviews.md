---
date: 2026-08-11
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [kyc, aml-sanctions, kafka, ai-agents]
summary: "KYC review turns event-driven: a sanctions-list refresh whose per-entry content hashes actually changed, and KYC case expiry, open a re-screening case through the case coordinator — the trigger opens the case, a human still decides it."
---

# ADR-0256 — Perpetual KYC: event-driven re-screening between periodic reviews

## Context

ADR-0116 shipped the KYC engine as case-based: `openbank-kyc-service` opens a `KycCase` when a
party onboards (the `PARTY_CREATED` consumer in `PartyEventConsumer`), runs IDENTITY / ADDRESS /
PEP_SCREENING / SANCTIONS_SCREENING checks, and a human decides. Between that moment and the next
scheduled review, nothing watches the customer. The code says so twice, by name:

- `KycService` on PEP screening: *"not continuous real-time monitoring (case-open time only in
  this increment — periodic re-screening needs the Temporal workflow already flagged in
  ADR-0116 §5, tracked separately)"*.
- `KycResource.rescreenPep` documents itself as *"an **operator-triggered** re-screen, not the
  periodic re-KYC programme"*.

So re-screening today requires a human to remember. That is the gap the industry calls perpetual
KYC (pKYC): regulators (AMLD6) and practice (BNP Paribas "One KYC" reports a 20% KYC cost
reduction and a 67% improvement in file-closure rates from exactly this move) treat the periodic
calendar as a floor, not a target — material changes (sanctions-list updates, customer master-data
changes, expiring evidence) should trigger review when they happen, not at the next anniversary.

Three platform pieces this needs already exist:

1. **Sanctions lists are already refreshed in-repo, per entry.** `SanctionsListService.refresh`
   imports enabled lists on a cron; every imported row lands as a `SanctionsEntry` (migration
   `V5__create_sanctions_entries.sql`) carrying a stable `externalId`, the screening-relevant
   fields (`primaryName`, `aliases`, `dateOfBirth`, `nationalities`, `programs`) and an `active`
   flag. A refreshed list whose changed entries are never re-matched against the *existing*
   customer base is a screening gap: the check ran at onboarding, the list changed after, and no
   case exists.
2. **The case coordinator (ADR-0244) already owns multi-agent case orchestration** — `case.open`
   with class `AML_ALERT`, per-class token budgets, deadlines, convergence thresholds, and one
   HITL proposal per case. A KYC re-screening is an `AML_ALERT`-shaped case: gather evidence
   (PEP screen, sanctions screen, party record), let the compliance-officer agent draft an
   assessment, a human decides.
3. **KycCase already expires** (`expiresAt`, 30-day default at creation) and kyc-service already
   publishes case events through its outbox.

The missing piece is a definition, not plumbing — the same shape as ADR-0247: *what counts as a
re-screening trigger, who may raise it, and what it may cause.*

Two design forces decide this ADR. First, what a trigger is *allowed to do*: a trigger that
itself changes a customer's standing turns a data-quality event into a customer-harming action
with no human in between — so every trigger below opens a *case*, and only a case (ADR-0247's
deliberate-decision line; ADR-0116's MATCH → MANUAL_REVIEW, never auto-reject). Second, what a
trigger may *fire on*: a refresh that re-imports the same list content must raise nothing, or the
daily cron becomes a daily fleet-wide re-screening — and a count-only comparison is not
sufficient, because a re-import that edits one entry while deleting another leaves the count
unchanged and the edit unseen. The trigger condition must therefore be defined on the *content of
the entries*, which is why D1 is written the way it is.

## Decision

**We will make KYC re-screening event-driven: a closed catalogue of triggers, each gated on a
content-level change, opens a re-screening KYC case through the case coordinator, while the
periodic calendar remains as a floor. A trigger can never decide anything about a customer — it
can only open a case for a human to decide.**

**D1 — A re-screening trigger is a named, closed-catalogue event with a content-level firing
condition; "the importer ran" is never one.** The catalogue at acceptance has exactly two active
members and one deferred:

- `SANCTIONS_LIST_CHANGED` *(active)* — fires when a `SanctionsListService.refresh` import
  produces a **per-entry content diff**, not merely a run. The importer computes, per list, a
  hash of each incoming entry's screening-relevant fields (`externalId`, `primaryName`,
  `aliases`, `dateOfBirth`, `nationalities`, `programs`, `active`) — with list fields
  canonically ordered before hashing, so a reorder-only re-import hashes identically — and
  compares it against the stored entries for that list: any added, removed,
  re-activated/deactivated, or field-edited entry is a change. A refresh whose import is
  content-identical (the common case — upstream re-publishes the same data) records
  `lastEntryCount` as today and raises nothing. **A changed *count* alone is explicitly rejected
  as the condition**: equal-count edits are exactly the updates a sanctions regime issues (a name
  spelling corrected, a program reassigned), and a count heuristic makes them invisible by
  construction. Two storm guards apply: if the diff exceeds a configured share of the list
  (upstream schema reformat looks like a total rewrite), the refresh raises an operator ticket
  instead of a mass re-screening; and each refresh raises at most one trigger per list regardless
  of how many entries changed.
  *Cost honesty:* the hash comparison requires the import to read the list's current entries
  before write — today the importer replaces the list contents; this ADR adds that read and the
  hash computation to the import path. It is deliberate: the alternative (trusting count or
  timestamps) builds the screening gap into the system on day one.
- `KYC_CASE_EXPIRED` *(active)* — `expiresAt` reached without review, raised by a sweep over the
  existing field. The calendar floor made explicit: expiry opens a case instead of the case
  silently lapsing to EXPIRED with no queue entry.
- `PARTY_DATA_CHANGED` *(deferred — catalogue member, not yet wired)* — a material change to a
  party's master data (name, date of birth, residency country, beneficial-owner set) requires
  party-service to publish a **materiality-classified** change event: materiality must be
  declared in the publisher's event contract, never inferred by the consumer from a generic
  update, and address-only/contact-only edits are excluded. `KafkaPartyEventPublisher` publishes
  today, but no materiality classification exists — this member is inert until that contract
  lands, tracked as #4458. This ADR therefore ships the catalogue with two live triggers and says
  so, rather than declaring three and delivering two.

Extending the catalogue is an ADR-level act (it changes what may put a customer under review),
not a config flag.

**D2 — Every trigger opens a case through `case-coordinator`, never a verdict.** kyc-service
translates a trigger into a `case.open` call with class `AML_ALERT`, `subjectRef = partyId`, and
the trigger identity in the case context. The coordinator's budget, deadline and convergence
controls (ADR-0244) apply unchanged; the compliance-officer charter (agents.yaml) is the drafting
participant. The single case proposal lands in the existing HITL queue; the four-eyes KYC gate
(ADR-0116, ADR-0028) is the disposition. A trigger that cannot reach the coordinator fails
**open to a ticket, closed to nothing else**: the fallback is a tracked task for an operator,
never a silent drop and never an automated action.

**D3 — Re-screening re-runs checks on a linked case; it does not fork the customer's
lifecycle.** A triggered re-screen opens a *new* case for the party with the screening check set
(SANCTIONS_SCREENING, PEP_SCREENING; IDENTITY joins when `PARTY_DATA_CHANGED` is wired) — reusing
`createCase`'s structure minus ADDRESS, because a list change must not become a document request
to the customer. The existing dedup invariant (`findActiveByPartyId` + the
`uq_kyc_cases_active_party` partial unique index) means a trigger arriving while a case is in
flight attaches to that case instead of opening a second one. The party's standing (APPROVED,
products live) is untouched while a re-screening case is open — review is not suspicion, and
suspicion is a fraud-hold (ADR-0247), which this ADR does not raise. For a
`SANCTIONS_LIST_CHANGED` trigger, screening is additionally **scoped to the diff**: only parties
whose prior screening could match a changed entry are re-screened (name-similarity candidate set
over the changed entries), not the whole book — the per-entry diff that fires the trigger also
bounds the blast radius.

**D4 — Expiry sweep is a `suspend fun` scheduled job with an observable trigger count, not a
silent cron.** `KYC_CASE_EXPIRED` comes from a sweep over `kyc_cases.expires_at`. This repo has a
documented history of schedulers that never ran (#2148, #2187 — a plain non-suspend `@Scheduled`
method carries no Vert.x context and dies silently), so the sweep is a `suspend fun`, carries a
liveness heartbeat per ADR-0237, and is monitored on the *count of expiry triggers raised*, never
on the job's own success.

**D5 — Adverse media and commercial watchlist feeds are out of scope.**
`CheckType.ADVERSE_MEDIA` exists in the domain model, but no adverse-media source exists in the
platform, and this ADR does not create one: an event-driven trigger requires a change-detectable
source, and a stubbed feed would make a catalogue member decorative. When a real source lands
(#4459), it joins the catalogue by the D1 mechanism with its own ADR.

**D6 — What the customer is told is unchanged and out of scope.** A re-screening case is an
internal state. Customer-visible consequences (document re-requests, contact) follow the existing
KYC communication paths when a human disposition calls for them; this ADR adds no customer-facing
surface, and tipping-off constraints on AML-adjacent review are unaffected because no automated
party action exists to tip off about.

## Alternatives considered

- **Gate the trigger on `lastEntryCount` change.** Rejected — the failure mode this ADR exists to
  prevent. A count comparison sees additions and deletions but is blind to equal-count edits,
  which are the routine shape of sanctions-regime updates; the first real-world correction would
  pass silently and the system would read as covering a case it structurally cannot see. D1's
  per-entry hash diff is the price of the trigger meaning what it says.
- **Keep the periodic calendar and just shorten the cycle.** Rejected: re-screens everyone
  regardless of signal, and still misses the sanctions-list change the day after the cycle ran.
  The calendar stays as the floor (`KYC_CASE_EXPIRED`) because event-driven coverage can never
  prove it saw everything — but as the only mechanism it buys latency, not safety.
- **Re-screen the whole book on any content change, unscoped.** Simpler than D3's diff-scoped
  candidate set. Rejected: a single-entry list update would re-screen every approved customer,
  burying the review queue in no-match MANUAL_REVIEW noise until reviewers dismiss reflexively —
  the false-positive fatigue cited across the AML literature as why automation programmes fail.
- **Raise a fraud-hold-style adverse state (ADR-0247) directly from a trigger.** Rejected for
  ADR-0247's own reason: a list change is a data event, not a decision about a person. Holds
  remain analyst-created after review; the trigger feeds the queue, the human places the hold.
- **Synchronous re-screen inline in the sanctions refresh job.** No case, no coordinator.
  Rejected: it couples a batch import to fleet-scale screening latency, and produces check
  results with no case around them — the "who decided this, on what grounds, when" record the
  platform is otherwise building (ADR-0247) would be absent.

## Consequences

**Positive**
- The "screened at onboarding, list changed after" gap closes at content granularity: an entry
  edit reaches exactly the customers whose prior screening could match it, as a case, within the
  refresh cycle.
- pKYC lands on infrastructure that exists and is governed — coordinator budgets (ADR-0244),
  HITL queue (ADR-0031 D4), four-eyes KYC gate (ADR-0116), outbox — so the change is an
  import-path diff computation, a consumer, and a sweep, not a new subsystem.
- The trigger catalogue is auditable by construction: every case carries its trigger and (for
  list changes) the diff that fired it — "why is this customer under review" has a one-row
  answer, and "what did the list change actually touch" is replayable from the hashes.

**Negative**
- The import path becomes heavier: hash computation and a current-entries read per refresh, plus
  a stored-hash column per entry (Flyway migration on the sanctions-entry table). Real but
  bounded cost on a batch path, bought with the correctness the trigger depends on.
- Two storm guards (diff-share cap, one-trigger-per-list) are new failure-relevant configuration
  that must be tuned and monitored; a mis-set cap either floods the queue or silently converts
  real mass changes into tickets nobody reads.
- kyc-service gains an inbound dependency on sanctions-service's refresh lifecycle, and (when
  wired) on party-service's materiality contract — cross-service coupling this ADR creates
  deliberately but does not eliminate.
- The expiry sweep is another scheduled job with the #2148/#2187 failure mode; D4 names the
  mitigation, and the honest statement is that sweep-driven triggers are only as live as their
  heartbeat monitoring.

**Neutral**
- `KycCase`'s schema is unchanged; re-screening cases are ordinary cases with a trigger recorded.
- No change to the payment-path screening gate (ADR-0032): payments screen at execution as today;
  this ADR concerns the *book*, not the transaction path.
- `PARTY_DATA_CHANGED`'s deferral is a statement of sequencing, not of doubt — the catalogue is
  designed for it and #4458 tracks it.

## Compliance impact

- PCI DSS: not applicable — no cardholder data; triggers carry party identifiers and list-entry
  hashes only.
- DORA:    a new internal event dependency (sanctions refresh → kyc case-open); no new
  third-party ICT dependency — D5 explicitly defers external feeds.
- GDPR:    engages Art. 5(1)(c) (re-screening reuses already-collected KYC data; no new
  collection) and Art. 6(1)(c) (legal obligation — the AMLD ongoing-monitoring duty — as the
  basis, so no consent dependency). The closed D1 catalogue is the proportionality record.
- PSD2:    not applicable — no payment-flow touchpoint; the ADR-0032 execution-time gate is
  unchanged.
- CNB:     operationalises the AMLD ongoing-monitoring obligation — the trigger catalogue, the
  per-entry diff, and the per-case audit trail are the supervisory evidence; four-eyes
  disposition (ADR-0116) is preserved and nothing here is an automated customer decision. EU AI
  Act: no new high-risk system — agents draft and synthesise while every disposition is human
  (agents.yaml `requires_human`), the proposal-only pattern already registered in
  `docs/compliance/eu-ai-act.md`; no `agents.yaml` change.

## References

- ADR-0116 — KYC engine (case model, four-eyes gate, §5's flagged periodic re-screening this
  implements in event-driven form)
- ADR-0244 — case coordinator: `case.open`, `AML_ALERT` class, budgets and convergence
- ADR-0247 — fraud hold: the deliberate-decision-vs-derived-verdict line this ADR mirrors, and
  the scheduler failure mode it inherits
- ADR-0031 D4 — HITL proposal queue; ADR-0032 — payment-path screening gate (untouched)
- ADR-0237 — scheduler liveness heartbeat standard the D4 sweep must carry
- Issue #4458 — party-service materiality-classified change event (`PARTY_DATA_CHANGED` wiring)
- Issue #4459 — adverse-media source selection (D5)
- Issue #2148 / #2187 — schedulers that never ran (why D4 is written the way it is)
- BNP Paribas "One KYC" (Celent) — 20% KYC cost reduction, 67% file-closure improvement; the
  external evidence that event-driven review beats the calendar
