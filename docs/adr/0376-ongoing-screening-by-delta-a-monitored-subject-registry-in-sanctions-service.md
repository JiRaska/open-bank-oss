---
date: 2026-11-12
decision-status: proposed
delivery-status: partial
followup: "none — the producer half is built and disabled; registering subjects and consuming the finding in kyc-service and kyb-service is a later slice with no issue filed yet"
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [aml-sanctions, kyc, privacy-gdpr, kafka]
summary: "sanctions-service keeps a registry of subjects to monitor and matches each changed list entry against it, with a bounded sweep as safety net; a match is published as a per-subject finding, never decided here."
---

# ADR-0376 — Ongoing screening by delta: a monitored-subject registry in sanctions-service

## Context

ADR-0256 decided that a customer is re-screened when a sanctions or PEP list changes, that the
trigger only ever opens a case for a human, and (D3) that the re-screening is scoped to the diff:
"only parties whose prior screening could match a changed entry are re-screened (name-similarity
candidate set over the changed entries), not the whole book". It did not decide where that
candidate set is computed, and its own delivery note records why the first slice could not
compute it: kyc-service "persists no party ↔ list-entry-external-id mapping", so on a
`SANCTIONS_LIST_CHANGED` publication it triggers on every party with a non-terminal KYC case.
That is the in-flight book. The approved book, which is the population ongoing monitoring exists
for, is not reached at all.

What exists today, read from the code:

- sanctions-service records every committed content change of a list entry in
  `sanctions_change_journal` (a trigger, V15) and publishes the changed external ids in chunks.
  The publication says which entries changed. It cannot say who they match.
- The matching engine is `word_similarity(subject name, entry search text)` in Postgres, with a
  pg_trgm index on the entry side, and the thresholds, the list set of the `CUSTOMER` profile and
  the rule for what an analyst's clearance covers all live in sanctions-service.
- No service holds the names of the customers together with the entries. kyc-service holds the
  customers, sanctions-service holds the entries and the engine.

So the candidate set needs the customer names and the matching engine in one place. This ADR
decides which place, because the answer creates a new store of personal data and a new
dependency direction, and each of the alternatives below is a reasonable design someone would
otherwise build.

## Decision

**We will compute the candidate set inside sanctions-service. Callers register the subjects they
want watched; sanctions-service matches each added or changed list entry against that registry
with the live screen's own engine, and publishes each match as a finding addressed to the
service that owns the subject.**

**D1 — A monitored-subject registry.** A caller registers a subject with
`PUT /api/v3/sanctions/monitored-subjects/{ownerService}/{subjectRef}` and withdraws it with
`DELETE`. The registry holds what the `CUSTOMER` screening profile reads (entity type, name,
aliases, date of birth, nationality, country) and nothing else: no document identifiers.
`subjectRef` is the value the caller sends as `subjectId` on a live screen, so that a clearance
recorded for the subject binds here too. Registration is idempotent; different attributes
replace the stored ones. Deregistration deletes the row and its findings and is allowed while
monitoring is disabled.

**D2 — The delta is the change journal.** A trigger on `sanctions_change_journal` queues each
journal row for monitoring, in the transaction that changed the entry, and only while the
registry is non-empty. "What changed" therefore has one definition for the change events of
ADR-0256 and for monitoring, including the journal's rule that a rewrite which only reorders
names is not a change. A scheduled run matches a bounded batch of queued entries against the
registry with the same function, operand order and `CUSTOMER` floor as the live search.

**D3 — A bounded sweep is the safety net.** A second scheduled run re-evaluates a bounded batch
of whole subjects through the function the live screen is built on: never-swept subjects first,
then the least recently swept, every subject again after a configured interval. It catches what
a delta missed and is the only path that sees jurisdiction reference data, which arrives by
migration and passes through no journal. When a delta is too large to match entry by entry (a
backlog bound, or one entry matching more subjects than a bound), the queue is dropped and every
subject becomes due for the sweep, in one transaction, because past that point the sweep is the
cheaper way to do the same work.

**D4 — A finding is unique per subject, list entry and version of the entry's names.** Both
paths raise findings through one rule, and the unique key makes re-running either, or both on
the same change, raise nothing. The version is a hash of the entry's names as an unordered set.
When an entry leaves its list its findings are closed, so the same person being listed again is
a new finding.

**D5 — Clearances are honoured by the live screen's rule, not a copy of it.** A name match is
silent when an unexpired clearance of the same subject and attributes covers that entry
identity. A PEP match and a jurisdiction match are never covered, exactly as in a live screen.

**D6 — A finding is an event, and only an event.** It is written with its outbox row in one
transaction and published as `MONITORED_SUBJECT_MATCHED` (schema version 1) on the existing
screening topic: finding id, owning service, subject reference, match kind, list type, list
entry reference and version, score, the status a live screen would give that match, and how it
was detected. It carries no customer name. No REST operation returns findings. Opening a review
case from it is the owning service's job and is ADR-0256 D2 unchanged: the finding decides
nothing about a customer.

**D7 — Off by default.** `openbank.sanctions.monitoring.enabled` defaults to false. While it is
false, registration is refused (503) instead of being accepted and ignored, a scheduled run
reports its own `DISABLED` outcome, and the liveness heartbeats are not registered.

## Alternatives considered

- **Each caller re-screens its subjects when a change publication arrives.** kyc-service would
  call the screen API for every customer on each `SANCTIONS_LIST_CHANGED` chunk. No new store in
  sanctions-service. Rejected: without a party ↔ entry index the caller cannot scope the work to
  the diff, so one changed entry costs one live screen per customer, and every screen writes a
  `sanctions_checks` row and an event. ADR-0256 already rejected the unscoped form ("re-screen
  the whole book on any content change") for the review-queue noise; this adds the load.
- **A periodic re-screen of every customer by every caller.** The calendar alone. Rejected for
  ADR-0256's reason: it re-screens everyone regardless of signal and still misses the change the
  day after the cycle ran. The sweep in D3 is that calendar, kept as the floor rather than as
  the mechanism.
- **Event-carried list diffs matched in the caller.** Extend the change event to carry the
  changed entries' names and let kyc-service match them against its own customers. The customer
  names never leave kyc-service. Rejected: it puts a second matching engine, a second copy of
  the thresholds and a second clearance rule in every caller, and the live screen and the
  monitoring would then disagree at the margins with nothing to notice. It also moves list
  content, including PEP entries, into every caller's event history.
- **A reverse index instead of a scan.** Make the delta match index-driven. Considered and not
  available with this engine: pg_trgm indexes the haystack of `word_similarity`, and in the
  reverse match the subject name is the needle. A prefilter on shared trigrams is sound only at
  thresholds too low to select anything. The delta therefore costs one pass over the registry
  per changed entry, which D3's fallback bounds.

## Consequences

**Positive**
- A list change reaches exactly the registered subjects it matches, in the approved book as well
  as the in-flight one, as one finding per subject and entry.
- One engine: the delta and the sweep cannot disagree with the live screen about thresholds,
  lists or clearances, because they call the same code.
- A missed delta has a bounded lifetime, the sweep interval, instead of lasting until someone
  remembers to re-screen.

**Negative**
- sanctions-service now holds a standing population of customer identity data, where it used to
  hold one row per screening request. The registry is a new personal-data store with its own
  erasure path (deregistration), and callers must keep it in step with their own records.
- The delta is a sequential pass over the registry per changed entry. Measured on one million
  synthetic single-name subjects (Postgres 18, one core, no parallel plan): about 7 seconds per
  entry. A day with 500 changed entries is roughly an hour of one database core, on the database
  that also serves payment screening.
- The owner of a subject is declared by the caller in the path. The fleet's services share one
  client, so the service cannot verify it, and one internal caller can deregister another's
  subject.
- A second trigger runs on list ingestion. With an empty registry it is one existence probe per
  journal row and no write.

**Neutral**
- No enforcing gate is added: an agent branch may not change `.github/`. What holds the decision
  is structural: the monitoring paths call `SanctionsService` for evaluation and clearance, and
  the existing gates for scheduled methods, liveness adoption, outbox writers and event
  contracts apply to the new code as to any other.
- ADR-0256 is refined, not superseded. Its change events and kyc-service's trigger loop are
  unchanged; a later slice can replace that loop's in-flight-book candidate set with these
  findings.

## Delivery check

`delivery-status` is `partial`: the registry, both paths, the finding event and the two REST
operations are built and disabled; nothing registers a subject and nothing consumes the event.

Built, from the repository root:

```bash
grep -c 'sanctions_monitored_subjects' openbank-sanctions-service/src/main/resources/db/migration/V23__ongoing_monitoring.sql
grep -c 'monitored-subjects' openbank-sanctions-service/src/main/resources/openapi.yaml
grep -c 'MONITORED_SUBJECT_MATCHED' openbank-contracts/openbank-sanctions-service/asyncapi.yaml
```

Each prints a number greater than zero.

Not delivered, and how to tell. Both commands print nothing today; the ADR is `shipped` when the
first prints a caller in another service and the second prints a consumer:

```bash
git grep -l 'monitored-subjects' -- 'openbank-*/src/main' ':!openbank-sanctions-service'
git grep -l 'MONITORED_SUBJECT_MATCHED' -- 'openbank-*/src/main' ':!openbank-sanctions-service'
```

## Compliance impact

- PCI DSS: not applicable — no cardholder data; the registry holds names and dates of birth.
- DORA:    not applicable — no new third-party ICT dependency; the work stays inside one service
  and its own database.
- GDPR:    a new store of personal data in sanctions-service. Data minimisation: only the
  attributes the screening profile reads. The basis is the legal obligation to monitor the
  relationship, as in ADR-0256. Erasure applies to the registry and its findings, which are not
  the statutory AML record, and is carried out by deregistration.
- PSD2:    not applicable — no payment flow is touched; the payment screening gate (ADR-0032) is
  unchanged.
- CNB:     supports the AMLD ongoing-monitoring obligation ADR-0256 operationalises. A finding is
  never shown to the customer (tipping-off) and is never an automated decision about one.

## References

- ADR-0256 — Perpetual KYC: event-driven re-screening between periodic reviews (D2, D3)
- ADR-0032 — Synchronous sanctions/AML screening gate in payment execution
- ADR-0003 — Transactional outbox for Kafka event publishing
- ADR-0237 — Scheduler and external-feed liveness heartbeat
- `docs/threat-models/openbank-sanctions-service.md`, section 6b
