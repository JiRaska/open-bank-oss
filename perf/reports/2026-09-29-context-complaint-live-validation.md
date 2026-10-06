# Complaint Context Graph live validation — 2026-09-29

## Result and boundary

An isolated, synthetic stack exercised the normal Domestic Payment, clearing,
Transaction, Dispute and Context APIs. A payment created after Context started
reached `SETTLED`; Transaction completed a debit booking whose persisted
`originating_payment_id` exactly equalled that payment's UUID. A complaint was
opened against the distinct booking UUID. Context projected the complaint,
booking, payment and payment stage with matching source revisions and evidence
references. No graph row, Kafka event or complaint evidence was inserted by hand.

This proves the complaint connection and case-scoped graph behavior in that local
fixture. It does **not** prove a balanced two-account transfer: the operator's JWT
subject did not identify the debtor's Party, so Domestic classified the transfer
as `INTERNAL_CLIENT`. The payer's test Balance decreased by 50 CZK, while the
synthetic recipient's Balance remained zero. The payer's earlier 10,000 CZK test
funding used an authorized Balance API and was not a full Ledger funding path.
Do not present the `SETTLED` status as proof that the recipient was credited.

## Observed checks

| Check | Result |
| --- | --- |
| TLS/OIDC/OPA Context readiness, strict source revisions, declared Kafka topics and DLQs | Passed in the isolated fixture |
| Domestic payment and Transaction booking | `SETTLED` payment, `COMPLETED` debit booking; exact persisted originating-payment relation confirmed by a read-only source query |
| Source complaint and graph | Normal Dispute `POST` produced revision 1. The assigned graph returned 9 nodes and 8 edges, including the booking, independent payment and payment-stage nodes |
| Evidence identity | `BOOKING_REQUESTED` used the booking source revision and exact `transaction:<booking UUID>:<revision>` evidence reference; payment stage used the domestic-payment revision and evidence reference |
| Four-eyes assignment | Maker's own approval returned 409; independent checker approved the maker's case-scoped request |
| Authorization isolation | Assigned maker received 200; unassigned checker, non-admin user, wrong case, wrong purpose and different root each received 403 |
| Historical revisions | Normal interim reply raised Dispute revision 1 to 2. Current graph showed revision 2; authorized `asOf` graph showed revision 1 |
| Revocation | Checker revoked the fixture's assignment; both current and historical graph reads then returned 403 |

The four-principal portable probe was **not** run. Its distinct reader identity did
not have `ROLE_ADMIN`. An automatic safety review rejected granting that standing
role to the synthetic reader because it would broaden privileges. The local proof
therefore used two independent human principals: a maker requesting their own access and
a checker approving it. This is narrower than the probe's full
four-principal acceptance and must not be counted as that test passing. No role or
policy was relaxed to make the probe pass.

A first payment was created before Context started. Its graph had the complaint
and booking but no independent payment node. The second payment was created
*after* Context became ready and supplied the live projection evidence above.
The earlier missing node may reflect consumer offset behavior; this run does not
establish replay or backfill correctness.

## Remaining acceptance

Complete source journeys for the other Context Graph scenarios, the portable
four-principal complaint probe, recipient-side settlement and Ledger evidence,
audit ingestion and drain, authenticated unchanged 100 RPS and 1x/10x performance,
rendered admin UI, required human review, and sandbox deployment remain open.
The isolated fixture did not exercise production Kafka ACLs or sandbox topology.
