# Delegation authority facts: what a rail decides from (ADR-0375 D1)

A payment rail must answer `AdmissionStep.AUTHORITY` — may this actor debit this account? — without
calling delegation-service on the money path, which ADR-0232 D3 refuses. The grant state it needs
comes from account-service's `delegation/authority-facts` endpoint, and the decision is made locally
by a pure kernel. This file is the rail-side half of that boundary; the stream-side half, the shared
decoder for `delegation-events`, is documented beside it.

`com.openbank.libs.fincrime.admission` holds the pure half of the same boundary.
`DelegationAuthorityGrantWire` is the shape account-service puts on the wire, and
`DelegationAuthorityFacts` maps it into the `DelegationFact`s the authority step decides from.

The mapper exists because the four things that are easy to get wrong here are all invisible when
wrong: an unparseable `validFrom` read as "now" makes a not-yet-valid grant enforceable; an absent
`validTo` read as expired refuses a legitimate open-ended delegate; a lookup FAILURE read as "no
grant" turns an outage into a confident refusal; and `active` invented on the rail side is a field
that is always true until the day it is not.

`DelegationFactsLookup` therefore has two cases and not three. `Available(emptyList())` is a
definitive "this party holds nothing here" and must reach the kernel so the chain can REJECT;
`Unavailable` means the question was not answered, and becomes `StepOutcome.Unavailable`, which the
chain maps to HOLD for outbound money. One grant that cannot be read makes the whole lookup
unavailable — a grant whose authority we cannot establish is not a grant we may refuse on.

`DelegationAuthorityFacts.outcome` decides the OWNER case **before** it looks at the lookup at all.
An owner's authority does not come from a grant, so failing to read grants is not failing to
establish it; asking the lookup first would hold every ordinary payment whenever account-service
blipped, which is the kind of fail-closed overreach that gets a control switched off.

## What a rail adapter is allowed to contain

`DelegationAuthorityGrantPayload` is the wire form with every field nullable and stringly typed,
because that is what a deserializer can actually produce: a missing key, a null, or an unparseable
value. `DelegationAuthorityFacts.lookupOfPayload` validates and maps it, and
`DelegationAuthorityFacts.dependencyDown` is the one shape every adapter raises for a transport
failure.

What is left in a rail is transport: build the client, fetch the token, call, and convert any
throwable with `dependencyDown`. That boundary is deliberate, because the judgement a rail would
otherwise make for itself has exactly one dangerous mistake available — dropping the grant it cannot
read. A dropped grant leaves an otherwise-successful answer, and an otherwise-successful answer with
no grants in it is a *definitive* refusal downstream. So a refusal gets manufactured out of a parse
failure, and nothing anywhere disagrees.

That is why the nullable form lives in the shared module rather than in each rail's DTO: the five
rails that have yet to bind the step inherit the decision instead of re-making it.

## The owner is not a parameter

`DelegationFactsLookup.Available` carries `ownerPartyId`, and `DelegationAuthorityFacts.outcome`
takes no owner argument. That is a deliberate narrowing of the API: the authority decision compares
the actor with the owner before it consults any grant, so a caller able to supply its own owner
could decide the owner case against one read while its write-time proof compares another. Removing
the parameter removes the window by construction rather than by review.

Two consequences follow, and both are tested:

- An **answered** lookup with no owner (`ownerPartyId == null`, which account-service returns for an
  unknown account) is `Unavailable`, not a refusal. "We have never heard of this account" is not a
  verdict about anybody's authority over it.
- A **malformed** owner on the wire is `Unavailable` too, never silently read as "no owner" — those
  two look identical downstream and are different facts.

What this gives up, deliberately: while the owner was a parameter, an owner's own payment could be
allowed even when the grant lookup failed, because the caller asserted who the owner was. With the
owner inside the lookup a failed read means the owner is unknown too, so the step holds. This step
therefore answers **delegated** authority only. A rail that can establish ownership by other means
— domestic-payment compares the read owner with the reservation's grantor — must not route an
owner-funded payment through it, and `createPayment` never does.

