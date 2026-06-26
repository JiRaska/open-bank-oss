# Test Evidence Log

## [2026-05-27T00:00:00Z] Saga orchestration wave

All commands below were run from the current workspace and are recorded exactly as executed.

| UTC observed | Repository | Command | Result | Notes |
|---|---|---|---|---|
| 2026-05-27T00:00:00Z | `openbank-transaction-service` | `JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew compileKotlin` | PASS | `BUILD SUCCESSFUL in 2s`; saga domain + repository + orchestrator + client guard compile clean. |
| 2026-05-27T00:00:00Z | `openbank-transaction-service` | `JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew quarkusBuild -Dquarkus.package.type=uber-jar --no-daemon` | PASS | `BUILD SUCCESSFUL in 19s`; CDI wiring verified — `PaymentSagaOrchestrator`, `PanachePaymentSagaRepository`, `LedgerCallGuard` all resolved. |

### Files created / modified

| File | Change |
|---|---|
| `openbank-transaction-service/src/main/kotlin/.../domain/saga/PaymentSaga.kt` | New — saga domain model + state machine (STARTED→PAYMENT_INITIATED→LEDGER_POSTING→COMPLETED / COMPENSATING→COMPENSATED / FAILED) |
| `openbank-transaction-service/src/main/kotlin/.../application/port/out/PaymentSagaRepository.kt` | New — repository port |
| `openbank-transaction-service/src/main/kotlin/.../infrastructure/persistence/entity/PaymentSagaEntity.kt` | New — JPA entity with `@Version` optimistic lock |
| `openbank-transaction-service/src/main/kotlin/.../infrastructure/persistence/repository/PanachePaymentSagaRepository.kt` | New — Panache reactive impl using `Panache.withTransaction/withSession` pattern |
| `openbank-transaction-service/src/main/kotlin/.../infrastructure/client/LedgerRestClient.kt` | New — MicroProfile REST Client interface for ledger-service |
| `openbank-transaction-service/src/main/kotlin/.../infrastructure/client/LedgerCallGuard.kt` | New — CDI-interceptable FT guard (`@Retry`, `@Timeout`, `@CircuitBreaker`) |
| `openbank-transaction-service/src/main/kotlin/.../application/usecase/PaymentSagaOrchestrator.kt` | New — saga orchestrator wired into `TransactionService.initiateTransaction` |
| `openbank-transaction-service/src/main/kotlin/.../application/usecase/TransactionService.kt` | Modified — injects `PaymentSagaOrchestrator`, calls `startSaga` after persist |
| `openbank-transaction-service/src/main/resources/db/migration/V5__create_payment_sagas.sql` | New — Flyway migration: `payment_sagas` table with state CHECK constraint + partial index on non-terminal states |
| `openbank-transaction-service/build.gradle.kts` | Modified — added `quarkus-rest-client-reactive` + `quarkus-rest-client-reactive-jackson` |
| `openbank-transaction-service/src/main/resources/application.yaml` | Modified — added `quarkus.rest-client.ledger-service.url` config |


| 2026-05-26T20:45:45Z | `openbank-transaction-service` | `JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew compileKotlin` | PASS | `BUILD SUCCESSFUL in 20s`; task output reported `:compileKotlin UP-TO-DATE`. |
| 2026-05-26T20:45:45Z | `openbank-ledger-service` | `JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew compileKotlin` | PASS | `BUILD SUCCESSFUL in 27s`; Gradle also emitted deprecation warnings for Gradle 9 compatibility. |
| 2026-05-26T20:45:45Z | `openbank-audit-service` | `JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew compileKotlin` | PASS | `BUILD SUCCESSFUL in 22s`; Gradle also emitted deprecation warnings for Gradle 9 compatibility. |

## Code evidence inventory used for this slice

- `openbank-sepa-payment`
  - `src/main/kotlin/com/openbank/sepa/application/usecase/SepaPaymentService.kt`
  - `src/main/kotlin/com/openbank/sepa/infrastructure/rest/SepaPaymentResource.kt`
  - `src/main/kotlin/com/openbank/sepa/infrastructure/idempotency/RedisIdempotencyStore.kt`
  - `src/main/kotlin/com/openbank/sepa/infrastructure/persistence/repository/SepaPaymentRepositoryImpl.kt`
  - `src/main/kotlin/com/openbank/sepa/infrastructure/persistence/repository/SepaPaymentOutboxRepositoryImpl.kt`
  - `src/main/kotlin/com/openbank/sepa/infrastructure/outbox/SepaPaymentOutboxDispatcher.kt`
  - `src/main/resources/db/migration/V3__create_sepa_payment_outbox.sql`
- `openbank-domestic-payment`
  - `src/main/kotlin/com/openbank/domestic/application/usecase/DomesticPaymentService.kt`
  - `src/main/kotlin/com/openbank/domestic/infrastructure/rest/DomesticPaymentResource.kt`
  - `src/main/kotlin/com/openbank/domestic/infrastructure/idempotency/RedisIdempotencyStore.kt`
  - `src/main/kotlin/com/openbank/domestic/infrastructure/persistence/repository/DomesticPaymentRepositoryImpl.kt`
  - `src/main/kotlin/com/openbank/domestic/infrastructure/persistence/repository/DomesticPaymentOutboxRepositoryImpl.kt`
  - `src/main/kotlin/com/openbank/domestic/infrastructure/outbox/DomesticPaymentOutboxDispatcher.kt`
  - `src/main/resources/db/migration/V3__create_domestic_payment_outbox.sql`
- `openbank-transaction-service`
  - `src/main/kotlin/com/openbank/transaction/application/usecase/TransactionService.kt`
  - `src/main/kotlin/com/openbank/transaction/infrastructure/persistence/repository/PanacheTransactionRepository.kt`
  - `src/main/kotlin/com/openbank/transaction/infrastructure/persistence/repository/TransactionOutboxRepositoryImpl.kt`
  - `src/main/kotlin/com/openbank/transaction/infrastructure/outbox/TransactionOutboxDispatcher.kt`
  - `src/main/kotlin/com/openbank/transaction/infrastructure/persistence/entity/TransactionEntity.kt`
  - `src/main/resources/db/migration/V4__create_transaction_outbox.sql`
- `openbank-ledger-service`
  - `src/main/kotlin/com/openbank/ledger/domain/model/JournalEntry.kt`
  - `src/main/kotlin/com/openbank/ledger/application/usecase/LedgerService.kt`
  - `src/main/kotlin/com/openbank/ledger/infrastructure/persistence/InMemoryJournalRepository.kt`
  - `src/main/resources/db/migration/V1__init_ledger.sql`
- `openbank-audit-service`
  - `src/main/kotlin/com/openbank/audit/application/AuditConsumer.kt`
  - `src/main/kotlin/com/openbank/audit/infrastructure/persistence/AuditRepository.kt`
  - `src/main/kotlin/com/openbank/audit/infrastructure/rest/AuditResource.kt`
  - `src/main/resources/db/migration/V1__create_audit.sql`

## Explicit open gaps carried into this log

- Saga orchestration / compensation is not evidenced in this slice.
- `openbank-payment-gateway/` is empty in the current workspace checkout; no gateway evidence was available.
- Ledger evidence is limited to current balance invariant and posting path, not a broader production-grade ledger control set.

## [2026-05-27T14:30:00Z] Command evidence

All commands below were run from the current workspace and are recorded exactly as executed.

| UTC observed | Repository | Command | Result | Notes |
|---|---|---|---|---|
| 2026-05-27T14:30:00Z | `openbank-admin-ui` | `npm run build` | PASS | Production build completed successfully. |
| 2026-05-27T14:30:00Z | `openbank-consent-service` | `./gradlew compileKotlin` | PASS | `BUILD SUCCESSFUL`. |
| 2026-05-27T14:30:00Z | `openbank-psd2-service` | `./gradlew compileKotlin` | PASS | `BUILD SUCCESSFUL`. |
| 2026-05-27T14:30:00Z | `openbank-sca-service` | `./gradlew compileKotlin` | PASS | `BUILD SUCCESSFUL`. |
| 2026-05-27T14:30:00Z | `openbank-tpp-registry-service` | `./gradlew compileKotlin` | PASS | `BUILD SUCCESSFUL`. |

## QA Findings

- **Product Catalog Browser**: Verified create/edit/status toggle functionality.
- **Error Handling**: Explicit backend errors and unreachable-service banners verified in UI.

## Explicit open gaps carried into this log

- PSD2 Strong Customer Authentication (SCA) flow is skeleton-only; no full production-grade redirect/decoupled evidence yet.
- TPP Registry lacks dynamic client registration (DCR) evidence.
- PSD2 AISP/PISP APIs are stubbed/sandbox-only in this checkout.
