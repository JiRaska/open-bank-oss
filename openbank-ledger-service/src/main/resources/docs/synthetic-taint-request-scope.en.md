# Synthetic taint across the CDI request scope

The synthetic dimension (ADR-0252) lets bank-owned canaries post activity that
real-time regulatory aggregates exclude. The taint travels on the
`x-openbank-synthetic` header and is only accepted from principals named in
`openbank.synthetic.trusted-principals`.

## Why a request-scoped holder exists

`LedgerResource.postJournal` is a Kotlin `suspend` resource method. RESTEasy
Reactive runs the request on a Vert.x event loop and dispatches the body to a
worker via the CDI request context's suspend/resume machinery. A filter that
only sets a `QuarkusHttpUser` property or a `RoutingContext` attribute loses
the taint at that boundary — measured live as the account-overview journey
seed posting rows arriving with `synthetic = false` (issue #14058).

`SyntheticTaintContext` (openbank-libs-runtime) closes the gap: the filter
records the taint BOTH on the security identity (for every non-suspend reader)
AND in the `@RequestScoped` holder (for suspend resources). `postJournal` reads
holder first, then the identity property — one flag, two write paths, so a
regression in either fails closed to real.

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

`SyntheticLedgerDimensionIT` drives the real suspend boundary: it posts
through the actual `LedgerResource` with a trusted canary token and asserts the
row's synthetic flag and all four trial-balance populations, including the
identity-property fallback path when the holder is absent.
