# Billing Service API

Runtime product fee assessment, posting and reversal (ADR-0143).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
## Annual fee-summary issuance retention and rollback (#12187)

The durable `billing_annual_fee_summary_issuance` row is the idempotency record for one
`(account_id, calendar_year)`. Keep it for the lifetime of the billing database, including after
the corresponding `billing_outbox` row reaches `SENT` and is purged. It contains only the account
identifier, calendar year, reservation timestamp, and source event ID; it is intentionally not
subject to outbox retention. The event payload remains in `billing_outbox` only until normal SENT
retention removes it.

The V8 migration backfills this key from every retained `billing.annual-fee-summary.ready` payload.
It aborts on malformed identity fields or multiple existing events for one account/year; resolve
those rows before retrying the migration. On rollback, disable the annual-summary scheduler and
roll back the application first. Do not drop the issuance table after any event has been emitted or
its outbox row purged: doing so removes the only durable guard against re-issuing that regulatory
document trigger. No lossless rollback exists once that evidence is gone.

## Build-time wiring

- **Four-eyes approval store.** `openbank.approval.store: redis` in `application.yaml` makes libs-runtime's `RedisApprovalStoreProducer` supply the Redis-backed `ApprovalStore`. It is read at build time; removing it removes the bean, and the service then fails `quarkusBuild` instead of silently running without four-eyes. The deployed Redis host comes from `QUARKUS_REDIS_HOSTS` on the workload, never the `localhost` default.
