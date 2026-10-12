# Receipted simulator submissions (candidate)

This additive, sandbox-only endpoint stores the exact first `pacs.002` response under a
caller-supplied `(rail, submissionId)` key. A second POST with the same key and identical
request bytes returns that response; different bytes return HTTP 409. The receipt commits
before HTTP 200. PostgreSQL's unique key serializes requests from multiple pods.

The existing `/credit-transfers` path is unchanged and remains stateless. SEPA Instant
must not use automatic retry until its caller has persisted the original submission
identity and request, switched to this endpoint, and proved ambiguous-response recovery.
The new endpoint is disabled by default (`openbank.clearing.receipts.enabled=false`),
and the receipt datasource and Flyway are inactive by default
(`CLEARING_RECEIPT_DB_ACTIVE=false`). This preserves the legacy boot path until the
owned database is deployed.

## Deployment order and rollback

1. Approve the sandbox receipt retention and deletion policy. A receipt contains the
   original response and a request digest; do not copy real customer traffic here.
2. Provision a simulator-owned PostgreSQL cluster and application credential. Configure
   its NetworkPolicy, WAL archiving and base backups, and prove a restore in a separate
   environment. Do not reuse another service's database or credential.
3. Apply migration V1 and deploy the simulator with the endpoint still disabled. Verify
   startup, database ownership, backup health, and the legacy provider Pacts.
4. Bind each rail to a reviewed distinct authenticated client identity before enabling
   the endpoint. The current shared `ROLE_API` token does not establish that boundary,
   so the endpoint must remain disabled. Then require a stable submission ID and prove
   concurrent duplicate, changed-request conflict, restart,
   and lost-response replay through the real network path.
5. Migrate rail clients one at a time. Keep any automatic SEPA retry disabled until its
   own durable intent, booking idempotency and reconciliation proof are complete.

Rollback the application by disabling the endpoint and returning clients to their
previous, manually reconciled behavior. Keep the receipt database and V1 table intact:
dropping it would permit a previously accepted submission ID to be accepted again.
Restore from backup if necessary and reconcile receipt identities before resuming.

The candidate does not declare this rollout complete. Retention, restore proof, caller
identity and integration review remain acceptance gates.
