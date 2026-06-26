# Architektura

## C4 — kontext & kontejnery

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
│  │  (čtyřoč princip, orchestrace; ŽÁDNÁ úvěr. matem. — libs)│           │
│  └───┬───────────────┬──────────────────┬──────────────────┘           │
│      │ port/out       │ port/out          │ port/out                    │
│      ▼                ▼                   ▼                              │
│  repozitáře      LedgerPostingPort    LoanEventEmitter (outbox)         │
│  (Panache)       CollateralValuation  RiskParameterSource               │
│      │                │                   │                             │
│      ▼                ▼                   ▼                              │
│  PostgreSQL      ledger-service       lending_outbox → Kafka            │
│  openbank_lending (POST /journals)    openbank.lending.events           │
└──────────────────────────────────────────────────────────────────────┘
```

## Hexagonální vrstvy (ADR-0002)

| Vrstva | Balíček | Odpovědnost |
|---|---|---|
| **Doména** | `domain.model` | Čisté agregáty — `LoanApplication`, `Loan`, `LoanInstallment`, `Collateral`, `ProvisioningSnapshot`; stavové enumy; vstupní request záznamy. Nulové frameworkové importy. |
| **Aplikace** | `application.usecase.LendingService`, `application.port.in`, `application.port.out` | Orchestrace + business pravidla: čtyřoč / segregace odpovědností, generování kalendáře, idempotence akruálu, odpis. Řídí čisté primitivy `libs.lending`. |
| **Adaptéry (in)** | `infrastructure.rest.LendingResource`, `infrastructure.servicing.InterestAccrualScheduler` | REST povrch (role-gated, JWT subjekt = důvěryhodný aktér) a naplánovaná servicing smyčka. |
| **Adaptéry (out)** | `infrastructure.persistence`, `infrastructure.adapter`, `infrastructure.client`, `infrastructure.outbox` | Panache repozitáře + mappery, adaptér účetního zápisu (REST nebo no-op), journal factory, outbox dispatcher. |

Doménová vrstva neobsahuje **žádnou úvěrovou matematiku** — amortizace, IFRS 9 ECL a bucketing delikvence jsou čisté primitivy v `openbank-libs` (`libs.lending.Amortization`, `Ifrs9`, `Delinquency`), nezávisle jednotkově testované a znovupoužité službou.

## Klíčové vstupní porty (`application.port.in`)

`ApplyForLoanUseCase`, `DisburseLoanUseCase`, `ServicingUseCase`, `AccrueInterestUseCase`, `WriteOffLoanUseCase`, `CollateralUseCase`, `ProvisioningUseCase` — všechny implementuje jediná `LendingService`.

## Klíčové výstupní porty (`application.port.out`)

| Port | Účel | Výchozí vazba |
|---|---|---|
| `LedgerPostingPort` | Posílat peněžní události jako podvojné zápisy | `RestLedgerPostingAdapter` (při `lending.ledger.backend=rest`), jinak `@Default` no-op |
| `LoanEventEmitter` | Zapisovat doménové události do transakčního outboxu | Panache outbox repozitář |
| `LoanApplicationRepository` / `LoanRepository` / `InstallmentRepository` / `CollateralRepository` | Perzistence | Hibernate Reactive (Panache) |
| `CreditBureauPort` | Signál úvěruschopnosti | konzervativní no-op (`NoOpLendingAdapters`) |
| `CollateralValuationPort` | Přecenění zajištění | no-op vrací deklarovanou hodnotu |
| `RiskParameterSource` | IFRS 9 PD/LGD vstupy | konzervativní výchozí (PD12m 0.03, PDlifetime 0.20, LGD 0.45) |

Výstupní porty dodržují **vzor platformní realizace** (ADR-0045): každý má offline-buildovatelný `@Default` no-op, takže služba se sestaví a nastartuje bez jakékoli externí závislosti; reálná integrace je build-time přepínaný `@Alternative @Priority` adaptér.

## Účetní zápisy — podvojné journaly (ADR-0028 D3)

Úvěrová kniha nikdy nemění zůstatky. Každá peněžní událost se mapuje na vyvážený dvounohý zápis přes čistou, side-effect-free `LendingJournalFactory`, posílaný stejným kontraktem `POST /api/v1/journals`, jaký používá `transaction-service`. Úvěry jsou jednoměnové, takže každý zápis se vyvažuje v rámci své měny (ADR-0025):

| Druh zápisu | MD (DEBIT) | DAL (CREDIT) |
|---|---|---|
| `DISBURSEMENT` | Loans Receivable | Funding Clearing |
| `PRINCIPAL_REPAYMENT` | Funding Clearing | Loans Receivable |
| `INTEREST` (předčasná/včasná hotovost) | Funding Clearing | Interest Income |
| `INTEREST_ACCRUAL` (splatné, zatím bez hotovosti) | Interest Receivable | Interest Income |
| `INTEREST_SETTLEMENT` (hotovost čistí pohledávku) | Funding Clearing | Interest Receivable |
| `WRITE_OFF` | Loan Loss Expense | Loans Receivable |

`idempotencyKey` zápisu je reference ekonomické události (např. `loan:<id>:disbursement`), takže opakování kolabuje do jediného zápisu. Úrokový výnos je uznán právě jednou: akruální průchod ho zaúčtuje k datu splatnosti; hotovostní splátka pak pohledávku *vyrovná* (`INTEREST_SETTLEMENT`) místo opětovného uznání výnosu, kromě případu splacení před akruálem (`INTEREST`, cash-basis).

## Outbox → Kafka tok (ADR-0003)

```
LendingService ── zapisuje ──► lending_outbox (stejná TX jako změna stavu)
                                    │
        LendingOutboxDispatcher (@Scheduled každých 5s, SKIP překryv, batch 25)
                                    │  Panache.withSession
                                    ▼
                 Kafka topic  openbank.lending.events  (String klíč = náhodné UUID, String payload)
                                    │
                       mark sent / mark failed (attempt_count, last_error)
```

Emitované typy událostí: `loan.disbursed`, `loan.interest_accrued`, `loan.written_off`.

## Servicing smyčka úročení

`InterestAccrualScheduler` tiká podle `lending.servicing.accrual.every` (výchozí `24h`, delayed 30s, `concurrentExecution = SKIP`). Každý průchod volá `accrueDueInterest(dnes, batchSize)` (výchozí batch 500), který uzná úrok pro každou splatnou-ale-nenaběhnutou splátku, zaúčtuje zápis `INTEREST_ACCRUAL`, označí řádek (`interest_accrued`) a emituje `loan.interest_accrued`. Nulové úrokové splátky se označí bez zápisu.

## Odolnost

- Volání ledgeru jdou přes `LedgerCallGuard` se SmallRye Fault Tolerance; timeouty REST klienta: connect 2s, read 3s.
- OpenTelemetry tracing + Micrometer/Prometheus metriky přes libs.
