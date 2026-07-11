# ADR-0158 — Account opening validates against product-catalog

Date: 2026-07-09
Decision-Status: Accepted
Delivery-Status: Complete
Author(s): jiri.raska

**Delivery note (2026-07-09):**
- ✅ Shipped: `AccountService.openAccount` now consults product-catalog via a new
  `ProductCatalogPort` before persisting a new account — rejects a product that
  product-catalog confirms does not exist, or that exists but is not `ACTIVE`.
  Fails open (proceeds, logged) when product-catalog cannot be reached.
- ⬜ **Deliberately not done in this increment** (see Alternatives): currency-match
  against the product's declared currency / `multiCurrencyConfig`, `minBalance`/
  `maxBalance` limits, and `eligibilitySegments` (requires the customer's segment,
  not available in `OpenAccountCommand` today). Tracked as a follow-up under issue #668.

## Context

ADR-0105 (unified product identity) made an account's `productId` a canonical
product-catalog UUID and closed the *lookup* gap — `GET product-catalog/products/{id}`
now resolves for every account. It deliberately did not address a second gap issue #668
(product configurability) surfaced: **account opening never validates the product at
all**. `AccountService.openAccount` stores `command.productId` as-is — an operator (or a
buggy caller) can open an account against a UUID that product-catalog has never heard of,
or one that was deliberately deactivated (`ProductStatus.INACTIVE`/`DRAFT`), and nothing
in account-service notices.

This is exactly the "consumers resolve products, not constants" principle from issue
#668, applied to the one check cheap and unambiguous enough to ship in one increment:
**does this product exist, and is it open for business.**

## Decision

### D1 — `ProductCatalogPort`, fail-open

A new outbound port, mirroring the shape already used in `openbank-interest-service`
(ADR-0157) and `openbank-billing-service`'s existing `ProductCatalogPort`:

```kotlin
sealed interface ProductLookupResult {
    data class Found(val product: CatalogProduct) : ProductLookupResult
    data object NotFound : ProductLookupResult
    data object Unavailable : ProductLookupResult
}
interface ProductCatalogPort {
    suspend fun findById(productId: UUID): ProductLookupResult
}
```

`openAccount` calls it right after the sanctions gate (ADR-0032 §C), before IBAN
generation:
- `NotFound` → reject (`ProductNotEligibleException`, mapped to 422 via the
  libs-runtime `IllegalStateExceptionMapper` — deliberately NOT a bare `RuntimeException`
  like the pre-existing `AccountOpeningBlockedByScreeningException`, which has no
  dedicated mapper and falls through to the generic 500 handler; not repeating that gap
  here, though it remains a latent issue in that sibling exception, untouched by this PR).
- `Found` with `status != "ACTIVE"` → reject, same exception.
- `Found` with `status == "ACTIVE"` → proceeds.
- `Unavailable` (timeout/5xx/connection refused) → **proceeds anyway, logged**.
  product-catalog is reference data, not money-path (`rules.yaml: money_path_services`
  does not list it) — an outage there must never block account opening. This is a
  **deliberately different posture** from the sanctions gate three lines above it, which
  fails closed: an unreachable compliance screen is a regulatory risk, an unreachable
  product catalogue is not. The adjacent code makes both postures visible side by side,
  so a future reader sees the distinction is intentional, not an inconsistency.

### D2 — Scope: existence + status only, not yet currency/limits/eligibility

Three richer checks were considered and deferred (not because they're wrong, but
because each needs data this increment doesn't have cleanly):
- **Currency match** — `Product.currency` vs. `command.currency` — complicated by
  `MultiCurrencyConfig`: a multi-currency product may legitimately accept an account
  opened in any of several currencies, and getting that logic right needs its own
  design pass, not a bolt-on here.
- **`minBalance`/`maxBalance`** — these gate an opening *deposit*, and
  `OpenAccountCommand` carries no deposit amount today (balance init is event-driven,
  ADR-0073, decoupled from `openAccount` entirely).
- **`eligibilitySegments`** — requires the customer's segment, which
  `OpenAccountCommand` doesn't carry; resolving it would mean a new dependency on
  party-service, a bigger increment than "does the product exist."

Shipping existence+status now, honestly scoping the rest as follow-up, is preferred over
either blocking on the full set or silently skipping the whole check.

## Alternatives considered

- **Fail closed on product-catalog unavailability**, matching the sanctions gate's
  posture. Rejected: product-catalog carries no regulatory obligation like sanctions
  screening does: a reference-data outage is an availability incident, not a compliance
  gap, and should not turn into an account-opening outage for every product.
- **A shared library module for "resolve + validate a product"** across interest-service,
  billing-service and account-service. Rejected for now: each consumer's failure posture
  and validated fields differ enough (interest-service checks day-count, billing-service
  reads the fee list, account-service checks existence+status) that a shared abstraction
  would need to be either too generic to add value or too configurable to stay simple.
  Revisit if a fourth consumer needs the same shape.

## Consequences

**Positive:** an account can no longer be opened against a nonexistent or deactivated
product — closing a real data-integrity gap issue #668 identified, with the exact same
proven fail-open/fail-closed split already established in ADR-0157.

**Negative:** a new cross-service REST dependency (account-service → product-catalog) on
the account-opening path; mitigated by the fail-open posture (never blocks on an outage).

**Neutral:** no DB migration (validation only, no new persisted field); no change to the
existing sanctions/IBAN checks; money-path controls (2 approvals, threat model) apply to
this PR since `openbank-account-service` is in `rules.yaml: money_path_services`.
