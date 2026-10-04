# Operations

## Commitment relays

`ContextAuditCommitmentRelay` and `ContextDisclosureCommitmentRelay` publish audit and disclosure
commitments from their transactional outboxes to Kafka. Each one polls every 5 seconds and is
delayed by 10 seconds after startup. Without the delay, the first tick builds the relay before
SmallRye Reactive Messaging has connected its outgoing channel, and the scheduler logs
`SRMSG00019: Unable to connect an emitter with the channel ...` once per relay on every pod start.

A row that fails to publish is marked `FAILED`, counted in
`openbank_context_audit_outbox_publish_failures_total` (or its disclosure counterpart), and retried
under the same ID after 30 seconds. A row stuck in `DISPATCHING` for 2 minutes is claimed again.
`openbank_context_audit_outbox_pending` reports rows that are not yet `SENT`.

## Database statement timeout

The application role has a 750 ms `statement_timeout` default in the context database, set by
Flyway migration `V14__application_role_statement_timeout.sql`. Request paths tighten or relax it
per transaction with `set_config('statement_timeout', ...)` from `openbank.context.query-timeout-ms`.

The timeout is deliberately **not** a cluster-wide PostgreSQL parameter. A cluster-wide value also
binds the `postgres` superuser that CloudNativePG uses for backups. On the primary,
`pg_backup_stop()` waits for WAL archiving, so the timeout cancelled it
(`canceling statement due to statement timeout`, SQLSTATE 57014), and every base backup taken on
the primary failed.

Rollback: `ALTER ROLE <app role> IN DATABASE <db> RESET statement_timeout;`
