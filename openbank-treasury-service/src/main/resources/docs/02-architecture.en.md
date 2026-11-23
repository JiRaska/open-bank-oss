# Treasury service architecture

Treasury owns the bank's own deals and their lifecycle. The aggregate records a product, counterparty, currency, amount, dates, state and the people responsible for each controlled transition. The API contract in `src/main/resources/openapi.yaml` is the source for the currently implemented endpoints; ADR-0315 also contains planned capabilities and is marked partially delivered.

## Deal path

A dealer creates a draft, submits it for approval and a different human approves it. The domain rejects self-approval and non-human approval independently of the authorization policy. Counterparty and product mandates are checked again at approval because exposure may have changed since submission. A senior override can cover a counterparty breach with a reason; it does not override a product mandate. Only a booked deal can move through confirmation, settlement and maturity. Cancellation and reversal follow separate state rules.

Value movement is posted through the ledger API with an idempotency key derived from the deal and transition. Treasury does not write ledger tables. Its own PostgreSQL migrations store deal state, limits and reconciliation records; a transactional outbox publishes booked deal events to consumers such as the risk engine. This separation lets the risk engine treat a treasury deal as an instrument without taking ownership of its booking.

## Funding check before booking

Before a placement or outflow is booked, the service checks the funding account's current balance against **every** outflow that is booked or confirmed and not yet settled, whatever its value date. The check runs again under an advisory lock before the booking commits. A booking that would overdraw the account is refused with 422, and nothing is written. An unreadable balance is refused with 502: the check never treats a missing balance as zero. No future inflows are projected, so any per-day projection would give the same answer as this sum.

## External and sandbox boundaries

Nostro statement ingestion and reconciliation compare expected settlement with statement entries and expose breaks for review. Sandbox counterparties and quotes are simulations; a successful simulated confirmation is not evidence of a live market connection. Minimum-reserve views and accruals depend on dated source facts, so missing facts must remain visible rather than becoming a default rate.
