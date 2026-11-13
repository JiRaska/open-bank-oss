# Delegation grant events: one decoder, many projections

ADR-0232 D3 makes grant enforcement decentralized: every product service keeps its own compacted
projection of the `delegation-events` stream and decides locally, so a money-moving service never
calls delegation-service on the request path. The consequence is that the *envelope* is read once per
enforcing service, and each of those readings is a separate chance to disagree with the producer.

`com.openbank.libs.delegation` holds that reading once.

`DelegationGrantEvent` is the decoded envelope: grant identity, grantor and grantee party, the
resource type and optional resource id, the capability set, the validity window, the lifecycle
revision, and the approval and per-transaction-limit fields. It is a union of what the existing
consumers needed, not a per-service subset — a service that ignores the approval and limit fields
simply does not read them, and the next enforcing service decodes with no new parsing code.

`DelegationGrantEvents` names the event types and splits them by effect:

- `ENABLING` — `DelegationActivated`, `DelegationReinstated`: the grant becomes enforceable.
- `DISABLING` — `DelegationSuspended`, `DelegationRevoked`, `DelegationExpired`,
  `DelegationRenounced`: it stops being enforceable.
- `IGNORED` — `DelegationOffered`, `DelegationDeclined`: an offer is not a grant, so no projection
  changes. These are named rather than left to fall through a default branch, because "we chose not
  to project this" and "we have never heard of this type" are different facts and only one of them
  is a defect.

`DelegationGrantEventDecoder.decode` returns `null` for a record it cannot make sense of — a missing
`aggregateId`, grantee or grantor. `null` is a poison pill, not an empty grant: the caller must decide
whether to ack or dead-letter it, and the decoder never invents a usable grant from a malformed one.
Note what the SmallRye default does with an exception here — `failure-strategy` defaults to `fail`,
which stops the channel — so a consumer that rethrows needs its DLQ wired in the same change.

## Why this is a correctness fix, not a tidy-up

Before this, account-service and card-issuance-service each parsed the same nine leading lines of the
same envelope, character for character. Both were green. Nothing compared them, so a producer-side
field rename would have reddened whichever copy a reader happened to open and left the other matching
nothing — and a grant that stops matching does not raise an error. It keeps a `REVOKED` grant
enforceable, which is the direction that costs money.

The shared decoder makes that single-sided drift impossible to ship quietly: renaming `granteePartyId`
on the wire now fails the decoder's own tests, once, for every enforcing service at the same time.

## Scope

This module is the framework-bearing half. The pure admission decision for delegated debit authority
lives in `openbank-libs-fincrime` beside the ADR-0374 chain, which declares exactly one dependency
(`openbank-libs-domain`) and therefore cannot host a Jackson decoder — see ADR-0375 D2.
