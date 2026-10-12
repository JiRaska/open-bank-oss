# SEPA Instant outbox recovery

The `SepaInstantOutboxDeadLettered` alert means at least one event for a committed payment
transition exhausted the shared outbox's ten delivery attempts and reached terminal `DEAD`.
`DEAD` is excluded from the processable backlog, so a backlog of zero is not evidence of delivery.

1. Read the affected row's event ID, payment ID, type, attempt count, creation time and
   `last_error` from `sct_inst_outbox`; compare the payment state with its event sequence.
   Do not copy payment payloads into incident tickets or logs.
2. Establish whether the broker acknowledged this event ID and whether downstream consumers
   processed it. A crash after broker acknowledgement but before `markSent` can produce a
   duplicate on retry. The unchanged outbox `ce-id` lets a current audit consumer deduplicate
   that retry; verify the consumer version and its stored event ID before assuming this happened.
   Repair transport or per-row payload errors before considering requeue.
3. The payment-domain owner and an independent reviewer decide disposition for each event ID:
   confirmed delivered, safe to requeue, or held for investigation. Requeue is an attributable
   data change through the approved operational process, preserving the original event ID and
   payload. There is no automatic replay of historical transitions or bulk `DEAD` rows.
4. Verify the chosen row's final state, broker acceptance, downstream ingestion and the
   `openbank_outbox_dead_lettered{service="sepa-instant"}` gauge. Keep the alert open while any
   unresolved `DEAD` row remains.

The scheme-submission flag stays explicitly false until the settlement path and its recovery
procedure have completed review. The outbox dispatcher remains enabled for ordinary submissions.
Deploy and verify the audit consumer's `ce-id` handling before enabling this producer's relay.
On application rollback, keep V5 and a compatible relay available until all recoverable rows
have approved dispositions. Disabling dispatch or restoring an older binary does not empty the
table; schema removal requires a verified empty recoverable set after writers stop.
