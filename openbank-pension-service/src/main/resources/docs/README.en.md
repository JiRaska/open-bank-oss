# openbank-pension-service — Documentation

> **What it is:** the participant side of the pension platform: the `PensionContract` lifecycle, strategy elections, contribution schedule, beneficiaries, and evaluation of effective-dated jurisdiction packs ([ADR 0334](../../../../docs/adr/0334-pension-fund-platform.md)). **What it is NOT (slice S1):** a unit register or a payment engine. It collects no contribution and pays nothing out yet.

This documentation is published by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service, [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)).

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / Hibernate Reactive Panache / PostgreSQL (`pension-db`, CNPG)
- **Ports:** 8171 (app), 8090 (management)
- **Events:** none yet.
- **Auth:** `ROLE_API`, `ROLE_OPERATOR` or `ROLE_ADMIN`. A caller without a staff role must send `X-Customer-Party-Id` and only sees that participant's contracts; another party's contract answers 404. Staff read only.
- **Idempotency:** `Idempotency-Key` is required on every POST.
- **Jurisdiction packs:** `jurisdiction-packs/*.json`, loaded and validated at startup. A contract pins the pack version in force on its creation date. The CZ/DPS and CZ/DIP packs are reference data pending legal review.

## API

| Method & path | What it does |
|---|---|
| `POST /api/v1/pension/contracts` | Create a DRAFT contract under the pack in force today |
| `GET /api/v1/pension/contracts/{id}` | Read a contract with its strategy history |
| `POST /api/v1/pension/contracts/{id}/submit` | DRAFT → PENDING_ACTIVATION |
| `PUT /api/v1/pension/contracts/{id}/strategy` | Elect or change the strategy (history kept) |
| `POST /api/v1/pension/contracts/{id}/suspend` / `resume` | Pause / resume contributions |
| `POST /api/v1/pension/contracts/{id}/incentive-evaluation` | Incentives of the pinned pack for one contribution |
| `GET /api/v1/pension/contracts` | The participant's own contracts (staff: by status) |
| `POST /api/v1/pension/simulations` | Illustrative projection per strategy (not advice) |
| `PUT /api/v1/pension/contracts/{contractId}/exit/payouts/{payoutId}/account` | SCA-bound, held (3 days), notified payout-account change |
| `GET /api/v1/pension/operator/payouts`, `GET /api/v1/pension/death-claims` | Staff queues |

Activation happens only through the onboarding workflow (signed application, cooling-off, first
contribution or transfer-in); termination is the S5 quote/sign flow under `/exit`. Every POST
requires `Idempotency-Key` and a retry is answered from the first response.

## Payout sweep heartbeat

The payout recovery sweep registers workflow `pension-payout-schedule-sweep` at startup. Its expected interval comes from `openbank.pension.payout-sweep.every` (default `1h`), the same setting used by the scheduler. A completed sweep records a heartbeat even when there are no overdue installments. A failed repository read or failed workflow restart does not record success.

`openbank_workflow_success_recorded` distinguishes a registered workflow that has not completed from a successful run. `openbank_workflow_last_success_age_seconds` is seeded at registration, so its startup value alone is not proof that a sweep ran. The existing overdue-installment gauge measures workload; the heartbeat measures execution.

An `off` or `disabled` interval disables the schedule and publishes no workflow heartbeat.
