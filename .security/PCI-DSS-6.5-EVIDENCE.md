# PCI DSS 6.5 Evidence

## [2026-05-26T20:45:45Z] Evidence entry

Purpose: record concrete evidence relevant to PCI DSS 6.5.x from the current workspace.

Important scope note:
- This file does **not** claim full PCI DSS 6.5.x coverage.
- It documents implemented controls that are directly verifiable in the current codebase and that materially reduce duplicate execution, inconsistent state transition, and loss of auditability.

### A. Duplicate-request handling at payment boundaries

Evidence:
- SEPA create flow returns an existing payment when the idempotency key already exists (`openbank-sepa-payment/src/main/kotlin/com/openbank/sepa/application/usecase/SepaPaymentService.kt` line 32) and the REST resource replays the cached JSON response from Redis (`openbank-sepa-payment/src/main/kotlin/com/openbank/sepa/infrastructure/rest/SepaPaymentResource.kt` lines 50-60).
- Domestic create flow follows the same pattern (`openbank-domestic-payment/src/main/kotlin/com/openbank/domestic/application/usecase/DomesticPaymentService.kt` line 33; `openbank-domestic-payment/src/main/kotlin/com/openbank/domestic/infrastructure/rest/DomesticPaymentResource.kt` lines 50-60).
- Transaction initiation deduplicates by idempotency key before creating a new transaction (`openbank-transaction-service/src/main/kotlin/com/openbank/transaction/application/usecase/TransactionService.kt` lines 29-31) and persists the key (`openbank-transaction-service/src/main/kotlin/com/openbank/transaction/infrastructure/persistence/entity/TransactionEntity.kt` lines 70-71).

Observed control value:
- Reduces accidental or malicious repeat submission of the same financial instruction.

### B. Atomic state change plus deferred publication

Evidence:
- SEPA payment write + outbox write are executed in one reactive transaction (`openbank-sepa-payment/src/main/kotlin/com/openbank/sepa/infrastructure/persistence/repository/SepaPaymentRepositoryImpl.kt` lines 21-25, 54-68).
- Domestic payment write + outbox write are executed in one reactive transaction (`openbank-domestic-payment/src/main/kotlin/com/openbank/domestic/infrastructure/persistence/repository/DomesticPaymentRepositoryImpl.kt` lines 21-25, 54-68).
- Transaction write + outbox write are executed in one reactive transaction (`openbank-transaction-service/src/main/kotlin/com/openbank/transaction/infrastructure/persistence/repository/PanacheTransactionRepository.kt` lines 44-52).
- Outbox tables explicitly persist delivery state, attempt counter, sent timestamp, and last error:
  - `openbank-sepa-payment/src/main/resources/db/migration/V3__create_sepa_payment_outbox.sql` lines 1-19
  - `openbank-domestic-payment/src/main/resources/db/migration/V3__create_domestic_payment_outbox.sql` lines 1-19
  - `openbank-transaction-service/src/main/resources/db/migration/V4__create_transaction_outbox.sql` lines 1-20

Observed control value:
- Reduces inconsistent outcomes where a business record is written but the corresponding event is lost silently.

### C. Retriable and bounded async publication

Evidence:
- All three outbox dispatchers poll pending/failed rows and either mark them sent or failed.
- Each dispatcher applies bounded concurrency and retry controls using `@Bulkhead`, `@CircuitBreaker`, `@Retry`, and `@Timeout`:
  - `openbank-sepa-payment/src/main/kotlin/com/openbank/sepa/infrastructure/outbox/SepaPaymentOutboxDispatcher.kt` lines 23-52
  - `openbank-domestic-payment/src/main/kotlin/com/openbank/domestic/infrastructure/outbox/DomesticPaymentOutboxDispatcher.kt` lines 23-52
  - `openbank-transaction-service/src/main/kotlin/com/openbank/transaction/infrastructure/outbox/TransactionOutboxDispatcher.kt` lines 23-52

Observed control value:
- Adds explicit, inspectable failure handling rather than untracked best-effort event emission.

### D. Accounting integrity check

Evidence:
- `JournalEntry` validates minimum line count and balanced totals before a journal can exist in valid form (`openbank-ledger-service/src/main/kotlin/com/openbank/ledger/domain/model/JournalEntry.kt` lines 21-40).
- `LedgerService.postJournal` builds the journal and calls `.post()` before persistence (`openbank-ledger-service/src/main/kotlin/com/openbank/ledger/application/usecase/LedgerService.kt` lines 25-56).

Observed control value:
- Prevents posting of unbalanced double-entry journals in the current domain path.

### E. Audit trail capture and retrieval

Evidence:
- `AuditConsumer` parses payloads and persists audit entries with source/correlation/actor fields when present (`openbank-audit-service/src/main/kotlin/com/openbank/audit/application/AuditConsumer.kt` lines 23-44).
- `AuditRepository` stores and queries those entries (`openbank-audit-service/src/main/kotlin/com/openbank/audit/infrastructure/persistence/AuditRepository.kt` lines 32-47).
- `AuditResource` exposes aggregate-based retrieval (`openbank-audit-service/src/main/kotlin/com/openbank/audit/infrastructure/rest/AuditResource.kt` lines 21-29).

Observed control value:
- Supports post-event reconstruction using persisted audit records.

### Explicit non-claims / open gaps

- This slice does not evidence full PCI DSS 6.5.x coverage for the broader secure-coding family (for example, it does not by itself prove protections against every injection, XSS, access-control, or cryptographic misuse class across all applications).
- Saga orchestration / compensation across services remains open.
- `openbank-payment-gateway/` is empty in the current workspace checkout, so no gateway evidence was available.
- Ledger evidence is limited to the balanced-entry invariant and current posting path; broader ledger depth remains open.

### Command evidence cross-reference

See `.security/TEST_EVIDENCE_LOG.md` for exact compile commands and pass status used in this evidence slice.
