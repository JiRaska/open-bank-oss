---
date: 2026-09-13
decision-status: accepted
delivery-status: partial
followup: "#9945 — complete payment rail and return-event correlation beyond dispute-owned references"
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: [open-bank-oss]
tags: [disputes, payments, admin-ui, audit]
summary: "Payment complaints get a bounded source-backed lifecycle trace on the shared context graph, correlating instruction, rail, booking, return and case evidence without replacing authoritative payment state."
---

# ADR-0306 — Payment complaint and return trace

## Context

A complaint handler needs to answer where a payment stopped, whether it booked, returned
or was reversed, and which evidence supports the answer. Today these stages are owned by
different services and event types. Operators otherwise reconstruct the chain manually,
while a live fan-out can produce an internally inconsistent view during transitions.

This is **P1 and the first real-data functional pilot** after ADR-0308's P0 controls. It
is rooted in one assigned complaint/payment, follows a small known path and provides
high operator value with lower disclosure risk than network-wide investigations.

## Decision

We will add a `PAYMENT_COMPLAINT_TRACE` lens to the shared ADR-0303 stack. It reuses the
same canonical identifiers, ingestion, storage, policy/audit, pagination and UI. It does
not create another payment state machine or a complaint-specific graph datastore.

The canonical trace is directed and typed:

- instruction —[CREATED]→ payment and payment —[SUBMITTED_TO]→ rail item;
- rail item —[ACKNOWLEDGED/REJECTED/SETTLED]→ rail evidence;
- payment —[BOOKED_AS/REVERSED_BY/RETURNED_BY]→ ledger or return reference;
- complaint/dispute —[CONCERNS]→ payment and —[SUPPORTED_BY]→ evidence version.

Correlation uses stable business/source IDs declared by producers. Temporal adjacency,
amount equality or similar text alone cannot create a fact edge. Missing correlations
are shown as gaps with the last observed source state. Duplicate/redelivered events are
idempotent. Corrections add a source-versioned observation; they do not rewrite the
historical evidence presented for a prior complaint decision.

The trace orders by domain event time and separately exposes recorded/ingested time.
Clock disagreement and late arrival are visible. Projected lifecycle state is labelled
derived; payment, rail, ledger and dispute services remain authoritative. The UI links
to an authoritative detail only after that destination reauthorizes the request.

Access requires an assigned complaint/dispute or an explicitly permitted payment lookup,
`PAYMENT_COMPLAINT_TRACE`, allowed account/payment scope and permitted fields. Customer
names and counterparties are minimized. A complaint agent cannot pivot through the
beneficiary into unrelated activity. Export and raw evidence download are independent
permissions. Every trace read is audited with purpose, case, root and snapshot.

The server uses a fixed trace template, normally one lifecycle chain and at most two
side branches (return and dispute). It returns at most 100 events/relations and 128 KiB;
older history is cursor-paged against one projection generation. It never scans all
transactions by amount/date. If a source is stale or a correlation missing, the result
is incomplete rather than "not happened".

Acceptance covers completed, rejected, timeout, return, reversal and duplicate-delivery
paths; partial source outage; late/out-of-order events; identifier collision; wrong
account/case; revoked assignment; missing audit/OPA; and concurrent payment control-load
benchmarks. Compare the trace to authoritative source fixtures and measure time-to-answer.

## Delivery status

### Delivered P1 slice

Complaint events now carry a versioned contract and stable account, transaction and dispute
references. Complaint lifecycle mutations increment a database-backed aggregate revision under a
row lock and commit the revision plus event in the existing transactional outbox. Domestic-payment
events now also carry a database-backed aggregate revision. A dedicated projector consumes the
created and status-changed records idempotently. Complaint graph-input revisions are now retained in
an append-only, bank-scoped snapshot table, including their exact account, transaction and dispute
references and a normalized graph-input digest. Category, channel and complaint deadlines are not copied
into this graph snapshot; their authoritative detail remains in dispute-service. Duplicate
projected revisions are idempotent; conflicting
contents for the same revision fail atomically. Out-of-order older revisions remain available
without rolling back the current projection. Complaint `asOf` reads select the highest source
revision whose event time is at or before the requested time and reconstruct its root and direct
links, then reuse the bounded rail/booking traversal. The immutable receipt timestamp is exposed
separately. This is source-event-time history, not a claim about what the investigator knew at
that time: complaint reads do not yet support a `knownAt` cutoff. Missing retained snapshots
return no complaint graph; overwritten pre-migration evidence is not reconstructed from the
current-state rows. Controlled replay from complete retained source events is required to restore
older complaint roots. Retention/erasure operations for these restricted snapshots still need an
approved operational lifecycle before production activation. Lifecycle evidence owned by other
projectors and the full trace's historical reproducibility remain separate acceptance work.
Domestic payment, transaction/ledger booking and clearing/SEPA-return projections now share an
append-only normalized node-history store. Every accepted event appends its node facts before
current-state deduplication, including older arrivals. An event-level digest rejects a changed
node set on replay; node-level digests additionally bind the exact retained fact. Source-owned
nodes take precedence over reference placeholders only when the source fact existed at `asOf`.
The reader obtains at most two indexed candidates for each of at most 100 selected keys; it
does not sort the entire graph history. Missing retained nodes suppress dangling links and mark
the result truncated. Eligible current rows remain readable as limited baseline facts only at
or after their stored source event time; they cannot reconstruct overwritten earlier revisions.
Replay remains required for pre-migration history. The three lifecycle projectors also append
normalized relationship observations and a digest of the complete edge set before event deduplication.
The reader chooses a complete eligible observation for each source-owned logical relationship
before applying its result bound. Each selected root is read through an indexed lateral query;
when the neighborhood overflows, newest eligible relationship evidence is selected, then the
bounded result is presented in event-time order. Both baseline and retained-history branches
have per-root bounds before their final merge. It does not combine an earlier timestamp with a later event's
version or evidence reference. Current edge rows are eligible baselines only when no historical
observation exists at that time. The source, target-prefix and relation allowlists remain enforced.

The consumed source contracts describe observations and contain no general relationship tombstone
or supersession identity. A changed endpoint or an omitted edge therefore does not withdraw a
previous observation. Correction/removal requires producer-owned relationship identity, effective
time, revision ordering and explicit withdrawal/supersession semantics; that acceptance remains open. The return's reversal transaction
ID is labelled a reference, not a booked reversal; ledger evidence remains the booking proof.
The fixed complaint query follows only `CONCERNS_TRANSACTION` and an allow-listed directed lifecycle
second hop, plus source/prefix/relation allow-listed booking-transaction and ledger-journal hops, so
it cannot pivot from a shared transaction into another complaint. The complaint's `transactionId`
names the transaction-service booking, represented as `booking-transaction:<transactionId>`;
it is not the domestic payment ID represented as `transaction:<paymentId>`. The reader reaches
that payment only through transaction-service's explicit incoming `BOOKING_REQUESTED` association.
Without that association it may show the referenced booking and its independently observed ledger
or reversal evidence, but never invents a payment link, even when the UUIDs happen to be equal.
Historical complaint snapshots retain their original source references; read normalization does
not rewrite those immutable observations. The reverse history index in V23 supports this bounded
lookup; its rollback drops only the index and does not remove evidentiary rows.
`TransactionInitiated` carries the
stable originating payment id and `JournalPosted` carries explicit producer attribution; both are
transactional-outbox events. Their projectors tolerate reverse delivery order through replaceable
placeholder nodes and expose the actual posted journal rather than inferring booking from a payment
status. Clearing settlement now increments each item revision and commits one `item.cleared` evidence
record per item in the same transaction as the batch, items and net-settlement intent. The SEPA
`payment.returned` non-repudiation record carries the revision of its `RETURNED` transition. Two
strict projectors add the clearing item, settlement acknowledgement and explicit return evidence to
the same fixed trace while ignoring all unrelated records on the shared topics. The admin UI renders
the same authorized payload as both graph and ordered payment timeline.
The staged production manifest requires explicit revisions from the first replica; timestamp fallback
remains a local compatibility aid for controlled legacy replay only. Other rails, timeout evidence and
the full authoritative reversal result remain follow-up work, so this ADR stays `partial`.
When transaction-service returns a new reversal transaction ID, the SEPA return evidence now carries
that optional ID and the bounded complaint graph exposes a `REVERSED_BY` edge to it. An idempotent
conflict can confirm a prior reversal without returning its ID; that event remains an unlinked
confirmation, and the graph does not invent the missing transaction reference. This link still needs
the transaction/ledger event and reconciliation evidence before the reversal outcome can be described
as a fully traced booking.
Transaction-service now includes its persisted `reversalOf` identity on the optional
`TransactionInitiated` wire field. Context projects only a `REVERSAL` initiation with a valid
original transaction ID, then follows the explicit original-booking-to-reversal-booking edge
within the complaint's bounded trace. A separate `JournalPosted` record can show the reversal's
ledger booking. Initiation alone never asserts that the reversal completed or reconciled; old
events without `reversalOf` remain unlinked rather than guessed from timing or description.

The investigation UI invalidates displayed and in-flight evidence whenever the
complaint reference, assignment case or purpose changes. A late response for the
previous investigation cannot populate the newly selected context. This client-side
lifecycle safeguard complements the server's assignment, OPA and read-audit checks.

## Alternatives considered


- **Live service fan-out:** rejected; it adds money-path load and cannot guarantee one snapshot.
- **Derive correlation from amount/time:** rejected; common values create false links.
- **Copy authoritative payment state into context-service:** rejected; each lifecycle node remains
  source-versioned evidence and domestic-payment remains authoritative.
- **Build this inside dispute-service:** rejected; shared rail/ledger lineage serves other lenses.

## Consequences

**Positive**
- Complaint handlers get one reproducible, source-backed lifecycle trace.
- A bounded first pilot proves shared ingestion, authorization and workload isolation.

**Negative**
- Producers need stable correlation identifiers and explicit missing-link handling.
- Eventual consistency requires prominent freshness and incomplete-state semantics.
- Retained legacy topic records require a controlled compatibility replay before they can enter the
  strict production projection.

**Neutral**
- No payment, return, reversal or complaint transition moves into context-service.

## Compliance impact

- PCI DSS: trace uses tokenized references and excludes PAN/authentication data.
- DORA: failure and performance isolation from payment processing are acceptance conditions.
- GDPR: complaint purpose, data minimization, access audit and retention apply.
- PSD2: improves evidence navigation without altering payment rights or statutory decisions.
- CNB: reproducible trace assists complaint evidence; no completeness claim without source proof.

## References

- [ADR-0303](0303-banking-context-graph-and-authorized-hybrid-retrieval.md)
- [ADR-0308](0308-effective-time-authorization-evidence-graph.md)
- [ADR-0085](0085-complaints-handling.md)
- [ADR-0117](0117-dispute-and-complaint-lifecycle.md)
- [Implementation roadmap #9945](https://github.com/JiRaska/open-bank-oss/issues/9945)


### Generation-scoped rebuild transition

The event ledger records the configured projection generation for new ingestion. Legacy rows keep
NULL generation because their original configured generation cannot be established from the ledger.
They remain evidence and do not suppress reconstruction into a named generation. Retained legacy
incident digests are checked by aggregate and revision before any new generation write: matching
content can rebuild; conflicting content is rejected; a missing digest remains explicitly unverifiable.
Incident revision
uniqueness and edge primary keys are scoped by bank and generation, preserving existing edge IDs.

This is a coordinated writer transition: stop and drain all five affected consumers before V19,
apply the migration, and start only generation-aware writers. Mixed old/new writers are unsupported
because old conflict targets no longer match the new scoped constraints. Do not roll back to an
old writer against this schema. After any multi-generation ingestion, stop consumers and retain the
expanded schema; collapsing the ledger or edge keys would lose evidence. Runtime configuration and
controlled replay approval remain prerequisites; the migration does not activate replay or switch
the reader generation. Reconstruction tests must cover nodes, relationships, duplicate delivery,
legacy evidence preservation and incident conflicts, not merely event-ledger row counts.


### Bounded recent relationship selection

The reader takes at most the requested edge budget per selected root from each history/baseline
partition, after selecting eligible logical observations, then applies the global budget. A row
discarded within a partition already has at least that many higher-ranked rows in the same
partition, so it cannot enter the global newest-evidence result. The bounded result is then
presented chronologically. Scope and source/prefix/relation allowlists remain inside SQL.

Current edges carry a derived `retained_history_from` timestamp. It is the minimum effective time
of a matching retained observation, not a source fact. V21 rebuilds it under each bank's RLS scope;
writers update it atomically and conflicting initial inserts retain the minimum. Before this time
the current row remains an eligible baseline; at or after it the retained evidence supplies the
relationship. Partial indexes prevent rechecking every covered current relationship against its
history. Any governed removal of history must recompute this coverage metadata before readers
resume. Evidence must not be removed independently while coverage still asserts its availability.

Database probes and integration tests do not replace the isolated authenticated capacity gate,
which must include policy, disclosure audit and concurrent payment regression at 1x/10x volume.

V21 shares V19's coordinated Context transition: drain/stop Context readers and consumers before
applying the migration and restart only the matching coverage-aware binary. An older reader using
`SELECT *` is incompatible with the added metadata column, and older writers do not maintain its
coverage. The explicit-column reader avoids that mapping defect for subsequent additive changes.
This transition must not be performed as an overlapping old/new Context rollout.
