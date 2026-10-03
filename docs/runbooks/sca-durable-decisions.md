# Durable SCA device decisions

A successful decision response means its signature evidence and `SCA_DEVICE_DECIDED`
outbox event committed together. It does not mean Kafka or the audit consumer has
accepted the event. Expired authorization cannot be reused; the decision row remains
available as evidence. The record identifies the signing credential, not an independently
verified human or hardware-attested device.

## Upgrade from Redis decisions

This change switches the authoritative decision store. Do not run Redis decision writers
and PostgreSQL decision writers against active challenges at the same time.

1. Stop new challenge initiation and drain in-flight decision, verification and consume
   requests. Existing challenges must complete and be consumed, or expire.
2. Check the source database for unexpired, unconsumed challenges. Keep initiation paused
   until this count is zero; an elapsed fixed delay is not proof of a drain:

   ```sql
   SELECT count(*) FROM sca_challenges
   WHERE expires_at > CURRENT_TIMESTAMP AND consumed_at IS NULL;
   ```

3. Apply Flyway V12 and replace every SCA instance before resuming traffic. The migration
   adds a table; it does not reconstruct expired Redis evidence or change past audit rows.
4. Verify decision acceptance, verification and single-use consumption with a synthetic
   challenge. Check the corresponding decision/outbox rows and audit ingestion separately.

Redis remains required for OTP and idempotency data. Removing the decision dependency
is not a claim that the whole service can run without Redis.

## Failed publication or uncertain response

A transaction failure leaves neither an accepted decision nor its event. A lost HTTP
response can leave both committed; the next decision attempt returns a conflict rather
than replacing the first choice. Continue the existing challenge flow or inspect its
retained decision. Do not delete a decision to let a second choice win.

Outbox retries keep the original event identity and payload. Inspect pending, failed,
dispatching and dead rows; a sent row establishes broker acknowledgement, not audit
persistence. Replay transport failures only after fixing their cause. Retained signatures
and credential keys are evidence: archive or delete them only under the selected retention
policy, preserving links needed for verification. No automatic purge is added here.

## Rollback

Pause initiation and drain unexpired, unconsumed challenges again before restoring a Redis
writer binary. Retain the decision table and its outbox rows; dropping them erases evidence.
There is no safe automatic conversion of recorded PostgreSQL decisions into a fresh Redis
first-decision window. A rollback without a drain can forget a prior refusal or approval.
