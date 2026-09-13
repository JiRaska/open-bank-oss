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
references. The context projector consumes them idempotently and rejects duplicate or older
source-issued ordering tokens into an isolated generation-scoped store. The current token is derived
from source event time rather than a strict aggregate revision, so equal or regressed timestamps can
leave the projection incomplete. An assigned investigator can open the bounded graph from the
disputes admin page; the server verifies assignment and OPA, commits the audit decision and returns
source-backed evidence. A strict source revision plus full instruction, rail, booking and return
producers remain follow-up work, so this ADR stays `partial`.

## Alternatives considered

- **Live service fan-out:** rejected; it adds money-path load and cannot guarantee one snapshot.
- **Derive correlation from amount/time:** rejected; common values create false links.
- **Copy authoritative payment state into context-service:** rejected; projection remains evidence.
- **Build this inside dispute-service:** rejected; shared rail/ledger lineage serves other lenses.

## Consequences

**Positive**
- Complaint handlers get one reproducible, source-backed lifecycle trace.
- A bounded first pilot proves shared ingestion, authorization and workload isolation.

**Negative**
- Producers need stable correlation identifiers and explicit missing-link handling.
- Eventual consistency requires prominent freshness and incomplete-state semantics.
- Timestamp ordering suppresses stale events but cannot prove causal order when source clocks collide
  or regress; production producers need a strict per-aggregate revision.

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
