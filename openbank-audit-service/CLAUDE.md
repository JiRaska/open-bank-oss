# openbank-audit-service — service notes

## Engineering notes

- **Never hash a value the database stores at lower precision than the JVM holds it.** The
  tamper-evidence chain (ADR-0031 / ADR-0133) writes `record_hash` from the INCOMING
  `AuditEntry` and re-derives it in `verifyChain()` from the object rebuilt out of the row, so
  the two sides agree only if every hashed field survives the round-trip byte for byte.
  `occurred_at`/`recorded_at` are `TIMESTAMPTZ` — microseconds — while `java.time.Instant`
  carries nanoseconds, and on Linux (where the pods run, unlike macOS) `Instant.now()` really
  does produce them. Hashing the unrounded value and storing the truncated one made every link
  permanently unverifiable: the lost digits are not in the database, so no rendering of the
  stored value can reproduce the hash. `openbank_audit_chain_entries_checked` sat at **0**
  against 1066 chained rows, and `AuditChainBroken` was a permanent critical, from the day the
  V5 chain shipped (#3505). `AuditRepository.normalisedForStorage()` now truncates to
  microseconds on both the persist path and inside `chainHash`, so the two agree by
  construction rather than by luck of the platform clock. If a future field is added to the
  canonical list, ask what the column does to it before adding it.
- **A hash-chain test that does not touch a real database proves nothing about the chain.**
  Every unit test here hashes and verifies in-process, which is exactly the step where the two
  sides disagreed — all of them were green for the whole life of the defect. `AuditChainRoundTripIT`
  (Testcontainers, `PostgresTestResource`) is the one that can see it; it pins the instants to
  literal NANOSECOND values so it proves the same thing on a developer's macOS as on a Linux
  runner. Note `audit_entries` carries `no_update_audit`/`no_delete_audit` rules (V2), so a test
  cannot clean up after itself — write with fresh `aggregate_id`s and anchor `verifyChain` with
  `fromEntryId` instead of assuming an empty table.
