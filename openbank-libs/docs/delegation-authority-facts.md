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
