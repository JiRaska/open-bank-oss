# DORA Article 25 Evidence

## [2026-05-26T20:45:45Z] Evidence entry

Purpose: record concrete, code-verifiable evidence relevant to DORA Article 25 from the current OpenBank workspace.

### 1. Payment-processing integrity and resilience

Implemented evidence:
- `SepaPaymentRepositoryImpl` persists payment state and outbox message in one reactive transaction (`openbank-sepa-payment/src/main/kotlin/com/openbank/sepa/infrastructure/persistence/repository/SepaPaymentRepositoryImpl.kt` lines 21-25, 54-68).
- `DomesticPaymentRepositoryImpl` does the same for domestic payments (`openbank-domestic-payment/src/main/kotlin/com/openbank/domestic/infrastructure/persistence/repository/DomesticPaymentRepositoryImpl.kt` lines 21-25, 54-68).
- `PanacheTransactionRepository` persists transaction state and outbox row together (`openbank-transaction-service/src/main/kotlin/com/openbank/transaction/infrastructure/persistence/repository/PanacheTransactionRepository.kt` lines 44-52).
- Outbox schemas exist in Flyway migrations with status/attempt/sent/error tracking: `V3__create_sepa_payment_outbox.sql`, `V3__create_domestic_payment_outbox.sql`, `V4__create_transaction_outbox.sql`.

Why this is relevant:
- The current implementation reduces silent dual-write failure between database state changes and later event publication.

### 2. Bounded retry and failure handling on async dispatch

Implemented evidence:
- `SepaPaymentOutboxDispatcher`, `DomesticPaymentOutboxDispatcher`, and `TransactionOutboxDispatcher` run on a scheduler and apply `@Bulkhead`, `@CircuitBreaker`, `@Retry`, and `@Timeout` before marking outbox rows SENT or FAILED.
- Exact files:
  - `openbank-sepa-payment/src/main/kotlin/com/openbank/sepa/infrastructure/outbox/SepaPaymentOutboxDispatcher.kt` lines 23-52
  - `openbank-domestic-payment/src/main/kotlin/com/openbank/domestic/infrastructure/outbox/DomesticPaymentOutboxDispatcher.kt` lines 23-52
  - `openbank-transaction-service/src/main/kotlin/com/openbank/transaction/infrastructure/outbox/TransactionOutboxDispatcher.kt` lines 23-52

Why this is relevant:
- The current implementation adds bounded retry and explicit failed-state recording instead of fire-and-forget publication.

### 3. Duplicate-request protection at payment entrypoints

Implemented evidence:
- SEPA endpoint requires `Idempotency-Key`, checks Redis for prior response, and returns `X-Idempotency-Replayed` on replay (`openbank-sepa-payment/src/main/kotlin/com/openbank/sepa/infrastructure/rest/SepaPaymentResource.kt` lines 41-65; `openbank-sepa-payment/src/main/kotlin/com/openbank/sepa/infrastructure/idempotency/RedisIdempotencyStore.kt` lines 18-37).
- Domestic endpoint uses the same pattern (`openbank-domestic-payment/src/main/kotlin/com/openbank/domestic/infrastructure/rest/DomesticPaymentResource.kt` lines 41-65; `openbank-domestic-payment/src/main/kotlin/com/openbank/domestic/infrastructure/idempotency/RedisIdempotencyStore.kt` lines 18-37).
- Transaction initiation checks for an existing transaction by idempotency key before creating a new one (`openbank-transaction-service/src/main/kotlin/com/openbank/transaction/application/usecase/TransactionService.kt` lines 29-31) and stores the key in persistence (`openbank-transaction-service/src/main/kotlin/com/openbank/transaction/infrastructure/persistence/entity/TransactionEntity.kt` lines 70-71).

Why this is relevant:
- The current code reduces duplicate financial instruction execution from repeated client submission.

### 4. Accounting integrity control

Implemented evidence:
- `JournalEntry` requires at least two lines and rejects unbalanced entries (`openbank-ledger-service/src/main/kotlin/com/openbank/ledger/domain/model/JournalEntry.kt` lines 21-40).
- `LedgerService.postJournal` constructs lines, calls `.post()`, persists the entry, and emits a journal-posted event only after the domain object has been validated (`openbank-ledger-service/src/main/kotlin/com/openbank/ledger/application/usecase/LedgerService.kt` lines 24-72).

Why this is relevant:
- The code evidences an explicit double-entry balance invariant before posting.

### 5. Traceability and audit retrieval

Implemented evidence:
- `AuditConsumer.consume` parses incoming JSON, derives aggregate metadata, and saves an `AuditEntry` (`openbank-audit-service/src/main/kotlin/com/openbank/audit/application/AuditConsumer.kt` lines 23-47).
- `AuditRepository.save` persists audit records and `findByAggregateId` retrieves them ordered by occurrence time (`openbank-audit-service/src/main/kotlin/com/openbank/audit/infrastructure/persistence/AuditRepository.kt` lines 32-47).
- `AuditResource.getAuditTrail` exposes aggregate-based retrieval (`openbank-audit-service/src/main/kotlin/com/openbank/audit/infrastructure/rest/AuditResource.kt` lines 21-29).

Why this is relevant:
- The current workspace contains a concrete ingestion and query path for audit evidence.

### Known remaining risks / gaps

- Saga orchestration remains open; this slice only proves per-service controls, not coordinated end-to-end payment orchestration.
- `openbank-payment-gateway/` is empty in the current workspace checkout, so gateway/orchestration evidence for DORA Article 25 could not be attached.
- Ledger coverage is narrow: balance validation exists, but broader persistence-backed ledger controls, reconciliations, and close-process controls are not evidenced here.
- Audit retrieval is currently `@PermitAll` in `AuditResource`; stronger retrieval authorization is not evidenced in this slice.

### Command evidence cross-reference

See `.security/TEST_EVIDENCE_LOG.md` for exact compile commands and observed pass status captured for this evidence entry.
