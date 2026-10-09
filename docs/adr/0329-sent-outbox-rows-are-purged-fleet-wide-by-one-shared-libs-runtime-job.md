---
date: 2026-10-03
decision-status: proposed
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [libs, database, privacy-gdpr]
summary: "SENT outbox rows are deleted after 7 days by one shared libs-runtime job over every SentOutboxRetention bean, v1 and v2 outboxes alike; an enforced gate makes opting out a reasoned exemption."
followup: "#11896, #11900, #11901 — case-coordinator, lending and risk-engine outboxes are exempt pending durable evidence or replay decisions; incentive left the exemptions in #11902"
---

# ADR-0329 — SENT outbox rows are purged fleet-wide by one shared libs-runtime job

## Context

ADR-0327 D8 decided *that* SENT outbox rows are purged — `purgeSent(olderThan = 7 d, batch = 5 000)`,
nightly, configurable via `openbank.outbox.retention.sent-days` — and put `purgeSent` on the
`OutboxRepositoryV2` port, implemented in `AbstractPanacheOutboxRepository`. It said the purge
"runs nightly from the base dispatcher". Measured on `origin/main` at `288feeeb25`:

1. **Nothing calls it.** `git grep -n purgeSent -- 'openbank-*-service/src/main/**'` returns no
   caller. Every outbox in the fleet — 40 modules extend `AbstractOutboxDispatcher` — keeps every
   SENT row forever.
2. **The payloads are personal data.** A SENT row keeps its `payload`. sca-service's `sca_outbox`
   carries `DEVICE_ENROLLED` (party id, credential id) and, since #11889, `SCA_DEVICE_DECIDED` with
   the signed payment payload — creditor IBAN included — which outlives the 1 826-day retention
   #11889 gives the durable decision table (its threat model records that as a residual). Payment, party, KYC and consent
   outboxes carry account or identity fields in their event payloads the same way.
3. **The port only reaches half the fleet.** 17 repositories are on the kernel base; 23 are still
   hand-rolled v1 `OutboxRepository` implementations, which have no `purgeSent` at all.
4. **"From the base dispatcher" cannot be built as written.** `@Scheduled` fires only on the
   concrete bean (ADR-0013's CDI constraint, restated in `AbstractOutboxDispatcher`'s KDoc), so a
   base-class purge needs an annotated method added to all 40 dispatchers — and a 41st that forgets
   it retains forever with nothing to notice.
5. **Some outboxes are read after SENT.** case-coordinator's `GET /cases/{caseId}` projects
   proposal evidence from `case_outbox` without an age bound; lending's
   `GET /applications/{id}/evidence` returns its outbox rows as the ADR-0214 evidence bundle;
   risk-engine's `risk_outbox.dedup_key` is its replay guard; incentive's outbox has a different
   schema (`published_at`) and its migration declares the rows audit evidence. A blanket purge
   would silently break all four.

## Decision

We will purge SENT outbox rows with **one** `@ApplicationScoped` job in `openbank-libs-runtime`,
`OutboxSentRetentionJob`, which every service gets by depending on libs-runtime.

- **Opt-in is a type, not a method.** A new domain port `SentOutboxRetention` carries `purgeSent`;
  `OutboxRepositoryV2` extends it, so every kernel repository is covered with no service change.
  A v1 repository implements it in one line by delegating to `PanacheOutboxRetention.purgeSent`,
  the same statement (`OutboxSql.purgeSent`) the kernel base executes. The job injects
  `@Any Instance<SentOutboxRetention>` and purges each one.
- **ADR-0327 D8's numbers stand:** 7 days, batches of 5 000, nightly (`0 17 3 * * ?`), keys under
  `openbank.outbox.retention.*` (`enabled`, `sent-days`, `batch-size`, `max-batches`, `cron`).
  Seven days applies only where a SENT row is replay/debug material rather than a live read model.
  It covers a long weekend plus triage. Case-coordinator has no seven-day API boundary today;
  `CaseThreadProjection` still exposes proposal evidence from its outbox for older cases.
- **Bounded:** at most `max-batches` (200) × `batch-size` per outbox per run, one cut-off per run,
  so a first run against years of rows takes several nights rather than one long transaction.
- **Isolated and observable:** one outbox failing increments `openbank_outbox_purge_failed_total`
  and the rest still run; deletions count into `openbank_outbox_purged_total{status="SENT"}`; the
  ADR-0237 workflow liveness `outbox-sent-retention` is registered at `StartupEvent` (only when the
  service has a non-exempt retention target) and recorded only when every active outbox succeeded.
- **Opting out is explicit.** `openbank.outbox.retention.enabled=false` is the only runtime
  switch and logs a WARN at boot. At build time the enforced gate `outbox-sent-retention` requires
  every dispatcher-owning module to declare a `SentOutboxRetention` class, or carry a reasoned
  exemption in `check-outbox-sent-retention.py`; the exemption set may only shrink after this
  decision is merged. A kernel
  repository whose rows are still a read model sets `sentRetentionExempt=true`; the shared job
  skips it and the gate requires a reason. Today the exemptions are billing, case-coordinator,
  lending and risk-engine, for the reasons in Context item 5 and billing issue #12187. incentive
  has left them (#11902): its rows are not evidence (`incentive_audit_event` is, with the actor),
  and `OutboxTableShape.sentAtColumn` covers its `published_at` column without a migration.
- DEAD rows are out of scope here: they are the producer-side DLQ (ADR-0327 D4) and keep their own
  window (notification's janitor today, `purgeDead` when ADR-0327 Phase 4 wires it).

## Alternatives considered

- **A purge method on `AbstractOutboxDispatcher` (ADR-0327 D8 as written).** Rejected for Context
  item 4: 40 annotated copies, and forgetting one is invisible.
- **Lift `purgeSent` onto the v1 `OutboxRepository` port as abstract.** The compiler would then be
  the gate. Rejected because every hand-written test fake of every service's outbox port would
  have to implement it too (a repository concern leaking into application ports), and the three
  exempt outboxes that must not be purged would need a fake implementation that lies.
- **Partition outbox tables by month and drop partitions.** Already rejected in ADR-0327 D8.
- **Per-service janitors** (the notification-service DEAD janitor pattern). Rejected: 40 copies of
  the same job, liveness and metrics, which is the duplication ADR-0327 exists to remove.

## Consequences

**Positive**
- SENT payloads stop outliving the retention periods of the tables they describe; 36 of 41
  outboxes are purged from the first night after deploy.
- A new outbox is purged by default (kernel base) or fails CI until it decides.

**Negative**
- The first run on a long-lived table deletes up to 1 M rows per outbox; on the largest tables
  that is several nights of steady deletes and autovacuum work.
- Replaying an event older than 7 days from the outbox is no longer possible; replay comes from the
  broker (topic retention) or audit-service.

**Neutral**
- The job runs on every replica; deletes are idempotent and bounded, so racing pods only shorten
  each other's batches.

## Compliance impact

- PCI DSS: not applicable — this shortens how long copies of already-emitted events are kept; it widens no cardholder-data scope.
- DORA:    not applicable — no change to ICT incident handling; the security-scanner incident outbox keeps its durable record in its own table.
- GDPR:    storage limitation — personal data in SENT outbox payloads is no longer kept indefinitely.
- PSD2:    not applicable — SCA decisions keep their durable record in sca-service's decision table.
- CNB:     not applicable — no change to reporting.

## References

- ADR-0327 (kernel-owned outbox v2, D8 retention), ADR-0013, ADR-0237, ADR-0214
- `openbank-libs-runtime/.../persistence/outbox/OutboxSentRetentionJob.kt`
- `.github/scripts/check-outbox-sent-retention.py`
