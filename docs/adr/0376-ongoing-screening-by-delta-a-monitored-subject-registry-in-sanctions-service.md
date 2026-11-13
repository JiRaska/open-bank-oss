---
date: 2026-11-12
decision-status: proposed
delivery-status: partial
followup: "#15723 — the producer half and the kyb-service half are built and disabled; registering subjects and consuming the finding in kyc-service is a later slice of that issue"
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
`PUT /api/v3/sanctions/monitored-subjects/{subjectRef}` and withdraws it with `DELETE`. The owner
of a registration is the calling service itself: the client its service-account token was
issued to, resolved by the service and never sent by the caller. A service therefore reads,
replaces and deletes only the subjects it registered, the same reference under two services is
two subjects, and a caller that is not a service under its own service account (a person, the
fleet's shared client, an unverified principal) is refused. The registry holds what the `CUSTOMER` screening profile reads (entity type, name,
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

**D2a — The delta match is index-driven, and the index only narrows.** The match is the reverse
of the live screen: `word_similarity(subject name, entry text)` with the subject name as the
needle, and pg_trgm indexes only the haystack, so no operator serves it directly (measured:
`search_name <% $entry` is a sequential scan even with sequential scans disabled). What an index
can serve is a necessary condition. A name with `k` distinct trigrams that scores `t` or more
shares at least `ceil(t·k)` of them with the entry text, so its plain `similarity()` to that text
is at least `ceil(t·k) / (k + q − ceil(t·k))`, `q` being the entry's trigram count. The bound
depends on `k`, so each search name is stored as its own row with a size class of similar `k`,
each class has a partial GIN trigram index, and the delta probes each class with
`search_name % entry` under that class's `pg_trgm.similarity_threshold`, set for the transaction
only. Every candidate is then scored by the same `word_similarity` expression a full scan uses,
so the result is the full scan's result; a test compares the two on a varied synthetic registry
and the measurement behind the figures below repeated the comparison at one million subjects
(40 entry texts, 19,032 scored matches, no difference in either direction).

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
- **A full scan of the registry per changed entry.** The form first built, on the grounds that
  pg_trgm cannot index the needle. Rejected after measuring it:
  7.3 s per entry on average on one million subjects (3.7 s for an entry with one name, 11.9 s
  with five), so a feed with 5,000 changes a day costs a database core for ten hours. D2a
  replaces it.
- **Operator forms on the name column (`<%`, `%>` with `word_similarity_threshold`).** The
  obvious index-driven form. Not available: `name <% entry` has the indexed column on the needle
  side and is not an indexable clause, and `name %> entry` computes the opposite containment
  (the entry inside the name), which is a different score and misses true matches.
- **A prefix filter: index only each name's rarest trigrams and fetch every name sharing one.**
  Sound, and measured: an entry text carries 20 to 100 trigrams, so between a quarter and a half
  of the registry shared at least one with it and had to be scored. Slower than the scan.
- **Counting shared trigrams in SQL over a GIN index on the trigram array.** Exact, one index.
  Measured at 2.1 s per five-name entry on one million subjects: the index part was 0.15 s,
  the rest fetching and aggregating some three million (name, trigram) hits. D2a leaves the
  counting inside the GIN scan, which is what the `%` operator does.
- **A separate table of (trigram, name) postings.** The same count from an index-only scan.
  Rejected on size: 1.4 GB per million subjects against 68 MB for D2a.

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
- The delta still costs work proportional to how common the entry's trigrams are in the
  registry: the GIN scan walks the posting lists of every trigram of the entry text. Measured on
  one million synthetic subjects (1.2 million names, Postgres 18.6, one core, no parallel plan):
  0.3 s per entry on average against 7.3 s for the full scan, from about 0.05 s for an entry
  with one name to about 0.6 s for one with five. A day with 5,000 changed entries is about
  25 minutes of one database core at that size, on the database that also serves payment
  screening. That is the capacity statement: the delta is sized for registries up to the low
  millions and feeds up to the low tens of thousands of changes a day; beyond either, D3's
  fallback (a re-sweep) is the cheaper path and takes over by its own bound.
- The registry stores each search name twice (the array the exact score reads and one row per
  name for the index) and carries fifteen partial GIN indexes: 68 MB of trigram indexes
  and 157 MB of name rows with their two b-tree indexes per million subjects, on top of about
  350 MB for the registry itself. Writing the name rows cost about 0.03 ms per subject, measured
  as a bulk insert of 20,000 subjects into the full tables.
- The numbers come from synthetic names, whose trigram distribution is not a real
  population's. No measurement on production-shaped data exists.
- A service can still withdraw or overwrite its own subjects, and the owner binding is as strong
  as the realm's client separation: two services issued the same client are one owner. A caller
  that still authenticates through the fleet's shared client cannot register at all. kyc-service
  does today (its `application.yaml` names `openbank-services`), so the first consumer of this
  ADR needs its own Keycloak client before it can register a subject; kyb-service already has
  one.
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
operations are built and disabled. kyb-service registers the subjects of its approved business
cases and consumes the finding (see the delivery note below), also disabled; kyc-service does
neither yet.

**Delivery note (kyb-service slice).** kyb-service is the first owner. On every write of an
approved business case it records, in the same transaction, which screened subjects must be
registered or withdrawn; a scheduled relay delivers that to the registry under the service's own
client. A finding addressed to it opens a review for an operator, once per finding id. What this
ADR left open or assumed, and what the slice does:

1. **A finding never changes the status of the owner's case (refines D6).** D6 says the owning
   service opens a review. In kyb the review is a fact beside the case's status, not a status:
   an approved case stays SIGNED or ACTIVE, its `updatedAt` does not move and no lifecycle event
   is published, so no applicant-facing path and no consumer can tell that a review is running.
2. **A confirmed match on an approved customer is an exit, not a rejection (new: D8 below).**
3. **The reference.** D1 holds as written: the reference is the `subjectId` of the live screen.
   The entity's is its party id. A person's is a random UUID from this slice on, because the id
   kyb used before embedded a hash of the person's name and a reference travels in a URL path
   and on an event. A person screened before the slice keeps the old id, since the check and any
   clearance are stored under it; that cohort still carries the name-derived form.
4. **The baseline repeats onboarding.** Registering an approved case makes the registry's first
   sweep report what the onboarding screen already reported, as D6's `BASELINE_SWEEP` allows, and
   each such finding opens a review. Not solved here; see the follow-up issues of #15723.
5. **Only name and entity type are registered.** That is what kyb's onboarding screen sends.

**D8 — A confirmed finding on an approved relationship requires an exit, and monitoring outlasts
the decision.** Neither ADR-0256 nor ADR-0284 decides what follows a confirmed sanctions match on
a customer who is already approved. The owning service must not treat it as an onboarding
rejection: the customer has a party, mandates and possibly accounts, and a rejection both tells
consumers that none of that exists and ends monitoring. Instead the owner records that the
relationship has to end, keeps the case approved and registered, and publishes a distinct event
(`BUSINESS_RELATIONSHIP_EXIT_REQUIRED` for kyb) that carries no reason and no finding. Monitoring
is withdrawn only when the relationship has actually ended. Carrying the exit out (restricting
the party, revoking mandates, closing accounts) is a workflow this ADR does not design; until it
exists the decision is surfaced by an alert and executed by hand, and that is a blocker for
switching monitoring on.

Both commands below now print kyb-service files. The ADR is `shipped` when kyc-service appears in
both as well.

Built, from the repository root:

```bash
grep -c 'sanctions_monitored_subjects' openbank-sanctions-service/src/main/resources/db/migration/V23__ongoing_monitoring.sql
grep -c 'monitored-subjects' openbank-sanctions-service/src/main/resources/openapi.yaml
grep -c 'MONITORED_SUBJECT_MATCHED' openbank-contracts/openbank-sanctions-service/asyncapi.yaml
```

Each prints a number greater than zero.

Callers and consumers outside sanctions-service, as the tree has them:

```bash
git grep -l 'monitored-subjects' -- 'openbank-*/src/main/*' ':!openbank-sanctions-service'
git grep -l 'MONITORED_SUBJECT_MATCHED' -- 'openbank-*/src/main/*' ':!openbank-sanctions-service'
```

The pathspec ends in `/*` on purpose. As first written (`'openbank-*/src/main'`) it matched no
file at all, sanctions-service's own included, so the check printed nothing whatever the tree
held; the known positive is the same command without the exclusion:

```bash
git grep -l 'monitored-subjects' -- 'openbank-*/src/main/*'
```

## Compliance impact

- PCI DSS: not applicable — no payment card data; the registry holds names and dates of birth.
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
