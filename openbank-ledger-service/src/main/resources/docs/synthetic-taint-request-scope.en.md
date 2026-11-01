# Synthetic taint across the CDI request scope

The synthetic dimension (ADR-0252) lets bank-owned canaries post activity that
real-time regulatory aggregates exclude. The taint travels on the
`x-openbank-synthetic` header and is only accepted from principals named in
`openbank.synthetic.trusted-principals`.

## Why a request-scoped holder exists

`LedgerResource.postJournal` is a Kotlin `suspend` resource method. Issue
#14058 reports trusted synthetic postings recorded as real on an older deployed
image. That observation does not independently isolate the request-property
loss or coroutine-dispatch mechanism.

`SyntheticTaintRequestFilter` records its decision on the JAX-RS
`ContainerRequestContext` property and in `SyntheticTaintContext`, a
`@RequestScoped` CDI holder with a non-null Boolean initially set to `false`.
Neither write changes `SecurityIdentity`. `postJournal` combines the two
values with OR: either trusted carrier being true marks the posting synthetic.
A false value in one does not override true in the other. The property is a
fallback when the holder is false, not when the CDI bean is absent or inactive.

## What this changes for callers

- Nothing for human traffic: the header is ignored unless the caller principal
  is in the trusted list, and the trust list is empty by default.
- A trusted synthetic principal posts exactly like any other caller, with
  `x-openbank-synthetic: true` on the request. The response journal carries
  `synthetic: true` and every population view (default / real-only /
  synthetic-only / all) reflects it.
- OPA remains the outer gate: each synthetic identity gets exactly the actions
  its `*_rest_ext.rego` rule names — the ledger seed identity holds exactly
  `ledger.create` (see `synthetic-ledger-seed-post`).

## Verification

`SyntheticLedgerDimensionIT` posts through the actual `LedgerResource` over
HTTP with test security claims and asserts the stored synthetic flag and all
four trial-balance populations. It also declines untrusted taint.

The original four tests passed even with the CDI read removed. The extended
profile therefore removes the legacy request property after the real trust
filter has assigned and checked both carriers. A test-only asynchronous filter
holds overlapping synthetic and ordinary POSTs until both decisions are set,
then checks each actual persisted row and a fresh ordinary follow-up. It never
sets the holder or grants an identity, and is disabled outside this profile.

In the prerequisite carrier regression, all five tests pass with the CDI read;
removing only that operand fails two
stored-synthetic-row assertions, including the concurrent scenario. This is a
controlled property-loss regression, not a reproduction of the older image's
failure mechanism, an absent-holder test, or live JWT-signature/OPA acceptance.

Test security identities and advisory OPA do not establish live JWT verification,
enforced policy or deployed behavior. Existing journal classifications are not
rewritten. Dedicated caller provisioning and operational seed postings remain
separate decisions.
