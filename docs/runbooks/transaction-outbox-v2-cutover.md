# Transaction outbox V2 cutover

See the [service runbook](svc-transaction.md), ADR-0327 and [issue #11652](https://github.com/JiRaska/open-bank-oss/issues/11652).

1. Get approval for the exact artifact and confirm this service's sandbox hold and rollout requirements. Roll out the v1 compatibility artifact to every replica while the original schema is still in use.
2. Set `OPENBANK_OUTBOX_DISPATCH_ENABLED=false` through GitOps. Verify every old dispatcher is disabled and let its active claims complete or expire. Transactional writers continue to enqueue events.
3. Deploy the v2 artifact while dispatch is paused. Flyway adds nullable `next_attempt_at` and `claim_token` to `transaction_outbox` and adds the v2 indexes. Check the app is ready and every replica uses the v2 artifact.
4. Re-enable dispatch through GitOps. Confirm the backlog drains, delivery continues by aggregate, and the retry and DEAD signals remain healthy.

For rollback, pause dispatch and wait for active claims before restoring the compatible v1 artifact. Retain the added columns, indexes and queued rows. The compatible v1 worker resumes its original retry behavior. Kafka delivery remains at least once, so keep event ID deduplication active throughout.
