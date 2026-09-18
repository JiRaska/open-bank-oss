---
date: 2026-09-13
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: [open-bank-oss]
followup: "#9945 — fraud-case source contract, wider AML network, retention and workload qualification remain"
tags: [fraud, aml-sanctions, authz, admin-ui]
summary: "Fraud and AML investigators use separate purpose-bound lenses over the shared context graph to examine explainable device, counterparty and money-flow relationships without turning similarity into evidence."
---

# ADR-0304 — Fraud and AML relationship investigation

## Context

Fraud and AML investigators need to find shared devices, beneficiaries, counterparties
and the time-ordered flow of funds. Existing transaction scoring (ADR-0084) and
synchronous screening (ADR-0032) decide individual operations; neither is a historical
network-investigation store. A broad graph can reveal unrelated customers and is one of
the most sensitive views in the bank.

This lens has critical business value, but its cross-customer scope makes it unsuitable
as the first real-data pilot. Its delivery priority is **P2**, after ADR-0308's effective-
time authorization/audit foundation and the bounded P1 lenses have proved isolation,
revocation and workload controls. Priority is not permission to bypass those gates.

## Decision

We will implement separately authorized `FRAUD_INVESTIGATION` and `AML_INVESTIGATION`
lenses on the shared `context-service` stack defined by ADR-0303. They reuse the
canonical model, ingestion, PostgreSQL store, policy/audit layer and admin UI explorer.
They do not get their own graph database.

The initial ontology is:

- party —[USED]→ device/session and party/account —[SENT_TO]→ beneficiary account;
- payment —[DEBITED_FROM/CREDITED_TO]→ account, carrying amount, currency and event time;
- party —[SUBJECT_OF]→ fraud/AML case and case —[SUPPORTED_BY]→ evidence reference;
- sanctions/screening results as observations, never identity or guilt edges.

Source IDs and directed transaction legs are authoritative references. Device matches
are observations with confidence and collection method. Shared IP, address, device or
payee never merges parties, creates a fraud hold or raises an AML decision automatically.
Hypotheses are structurally separate from facts and retain algorithm/model version.
Amounts group by currency or use a disclosed rate and valuation time; unlike currencies
are never summed silently.

Every request supplies a verified open case and one purpose. OPA authorizes the
investigator, assignment, root, edge classes, time window and fields. Fraud permission
does not imply AML permission. Search, counts, path discovery, facets, export and
semantic retrieval use the same allowed subgraph. Expanding to another customer needs
an allowed evidential edge and a new policy decision. Results carry case, policy version
and snapshot time.

Interactive queries use one hop per expansion and at most two hops per request. Default
windows are 30 days for fraud and 90 days for AML; wider retained evidence requires an
explicit reason. High-degree merchants/infrastructure are aggregated first. Long
money-flow tracing runs asynchronously against a fixed snapshot and returns bounded,
explainable evidence.

The lens is read-only. Decisions, holds, reports and case transitions remain operations
of their owning services with existing approvals. Semantic search may retrieve similar
authorized closed-case evidence, but similarity cannot create a link, guilt score or action.

Acceptance covers cross-case/customer IDOR, hidden-node path leakage, revoked assignment,
unavailable OPA/audit, timing/count side channels, high-degree nodes, replay, duplicates
and currency-safe aggregation. Measure task precision/recall and false associations as
well as latency. Pilot on synthetic data, then a limited investigator group without export.

## Source evidence delivered in the first P2 step

AML case creation and status changes originate from the AML service's transactional outbox.
The Kafka body carries the case, party, optional account/transaction and observed status;
`ce-id` and `ce-type` are **headers**, not body fields. The context projector requires those
headers and a matching idempotency key. It persists a bank-scoped, append-only, event-time
and recorded-time history with typed indexes for explicit case links. The projector drops
customer references, matched names, reasons, analyst names and other free text. Duplicate
event IDs with changed normalized content fail; a late event cannot rewrite an earlier
knowledge-time view. The consumer has its own group, DLQ and bounded database timeout.

The first read surface is limited to one assigned AML case. Before it returns evidence,
it checks the current case status with the owning AML service through a bounded,
uncached call carrying the investigator's authenticated bearer (1.5 s total timeout, 16 in-flight calls per
context instance, no retry); closed, unknown, overloaded or unavailable status yields no
evidence. That dependency is configured outside the public repository. Authorization
also binds the investigator, case, root and AML purpose through the existing assignment,
OPA and durable audit path. The AML service has case lifecycle events but no monotonic
revision in their body, so a timestamp alone cannot prove a complete transition
sequence. Event history presents observations, never a final legal conclusion. No
device-use edge, transaction settlement or inference is delivered.

The next bounded read surface discovers cases with an explicitly equal party, account
or transaction identifier in the same effective/knowledge window. Candidate discovery
joins only currently approved assignments for that investigator and returns at most four
cases. Every candidate is then independently re-authorized, read-audited and checked
against its current source status before any evidence is returned. A revoked or terminal
candidate is omitted; source or policy unavailability fails the whole request. Each
related case returns at most 20 observations. If that bounded slice no longer includes
the linking observation, the candidate is omitted rather than drawn without visible
evidence. The displayed network is intentionally incomplete: the four-case cap,
observation cap, assignment scope and source lag mean absence of an edge does not
prove absence of a relationship. Exact identifier equality is a lead, not a fraud
finding or inferred ownership.

Fraud's current source emits a temporary fraud-hold change for marketing suppression;
it does not have a case/assignment lifecycle or an authoritative fraud finding. Its
signals cannot be reused as an investigative case or an account restriction. A true
fraud-case source contract, purpose-bound authorization and independent negative tests
are prerequisites for the Fraud lens.

The first Fraud network lens now uses a distinct reference-only Fraud topic. Context
fetches live OPEN case associations from Fraud over HTTPS only after a case-scoped
assignment, policy decision and committed audit; every candidate repeats that chain.
Only exact account or counterparty UUID equality within the same identifier role
becomes a visible edge. Discovery is limited to four currently assigned case
references, and the UI labels a truncated or empty result as a partial search.
No device graph, inferred identity, vector-generated edge or fraud finding is
represented. The source URL is required runtime secret material, while the tracked
deployment declares service identity for generated network policy. Missing secret,
TLS trust, source, PDP or audit fails closed. This is an investigative pilot slice.

Broader cross-case expansion, retention/restriction workflow, load evidence and a
controlled pilot remain required. The bounded AML and Fraud networks are not
completion of ADR-0304.

The current Fraud pilot selects the first four assigned case references by UUID
**before** comparing source-owned account and counterparty identifiers. Once an
investigator has more than four eligible cases, a genuinely related case can be
outside that slice. The `candidateTruncated` response flag warns about this; an
empty related list must not be described as an exhaustive negative finding.

The next Fraud network increment will select candidates by explicit equality in
Fraud's indexed case store. Context will supply a bounded, cursor-paged set of
case IDs that are assigned to the investigator for this purpose; Fraud will match
only those IDs against the live root case's account and counterparty **within the
same identifier role**. The source response will contain candidate case IDs only,
not account or counterparty IDs. Context will independently re-authorize, audit
and fetch each candidate's evidence before returning it to the UI. A page limit
or unavailable source will be reported as partial or unavailable, never as a
complete network. The reference Kafka topic remains case-ID/revision/time only.
Measure lookup selectivity, authorization cost and p95/p99 latency with 1× and
10× assigned-case populations before increasing the interactive expansion cap.

Candidate IDs supplied in an HTTP body are **not proof of assignment**. Fraud must
not return a match, count, truncation signal or timing-distinguishable result for
an unverified candidate: a caller with access to the root could otherwise probe
guessed case IDs. The source lookup therefore requires authenticated Context
service identity *and* the human investigator's bearer, with a server-side
case-scoped candidate authorization check before equality is evaluated. A plain
caller-controlled header or a client-side-only filter does not satisfy this
condition. Until that dual authorization and its bounded 1×/10× load behavior
are implemented and verified, the four-case pilot remains the exposed behavior.

## Alternatives considered

- **One financial-crime permission:** rejected; fraud and AML have different purposes,
  teams, evidence and access obligations.
- **Build relationships from embeddings:** rejected; semantic proximity is not a device,
  identity, ownership or transfer relation.
- **Query services live:** rejected; it couples investigations to money-path capacity and
  cannot reproduce a consistent historical snapshot.
- **Make this the first pilot:** rejected; cross-customer disclosure needs proven controls.

## Consequences

**Positive**
- Investigators get explainable, time-bounded relationships and source evidence.
- Fraud and AML share operations while retaining separate purpose policies.

**Negative**
- Device and counterparty data enlarge the sensitive derived-data footprint.
- Cross-customer patterns require strict authorization and side-channel tests.

**Neutral**
- Existing real-time screening and scoring remain authoritative and unchanged.

## Compliance impact

- PCI DSS: tokenize/mask payment identifiers; graph/vector storage admits no PAN or
  sensitive authentication data.
- DORA: test shared-stack capacity/recovery with this workload isolated from payments.
- GDPR: purpose limitation, minimization, retention, audit and human review apply to
  device/behavioral associations; no solely automated adverse decision is introduced.
- PSD2: this lens grants no account access or payment authority.
- CNB: provenance and reproducible snapshots support review; no regulatory approval is claimed.

## References

- [ADR-0303](0303-banking-context-graph-and-authorized-hybrid-retrieval.md)
- [ADR-0308](0308-effective-time-authorization-evidence-graph.md)
- [Implementation roadmap #9945](https://github.com/JiRaska/open-bank-oss/issues/9945)
