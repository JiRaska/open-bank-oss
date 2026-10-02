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
created and status-changed records idempotently, preserving one immutable evidence node per revision.
The fixed complaint query follows only `CONCERNS_TRANSACTION` and an allow-listed directed lifecycle
second hop, plus source/prefix/relation allow-listed booking-transaction and ledger-journal hops, so
it cannot pivot from a shared transaction into another complaint. `TransactionInitiated` carries the
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
