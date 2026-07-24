---
date: 2026-07-24
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: [openbank-app]
tags: [cards, sca, customer-edge, product-catalog]
summary: "Cards gain a real lifecycle (single-use type, cancel, catalog-enforced quota) and a synthetic encrypted PAN vault; raising a limit, revealing a PAN, issuing and closing a card now require a card-bound SCA challenge."
---

# ADR-0194 — Card lifecycle, synthetic PAN vault and SCA-gated card operations

## Context

Cards were the thinnest domain in the platform, and the gaps only became visible once the
mobile card screen was exercised end to end:

- **The lifecycle had holes that were declared but unreachable.** `CardStatus` listed
  `CANCELLED` and `EXPIRED`, but no transition, use-case or route could ever produce
  either. A customer could freeze a card and block it as lost; they could not close one
  they simply no longer wanted, which is the ordinary case.
- **`maskedPan` was a fabrication.** card-issuance generated
  `"**** **** **** ${(1000..9999).random()}"` at issue time. No PAN was ever produced or
  stored, so the last four digits the customer sees correspond to nothing. The edge's
  comment "no PAN/CVV ever crosses the edge (PCI)" was true only because none existed —
  a property the system had by accident, not by design.
- **The product catalog's card rules were dead config.** `CardConfig` carries `enabled`,
  `maxCards`, `virtualCardAllowed`, `networks`, `tiers` and a per-card fee; the seeded
  `CURRENT_PERSONAL` allows 3 cards and `CURRENT_BUSINESS` 10. A repo-wide search found
  **zero** readers. The edge hardcoded `productCode = "VIRTUAL_DEBIT"`, which is not a
  product code in the catalog at all, so even a future check could not have matched.
- **No card operation had a step-up.** Raising a daily limit, re-enabling online and
  abroad payments, and issuing a card were all reachable with nothing but a
  `ROLE_CUSTOMER` bearer token. Whoever holds an unlocked phone holds the card controls.
  SCA existed and was wired to exactly four payment routes.

Meanwhile the app compensated for each gap with a fiction: a sample card rendered
whenever the customer had none (embossed with their own real name), a "reveal details"
affordance that only worked on that sample, and four preset limit chips topping out at
100 000 CZK that made a higher limit unreachable through the UI although no backend cap
existed.

## Decision

We will give cards a real lifecycle, back the masked PAN with an actual (synthetic)
number, enforce the catalog's entitlements, and gate the risk-increasing operations
behind SCA.

**1. Lifecycle.** `CardType` gains `SINGLE_USE` — a virtual card intended for one
merchant. `Card.cancel()` fills the dead `CANCELLED` status, allowed from `PENDING`,
`ACTIVE`, `SUSPENDED` and `BLOCKED`, terminal thereafter, exposed as
`POST /api/v1/cards/{id}/cancel` upstream and `POST /customer/v1/cards/{id}/cancel` at
the edge. Cancel stays deliberately distinct from block: block is a fraud signal that
closes the card, cancel is housekeeping. Collapsing them would destroy the reason the
issuer records.

**2. Synthetic PAN vault.** At issue, card-issuance generates a Luhn-valid PAN from a
well-known **test** BIN for the network plus a CVV, derives `maskedPan` from that PAN,
and stores both encrypted with AES-256-GCM under a key supplied by configuration; a
missing key fails startup rather than silently storing plaintext.
`GET /api/v1/cards/{id}/secure-details` returns the decrypted values for `VIRTUAL` and
`SINGLE_USE` cards only, never for a card in a terminal status. The edge exposes it as
`POST /customer/v1/cards/{id}/details`, SCA-gated, with `Cache-Control: no-store`; the
app holds the result in composable state for 30 seconds and never persists it. PAN and
CVV are never logged at either layer.

These are synthetic numbers for a sandbox bank. No real cardholder data exists in this
platform, and the test BINs are chosen so that a leaked screenshot authorises nothing.

**3. Catalog-enforced entitlement.** card-issuance resolves the product on issue and
rejects with `CARD_QUOTA_EXCEEDED`, `CARD_PRODUCT_DISABLED` or
`CARD_NETWORK_NOT_ALLOWED`; only live-status cards consume quota. The edge stops
hardcoding `VIRTUAL_DEBIT` and resolves the account's real product code
(account-service → `productId` → product-catalog → `code`).
`GET /customer/v1/cards/entitlements` reports the quota to the app with a `source` field
of `CATALOG` or `FALLBACK`, so the client can tell a real product rule from a permissive
default and refuses to present the latter as authoritative.

**Availability rule, chosen deliberately:** if the catalog is unreachable or does not know
the product code, issuance is **allowed** with a warning naming the code and the failure.
The quota is a soft product rule, not a security control, and product-catalog is KEDA
scale-to-zero here — failing closed would take card issuance down every time the catalog
scales in.

**4. SCA on the risk-increasing half.** sca-service gains a `CARD_MANAGEMENT` purpose
whose dynamic-linking data carries `cardId` and `cardAction`, appended to the signed
payload conditionally, exactly as ADR-0169 did for document signing:

```
challengeId|decision|amount|currency|creditorIban|reference|cardId|cardAction
```

The payment and document layouts are unchanged and pinned byte-for-byte by test. Binding
the card and the action into the signature is the point: an approval to reveal card A
cannot be replayed to raise the limit on card B. The challenge's idempotency key gains
the same two segments, so two card challenges for one party can no longer collapse into
one another.

The edge requires a consumed challenge for **issue**, **cancel**, **reveal details**, and
**a limit increase**. It does *not* require one for freeze, for a limit decrease, or for
turning a channel off. Friction that protects nothing only teaches people to click
through prompts, and every one of those actions reduces the customer's exposure rather
than raising it.

**5. The app stops inventing cards.** An account with no card gets an explicit empty state
offering to issue one, not a sample card wearing the customer's name. Sample
*transactions* stay, badged — they demonstrate a section without impersonating an asset.

## Alternatives considered

- **Leave the PAN absent and drop "reveal details" entirely.** Smallest PCI surface and
  no crypto to operate. Rejected: a virtual card whose number the customer cannot read is
  not a usable product, and the app already had the affordance — it simply lied about
  which card it worked on. Reveal is restricted to virtual and single-use cards for this
  reason; a physical card's number is printed on the plastic and the platform must not
  become a second source for it.
- **Store the PAN in plaintext because the data is synthetic anyway.** Rejected: the
  sandbox is the rehearsal for the controls, and a vault that is only encrypted "when it
  matters" is a vault nobody has ever tested.
- **Fail closed when product-catalog is unreachable.** The textbook answer, and rejected
  on this platform's own history: product-catalog scaling to zero has already caused a
  silent onboarding outage. A soft product rule must not be able to stop issuance, so
  long as the fallback is loud and the client can see that the number is not
  authoritative.
- **Reuse the payment challenge shape for card operations** (amount `0.00`, card id in the
  creditor field). Cheapest to build. Rejected: it makes the audit trail unreadable and
  puts a card id in a field the whole platform reads as a creditor IBAN. The
  document-signing precedent already established how to add a purpose properly.
- **Require SCA on every card mutation, including freeze.** Rejected as above: freezing is
  the action a customer takes *because* something is wrong, and it is the moment where a
  biometric prompt is most likely to fail and least defensible.

## Consequences

**Positive**
- `CANCELLED` becomes reachable, and the customer can close a card without reporting a
  crime that did not happen.
- The last four digits shown to the customer are now the last four digits of the card.
- The catalog's card rules are enforced for the first time, and the app can say what the
  customer is entitled to instead of offering a button that fails on submit.
- Raising a limit or reading a PAN now costs a device-signed, card-bound approval.

**Negative**
- card-issuance acquires a cryptographic key to manage and rotate, and a new failure mode
  at startup if it is missing.
- The edge now reads account-service and product-catalog on the issue path, adding two
  hops and two more things that can be slow.
- `secure-details` is a route that returns secrets. It is single-purpose, type- and
  status-restricted, SCA-gated, `no-store`, and never logged — but it exists, and every
  future change to it needs that list re-checked.

**Neutral**
- Single-use cards are a *type* only. There is no authorisation flow in this platform to
  close one after its first use; the code says so rather than implying the behaviour.
- `EXPIRED` remains unreachable — no expiry job exists yet. Out of scope here.
- Cards issued before this change have no stored PAN; `secure-details` refuses them
  explicitly rather than returning a partial answer.

## Compliance impact

- PCI DSS: engaged in principle — this introduces storage of a card number and a route
  that returns it. The numbers are synthetic test-BIN values for a sandbox platform and no
  real cardholder data is in scope, so no PCI requirement is being claimed as met. The
  controls chosen (encryption at rest, no-store responses, no logging of PAN/CVV,
  virtual-only reveal) are the ones a real deployment would have to evidence.
- DORA: not applicable — no change to ICT third-party arrangements or incident reporting.
- GDPR: not applicable — no new category of personal data; the cardholder name was already
  stored.
- PSD2: dynamic linking is extended to card-management approvals, using the same mechanism
  ADR-0021 established for payments and ADR-0169 for documents.
- CNB: not applicable — no reporting surface changes.

## References

- ADR-0113 — card-issuance bounded context (which already named synthetic sandbox PANs as
  this context's model; this ADR is what finally makes one exist)
- ADR-0189 — field-level encryption and tokenization for PII and PAN
- ADR-0190 — card authorisation, 3DS and PIN processing out of scope (why `SINGLE_USE`
  cannot self-close here)
- ADR-0021 — SCA: decoupled device approval, no auto-approve
- ADR-0169 — customer document access and SCA-bound signing (the conditional-append
  precedent this ADR follows)
- ADR-0089 — customer-facing AI assistant (the copilot card-freeze proposal this screen
  still honours)
