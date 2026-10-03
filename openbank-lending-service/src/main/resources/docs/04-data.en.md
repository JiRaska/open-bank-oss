# Data

## Datastore

- **Engine:** PostgreSQL 16, reactive Postgres client + Hibernate Reactive (Panache).
- **Database:** `openbank_lending` (reactive URL `postgresql://…/openbank_lending`). Governance schema name: `lending_schema` (`governance.yaml`).
- **Schema generation:** `none` — schema is owned by Flyway (`migrate-at-start: true`, `validate-on-migrate: false`).
- **Data classification:** confidential. Data lineage role: both (producer + consumer).

## Tables

| Table | Purpose | Key columns |
|---|---|---|
| `loan_application` | Origination — application in the four-eyes flow | `id`, `party_id`, `requested_amount`+`currency`, `nominal_annual_rate`, `term_periods`, `method`, `status`, `proposed_by` (maker), `decided_by` (checker), `decision_reason`, `created_at`, `decided_at` |
| `loan` | Servicing — live loan booked from a disbursed application | `id`, `application_id` → `loan_application`, `party_id`, `principal`+`currency`, `nominal_annual_rate`, `term_periods`, `method`, `status`, `disbursed_at`, `version` (optimistic lock) |
| `installment` | Contractual repayment schedule, one row per installment | `id`, `loan_id` → `loan`, `number`, `due_date`, `currency`, `opening_balance`, `principal`, `interest`, `payment`, `closing_balance`, `paid`+`paid_at`, `interest_accrued`+`accrued_at`; `UNIQUE(loan_id, number)` |
| `collateral` | Security registered against a loan (AnaCredit categories) | `id`, `loan_id` → `loan`, `type`, `description`, `market_value`+`currency`, `haircut` (`[0,1]`), `valued_at` |
| `lending_outbox` | Transactional outbox (ADR-0003) | `id` (BIGSERIAL), `event_id` (unique), `aggregate_id`, `event_type`, `payload`, `status`, `attempt_count`, `sent_at`, `last_error`, `created_at`, `updated_at` |
| `loan_provisioning` | IFRS 9 stage/ECL history, one row per loan per reporting period (ADR-0028 Phase 3) | `id`, `loan_id` → `loan`, `period` (`yyyy-MM`), `as_of`, `outstanding_balance`+`currency`, `days_past_due`, `bucket`, `stage`, `expected_credit_loss`, `created_at`; `UNIQUE(loan_id, period)` |

### PostgreSQL enum types (V1)

`amortization_method` (`ANNUITY`, `EQUAL_PRINCIPAL`, `BULLET`); `application_status` (`PROPOSED`, `APPROVED`, `REJECTED`, `DISBURSED`); `loan_status` (`ACTIVE`, `CLOSED`, `WRITTEN_OFF`); `collateral_type` (`REAL_ESTATE`, `VEHICLE`, `SECURITIES`, `CASH_DEPOSIT`, `GUARANTEE`, `OTHER`).

`loan_provisioning.bucket` / `.stage` are plain `VARCHAR`, not Postgres enums (`DelinquencyBucket` / `Ifrs9Stage` names from `openbank-libs-domain`) — kept consistent with how the same libs enums are already stored elsewhere, and avoids an enum-type migration if libs adds a stage later.

### Money representation

Monetary amounts are `NUMERIC(20,2)` with a separate `CHAR(3)` ISO-4217 `currency`; rates are `NUMERIC(10,6)`, haircut `NUMERIC(5,4)`. Loans are single-currency.

### Indexes

`idx_loan_application_party`, `idx_loan_application_status`, `idx_loan_party`, `idx_loan_status`, `idx_installment_loan`, partial `idx_installment_due (due_date) WHERE paid = FALSE`, partial `idx_installment_accruable (due_date) WHERE paid = FALSE AND interest_accrued = FALSE` (drives the accrual pass), `idx_collateral_loan`, `idx_lending_outbox_status (status, created_at)`, `idx_lending_outbox_aggregate`, `idx_loan_provisioning_loan_period (loan_id, period DESC)` (drives the delta-baseline read and the idempotency check).

## Flyway migrations

| Version | File | What it does |
|---|---|---|
| **V1** | `V1__init_lending.sql` | Enum types; `loan_application`, `loan`, `installment`, `collateral`, `lending_outbox`; all indexes |
| **V2** | `V2__installment_interest_accrual.sql` | Adds `installment.interest_accrued` + `accrued_at`; partial `idx_installment_accruable` (servicing posting loop, ADR-0028 Phase 2) |
| **V3** | `V3__hibernate_sequences.sql` | `CREATE SEQUENCE lending_outbox_seq INCREMENT BY 50` — Panache id allocation needs `<table>_seq`; CREATE TABLE only made `<table>_id_seq`, and generation is `none`. Rollback: `DROP SEQUENCE lending_outbox_seq;` |
| **V4** | `V4__loan_provisioning.sql` | Adds `loan_provisioning` (IFRS 9 stage/ECL history) + `idx_loan_provisioning_loan_period` (ADR-0028 Phase 3). Rollback: `DROP TABLE loan_provisioning;` |

**Rule (CLAUDE.md):** never edit a migration after it is applied to a live DB — add a new versioned migration. `validate-on-migrate` is off; use `QUARKUS_FLYWAY_REPAIR_AT_START` only as a transient recovery measure.

## PII & sensitive fields

| Field | Classification | Notes |
|---|---|---|
| `party_id` (all tables) | Pseudonymous identifier | UUID reference to `party-service`; no name/contact data stored here |
| `requested_amount` / `principal` / `interest` / `market_value` | Confidential financial | Loan economics |
| `proposed_by` / `decided_by` | Operator identity | JWT subject of the acting bank officer (maker/checker) — internal staff identifier |
| `decision_reason` | Confidential | Free text; may carry credit-decision rationale |
| `lending_outbox.payload` | Confidential | Event JSON containing `loanId`, `partyId`, amounts |
| `loan_provisioning.*` | Confidential financial | IFRS 9 stage/ECL history feeds AnaCredit/FINREP; no direct PII, but the aggregate loan-level impairment is examiner-sensitive |

No customer name, address, IBAN or national ID is stored in this service — only the pseudonymous `party_id`.

## Retention

- **Policy:** 7 years (`governance.yaml: retentionPolicy`), consistent with credit-agreement / accounting record-keeping obligations. AML-driven holds may extend this for flagged cases.
- Closed (`CLOSED`) and written-off (`WRITTEN_OFF`) loans are retained for the statutory period rather than erased; GDPR erasure is overridden by legal record-keeping obligations (see [06 — Compliance](./06-compliance.md)).
- `evidenceExported: false` — this service is not yet wired into the central evidence-export pipeline.
