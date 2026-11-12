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

## Resource-ownership reads, per resource type (ADR-0375 D5)

A grant is authority in itself once a product service projects it, so before an offer is minted this
service asks the service that OWNS the resource whether the grantor really owns it. The answer is
fail-closed in both failing directions: `NOT_OWNED` refuses, and so does `UNVERIFIABLE` — a lookup
that could not be performed is not permission.

| resource type | asked of | status |
|---|---|---|
| `ACCOUNT`, `SAVINGS_GOAL` | account-service (a savings goal is keyed on its owning account, ADR-0153) | resolved |
| `CARD` | card-issuance-service | resolved |
| `DOCUMENT` | document-service `GET /api/v1/documents/{id}/owner`, action `document.verifyOwnership` | resolved (ADR-0375 D5) |
| `PAYMENT`, `STATEMENT` | nothing — see below | always `UNVERIFIABLE`, so every such offer is refused |

The document read uses the NAMED `m2m` OIDC client, so it authenticates as
`service-account-openbank-delegation` (ROLE_API only) and not as the shared `openbank-services`
identity, which document-service refuses for that action. `M2mOidcClientIdentityWiringTest` pins the
client selection.

`PAYMENT` and `STATEMENT` are refused by measurement, not because the work is unfinished.
statement-service is addressed by `accountId` and holds no statement object with an id of its own,
so a `STATEMENT` resource id refers to nothing its owning service can resolve. A payment id resolves
in no single service: six rails own payments and nothing maps an id to its rail. Neither type has an
owner to ask, so both stay refused until those pairs leave the capability matrix.

### Preset catalog corrections are forward migrations (ADR-0375 D7)

The role-preset catalog is seeded by migration, and a preset can be wrong in a way nothing notices
while the enforcement around it is also wrong. `Účetní` and `Pokladník` grant
`ACCOUNT_READ_BALANCES` without `ACCOUNT_VIEW_DETAILS`; that was invisible for as long as the edge's
account-detail read asked for the balances capability. V33 adds the missing capability to both
presets.

It touches the CATALOG only. Grants already minted are immutable snapshots — a grant carries what
the grantor agreed to at the time, and widening one afterwards would be this service editing
somebody's mandate. An existing grantee of those two roles keeps balances and transactions and
loses the account-detail drill-down until the grantor re-shares from the corrected preset.
