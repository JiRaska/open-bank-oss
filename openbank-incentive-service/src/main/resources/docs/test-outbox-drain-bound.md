# Test outbox drain bound — openbank-incentive-service

The concurrency contract IT (`IncentiveRestContractIT > published inventory
reserves once under concurrency then releases commits and expires`) fans out a
batch of incentive reservations and asserts the transactional outbox publishes
every event to the in-memory sink. Delivery to the sink is asynchronous: on a
loaded CI machine the last ack can land after the synchronous dispatch returns,
so the assertion awaits the full batch (at most 10 seconds) instead of racing
the final ack. This bound is a test-only liveness guard; it does not change the
production dispatch semantics, which remain at-least-once with deduplication on
the consumer side.
