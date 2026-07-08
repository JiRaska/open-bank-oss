# Architecture

## C4 — context & containers

```
┌──────────────────────────────────────────────────────────────────────┐
│ openbank-lending-service                                               │
│                                                                        │
│  ┌────────────────────┐   ┌───────────────────────┐                   │
│  │ infrastructure/rest │   │ infrastructure/        │                  │
│  │ LendingResource     │   │   servicing            │                  │
│  │ (REST, @RolesAllowed)│  │ InterestAccrualScheduler│                 │
│  └─────────┬───────────┘   └──────────┬────────────┘                   │
│            │ port/in (use cases)       │ AccrueInterestUseCase         │
│            ▼                           ▼                                │
│  ┌────────────────────────────────────────────────────────┐           │
│  │ application/usecase  LendingService                      │           │
│  │  (four-eyes, orchestration; NO credit math — libs owns it)│          │
│  └───┬───────────────┬──────────────────┬──────────────────┘           │
│      │ port/out       │ port/out          │ port/out                    │
│      ▼                ▼                   ▼                              │
│  repositories    LedgerPostingPort    LoanEventEmitter (outbox)         │
│  (Panache)       CollateralValuation  RiskParameterSource               │
│      │                │                   │                             │
│      ▼                ▼                   ▼                              │
│  PostgreSQL      ledger-service       lending_outbox → Kafka            │
│  openbank_lending (POST /journals)    openbank.lending.events           │
└──────────────────────────────────────────────────────────────────────┘
```

## Hexagonal layers (ADR-0002)

| Layer | Package | Responsibility |
|---|---|---|
| **Domain** | `domain.model` | Pure aggregates — `LoanApplication`, `Loan`, `LoanInstallment`, `Collateral`, `ProvisioningSnapshot`, `LoanProvisioningRecord`, `ProvisioningRunOutcome`; status enums; inbound request records. Zero framework imports. |
| **Application** | `application.usecase.LendingService`, `application.port.in`, `application.port.out` | Orchestration + business rules: four-eyes/segregation-of-duties checks, schedule generation, accrual idempotency, write-off, IFRS 9 provisioning delta. Drives the pure `libs.lending` primitives. |
| **Adapters (in)** | `infrastructure.rest.LendingResource`, `infrastructure.servicing.InterestAccrualScheduler`, `infrastructure.servicing.ProvisioningCycleScheduler` | REST surface (role-gated, JWT subject = trusted actor) and the two scheduled servicing/provisioning loops. |
| **Adapters (out)** | `infrastructure.persistence`, `infrastructure.adapter`, `infrastructure.client`, `infrastructure.outbox` | Panache repositories + mappers, ledger posting adapter (REST or no-op), journal factory, outbox dispatcher. |

The domain layer carries **no credit math** — amortization, IFRS 9 ECL and delinquency bucketing are pure primitives in `openbank-libs` (`libs.lending.Amortization`, `Ifrs9`, `Delinquency`), unit-tested independently and reused by the service.

## Key inbound ports (`application.port.in`)

`ApplyForLoanUseCase`, `DisburseLoanUseCase`, `ServicingUseCase`, `AccrueInterestUseCase`, `WriteOffLoanUseCase`, `CollateralUseCase`, `ProvisioningUseCase`, `RunProvisioningCycleUseCase` — all implemented by the single `LendingService`.

## Key outbound ports (`application.port.out`)

| Port | Purpose | Default binding |
|---|---|---|
| `LedgerPostingPort` | Post cash events as double-entry journals | `RestLedgerPostingAdapter` (when `lending.ledger.backend=rest`), else `@Default` no-op |
| `LoanEventEmitter` | Write domain events to the transactional outbox | Panache outbox repository |
| `LoanApplicationRepository` / `LoanRepository` / `InstallmentRepository` / `CollateralRepository` / `ProvisioningRepository` | Persistence | Hibernate Reactive (Panache) |
| `CreditBureauPort` | Creditworthiness signal | conservative no-op (`NoOpLendingAdapters`) |
| `CollateralValuationPort` | Re-value collateral | no-op returns declared value |
| `RiskParameterSource` | IFRS 9 PD/LGD inputs | conservative defaults (PD12m 0.03, PDlifetime 0.20, LGD 0.45) — **not production-calibrated, see 06 — Compliance** |

Outbound ports follow the **platform realization pattern** (ADR-0045): each has an offline-buildable `@Default` no-op so the service builds and boots with zero external dependency; the real integration is a build-time-gated `@Alternative @Priority` adapter.

## Ledger posting — double-entry journals (ADR-0028 D3)

The loan book never mutates balances. Every cash event maps to a balanced two-legged journal via the pure, side-effect-free `LendingJournalFactory`, posted through the same `POST /api/v1/journals` contract `transaction-service` uses. Loans are single-currency, so each entry self-balances within its currency (ADR-0025):

| Posting kind | DEBIT | CREDIT |
|---|---|---|
| `DISBURSEMENT` | Loans Receivable | Funding Clearing |
| `PRINCIPAL_REPAYMENT` | Funding Clearing | Loans Receivable |
| `INTEREST` (early/on-time cash) | Funding Clearing | Interest Income |
| `INTEREST_ACCRUAL` (due, no cash yet) | Interest Receivable | Interest Income |
| `INTEREST_SETTLEMENT` (cash clears receivable) | Funding Clearing | Interest Receivable |
| `WRITE_OFF` | Loan Loss Expense | Loans Receivable |
| `PROVISIONING`, ECL delta ≥ 0 (impairment increases) | Loan Loss Expense | Loan Loss Allowance |
| `PROVISIONING`, ECL delta < 0 (partial release) | Loan Loss Allowance | Loan Loss Expense |

The journal `idempotencyKey` is the economic-event reference (e.g. `loan:<id>:disbursement`), so replays collapse to a single journal. Interest income is recognized exactly once: the accrual pass books it at due date; cash repayment then *settles* the receivable (`INTEREST_SETTLEMENT`) rather than re-recognizing income, except when repaid before accrual (`INTEREST`, cash-basis).

`PROVISIONING` is the one **signed** kind (`LendingJournalFactory.buildProvisioningLines`, mirroring the FX-revaluation delta pattern in `openbank-ledger-service`): the ledger line always carries the delta's absolute value, and its sign only selects which account is debited. It never touches Loans Receivable — provisioning is an impairment overlay, not a change to the recognized asset.

## Outbox → Kafka flow (ADR-0003)

```
LendingService ── writes ──► lending_outbox (same TX as state change)
                                    │
        LendingOutboxDispatcher (@Scheduled every 5s, SKIP overlap, batch 25)
                                    │  Panache.withSession
                                    ▼
                 Kafka topic  openbank.lending.events  (String key = random UUID, String payload)
                                    │
                       mark sent / mark failed (attempt_count, last_error)
```

Emitted event types: `loan.disbursed`, `loan.interest_accrued`, `loan.written_off`, `loan.provisioned`.

## Servicing posting loop

`InterestAccrualScheduler` ticks on `lending.servicing.accrual.every` (default `24h`, delayed 30s, `concurrentExecution = SKIP`). Each pass calls `accrueDueInterest(today, batchSize)` (default batch 500), which recognizes interest for every due-but-unaccrued installment, books an `INTEREST_ACCRUAL` journal, flags the row (`interest_accrued`), and emits `loan.interest_accrued`. Zero-interest legs are flagged without a posting.

## IFRS 9 provisioning cycle (ADR-0028 Phase 3)

`ProvisioningCycleScheduler` ticks on `lending.provisioning.cycle.every` (default `720h`, ~monthly, delayed 60s, `concurrentExecution = SKIP`; the interval is a plain duration, not calendar-month-aware). Each pass computes the current period key (`yyyy-MM` from the injected `Clock`) and calls `runProvisioningCycle(period, asOf, batchSize)`, which for every `ACTIVE` loan (`LoanRepository.findActive`, up to `batchSize`):

1. Skips the loan if it already has a `loan_provisioning` row for this `period` (idempotent re-run).
2. Recomputes the IFRS 9 stage/ECL snapshot (the same `Ifrs9.assess` + `Delinquency` primitives `ProvisioningUseCase.assess` uses).
3. Reads the loan's most recent **earlier** period's ECL (`ProvisioningRepository.findLatestBefore`), defaulting to zero if this is the loan's first cycle.
4. Posts the **delta** (`newEcl − priorEcl`) as a `PROVISIONING` journal — skipped entirely if the delta is zero — and persists the new `loan_provisioning` row either way (the audit trail is written even when nothing is posted).
5. Emits `loan.provisioned` only when a journal was posted.

**PD/LGD are conservative placeholders** (`ConservativeRiskParameterSource`, `RiskParameterSource.DEFAULT_PD_12M/DEFAULT_PD_LIFETIME/DEFAULT_LGD`) until a real risk-parameter adapter is bound (ADR-0028 D4) — see 06 — Compliance for the explicit "not production-calibrated" caveat.

## Resilience

- Ledger calls go through `LedgerCallGuard` with SmallRye Fault Tolerance; REST client timeouts: connect 2s, read 3s.
- OpenTelemetry tracing + Micrometer/Prometheus metrics via libs.
