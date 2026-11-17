# delegation-service alerts

Alert procedures for `openbank-delegation-alerts` rules that are not generated into
`svc-delegation.md`.

## DelegationPaymentOutcomeUnrecognised

`DomesticPaymentOutcomeConsumer` acked a record on `openbank.domestic.payment.events` it could not
classify: an unknown or missing `ce-type`, or a `status-changed` / `finalized-absent` body it could
not read. If the record was an outcome, its spend reservation stays RESERVED and keeps consuming
the grantee's ceiling.

1. Find the records: the consumer logs `[delegation-payment-outcome] unrecognised ce-type <type>`
   or `undecodable payment event`.
2. A new `ce-type` the rail started publishing: decide whether it settles a reservation and teach
   the consumer (and its pact) before it matters; otherwise add it to the ignored set.
3. An unreadable outcome: read the payment's status in domestic-payment and settle the reservation
   through `POST /api/v1/delegations/{id}/reservations/{rid}/confirm` or `/release`.

## DelegationPaymentOutcomeConflictRate

More than 5 % of settling outcomes found their reservation already in the opposite terminal state.

- Before the edge switch (customer-edge still confirms on the rail's 2xx), every REJECTED or
  CANCELLED delegated payment is a conflict: the reservation is CONFIRMED and only RETURNED may
  release a CONFIRMED one. That is the #16000 defect made visible, not a new fault; finish the
  switch-on order (consumer, rail binding, edge switch) rather than silencing the alert.
- After the edge switch, a sustained share means a second writer settles reservations against the
  outcome. Look for confirm/release REST calls on reservations bound to a payment.
