

## SENT-row retention (ADR-0329)

libs owns the purge, not the tables. `SentOutboxRetention` (libs-domain) is the opt-in port; every `OutboxRepositoryV2` implements it, and a v1 repository opts in with `SentOutboxRetention by PanacheOutboxRetention(OutboxTableShape("<table>"))`. `OutboxSentRetentionJob` (libs-runtime) discovers every such bean and deletes SENT rows older than `openbank.outbox.retention.sent-days` (default 7) nightly, in bounded batches, with workflow liveness `outbox-sent-retention`. The enforced gate `outbox-sent-retention` fails a dispatcher-owning module that neither opts in nor carries a reasoned exemption.
