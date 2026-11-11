# Data

This page currently documents only the outbox lifecycle; the rest of the data model is not yet written up.

## Outbox retention (SENT rows)

`delegation_outbox` is a delivery buffer, not a record. Rows that reached the broker (`status = 'SENT'`) are deleted once their `sent_at` is older than `openbank.outbox.retention.sent-days` (default **7**), by libs-runtime's shared `OutboxSentRetentionJob` (ADR-0329, ADR-0327 D8). The repository opts in by delegating `SentOutboxRetention` to `PanacheOutboxRetention`.

- Runs nightly (`openbank.outbox.retention.cron`, default `0 17 3 * * ?`) on every replica; each delete is bounded (`batch-size` 5 000, at most `max-batches` 200 per run), so a long-unpurged table drains over several nights.
- PENDING, FAILED, DISPATCHING and DEAD rows are never touched — DEAD rows are the producer-side DLQ.
- Replaying an event older than the window comes from the Kafka topic or audit-service, not from this table.
- Signals: `openbank_outbox_purged_total{service,status="SENT"}`, `openbank_outbox_purge_failed_total`, and workflow liveness `outbox-sent-retention`.
- Opt-out: `openbank.outbox.retention.enabled=false` (logs a WARN at boot). Don't lower `sent-days` below what any reader of SENT rows needs.

## Organization delegation authority

Preview and offer resolve the actor's current mandate from party-service. An
organization delegation requires explicit sole signing authority with a quorum
of one. A joint mandate or incomplete representation evidence needs a separate
statutory signing workflow. Employee approval groups retain their own
management and operation rules.

At offer creation the grant stores the signing human and, for an organization,
the verified sole mandate ID. Acceptance rechecks the issuing organization's
mandate and confirms that the accepting human can sign for the grantee. The
grantee's SCA challenge belongs to that human. Organization offers created
without verified issuance evidence require a new offer. Existing individual
offers remain eligible after party-service verifies the grantor.
