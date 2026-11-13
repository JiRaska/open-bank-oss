# Database network-outage pilot

`LedgerDatabaseOutageIT` sends a real HTTP posting through the application while
Toxiproxy disables its PostgreSQL connection path. Both JDBC and reactive database
URLs cross the proxy. A separate observer connection reads the isolated database
directly, so evidence collection still works during the outage.

The test requires a bounded server-error response and no journal, idempotency or
outbox residue. It restores the proxy in `finally`, retries the same request, then
replays it again. The recovered operation must create one journal and idempotency
record, with exactly the expected two events sharing the journal transaction's
PostgreSQL `xmin`. HTTP replay must return the same journal ID.

```sh
./gradlew :openbank-ledger-service:test --tests '*LedgerDatabaseOutageIT'
```

Docker is required; unavailable infrastructure fails rather than silently skipping
the test. The dedicated test profile disables global test resources and starts its
own database and proxy. Schedulers/outbox dispatch are disabled so they cannot alter
the rows while atomicity is inspected. Kafka is in-memory for this test.

This proves rejection before a write, connection-pool recovery and idempotent
retry. It does not prove failure halfway through commit, broker recovery, production
failover or recovery-point objectives. Those require separate fault scenarios.
