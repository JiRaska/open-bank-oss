# Business eligibility segments — product & offer filtering (ADR-0284 D10)

The offer list the edge serves depends on which profile the caller is acting in.
A caller authenticated as a natural person sees the personal catalogue; a caller
carrying an `X-Acting-For` mandate (resolved to an entity) is in the business
profile and sees only what that profile may take.

## The rule

Every catalogue entry carries `eligibilitySegments`. A missing or empty array
reads as `ALL` — the catalogue's segment-agnostic default — so legacy entries
stay visible in every profile.

- **Business profile** (`X-Acting-For` resolved): only products whose segments
  include `BUSINESS`, or `ALL`, are returned. A retail-only product must not
  leak into the company context.
- **Personal profile**: the mirror rule holds — a product whose segments are
  exactly `BUSINESS` is not offered to a natural person.

A product the profile cannot take is **invisible rather than greyed out**,
exactly like a withdrawn one. The catalogue itself is never changed; the edge
filters on the segments the catalogue response already carries.

## Where the gate applies

The filter is applied at every surface that exposes an offer, so the list and
the single-offer fetch can never disagree:

- `GET /customer/v1/products` — the customer product/offer list
- `GET /customer/v1/products/term-deposits` — the term-deposit offer list
- the single-offer resolution used by `GET /customer/v1/products/term-deposits/{productId}`
  and by `POST /customer/v1/term-deposits`

The last point is the reason the gate exists on the single-offer path: without
it, a personal-only offer would remain reachable by id under a business profile
(and vice versa), and the list filter would be decorative.

## Errors and observability

Filtering is not an error path — an ineligible product simply does not appear.
An offer that fails the gate on the single-offer fetch is reported as
`404 Not Found`, same as a product the catalogue does not carry at all, so the
response does not reveal that the product exists for other profiles. A failed
or unavailable catalogue still returns `503` as before; the segment gate is
applied only to a successfully fetched catalogue document.
