---
date: 2026-09-12
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [architecture, analytics, privacy-gdpr]
summary: "When a second customer co-owns a declared asset, wealth-service splits DeclaredHolding into an Asset (the object) and an Interest (one party's claim on it), which also makes company look-through a walk rather than a new model; until then, household totals are per-party only."
---

# ADR-0302 — Asset and Interest: separating the object from the claim on it

## Context

ADR-0301 D1 shipped `DeclaredHolding`: one row per customer per declared thing, carrying an
`ownershipShare`. An adversarial review of that first cut found seven modelling defects. Two were
fixed before the contract reached `main`, on the test of whether deferring them would cost a
breaking change or unrecoverable data: the `HoldingType` enum mixed asset classes with a single
liability bucket, and `revalue` overwrote the previous value with no durable record anywhere. This
ADR is about the five that remain, all of which are additive, and about the one of them that is a
genuine modelling error rather than a missing feature.

**The modelling error: a holding is a claim, and the model has no object.** Two customers of this
bank co-owning one flat produce two `DeclaredHolding` rows, each with `ownershipShare = 0.5`,
and **nothing links them**. `externalReference` is caller-supplied free text — one may write the
cadastre number, the other may not write anything — and ADR-0301 D7 deliberately keeps it off the
event topic. Three consequences follow, and they are not hypothetical for a proposition aimed at
families and business owners:

1. A household view built on ADR-0282 D9 (parties bound through delegation-service) sums both rows
   and cannot tell "one flat, two owners" from "two flats". At 0.5 each it happens to total
   correctly; the moment either customer declares the whole flat because they think of it as
   *ours*, the household is silently worth 150% of one flat.
2. Revaluing "the flat" means revaluing two rows independently. They will drift, and the drift is
   invisible.
3. The same object is priced twice, so the append-only series added in ADR-0301 is two series
   about one thing.

**The same absence blocks look-through, which is the private-banking concept this whole line
exists for.** An affluent customer's flat is frequently owned by an s.r.o. they own 60% of. Today
they must declare either the flat, which misstates the owner, or the stake, which loses the
underlying. Neither is right, and the model offers no third answer.

**Three smaller gaps, stated so they are not rediscovered.** `status: PLEDGED` plus a single
`pledgedToLoanId` cannot express one asset securing two loans or a partial pledge — ADR-0028 lists
cross-collateralisation as explicitly not built, so this matches lending today and will not once it
is. There is no per-asset-class validity policy, so `valuationAgeDays` reports an age nothing
judges. And holdings carry their own currency with no conversion concept; `openbank-fx-service`
already exposes `/api/v1/fx/convert` and a historical rate endpoint, so the capability exists and
only the decision about where it is applied does not.

**Why this is an ADR and not a fix.** ADR-0210 is the precedent that matters: a second store is the
cost, and it superseded ADR-0199 for proposing one before its value was demonstrated. The
Asset/Interest split pays only when two customers of the *same* bank co-own the *same* object and
both declare it, and it imports an entity-resolution problem — is this cadastre id the same flat? —
that the platform needed a whole ADR to solve for natural persons (ADR-0072). Building it now, for
a service with no customers, is speculative generality. Building it after a household view is
shipped on top of the flat model is a data migration plus a broken number already in front of a
customer. So the decision is the trigger, not the date.

## Decision

**D1 — The flat model stands until a named trigger fires.** `DeclaredHolding` remains one row per
party per declared thing. This is not deferral by inertia: it is the ADR-0210 rule that a model is
earned by a demonstrated consumer, and today wealth-service has none (`openbank.wealth.events` is
allowlisted as having no consumer fleet-wide, with that reason).

**D2 — The trigger is the first aggregation across more than one party.** The split is built in the
change that first sums holdings across parties — a household net-worth view, an ADR-0282 D9 *Háj*
roll-up, or any segment rule whose denominator is a household rather than a person. Not before, and
never after: a cross-party total on the flat model is a number that can be wrong by 100% with
nothing able to detect it.

**D3 — When it fires, the shape is Asset, Interest, Valuation, Encumbrance.**
- **`Asset`** is the object. One flat is one Asset however many parties hold it. Identified by a
  jurisdictional natural key where one exists (cadastre id, VIN, ISIN, LEI or IČO) and by a
  bank-minted id otherwise, with the ADR-0212 country-pack shape deciding which key a jurisdiction
  issues rather than a `when (country)` anywhere in this service.
- **`Interest`** is one party's claim on an Asset: `(partyId, assetId, share, kind)` where `kind`
  is `DIRECT` or `THROUGH_ENTITY`. Co-ownership becomes two Interests on one Asset, which is what
  makes the double count *detectable* rather than merely absent.
- **`Valuation`** moves from the holding to the Asset, where it belongs: a flat has one value, not
  one per owner. The ADR-0301 append-only series carries over unchanged in shape.
- **`Encumbrance`** becomes a list against an Asset, each naming its creditor and secured amount,
  which is cross-collateralisation for free and retires the single `pledgedToLoanId`.

**D4 — Look-through is a walk over that graph, not a second model.** An Interest of kind
`THROUGH_ENTITY` points at an ADR-0284 entity party. Where that entity is onboarded, its own
Interests are walked and the customer's effective exposure is the product of the shares along the
path. Where it is not, the stake is a leaf and says so. Same model, two depths, and the depth
available is a fact about onboarding rather than about wealth-service.

**D5 — Matching is a proposal, never an automatic merge.** Deciding two declarations name one
object is the ADR-0072 problem in a new domain, and ADR-0072's answer applies unchanged: a
deterministic match on a jurisdictional key joins automatically, anything weaker opens a
four-eyes case, and nothing merges on a similarity score. A wrongly merged Asset understates a
household's worth and is harder to notice than the double count it was meant to fix.

**D6 — Valuation staleness becomes a per-class policy, pinned like a compliance pack.** Each asset
class carries a validity period in versioned effective-dated data (the ADR-0212 shape, not code),
a holding past it is `STALE`, and a stale valuation is excluded from every derived aggregate —
including the ADR-0301 D7 segment signal. Until this ships, D7's constraint is tightened here:
**a segment rule may use counts, but not totals**, because a total over unjudged ages is a number
whose meaning changes with the customer's diligence rather than their wealth.

**D7 — Currency conversion stays out of wealth-service, permanently.** The service stores each
valuation in the currency it was asserted in and never converts. The customer edge converts for
display through `openbank-fx-service` and labels the rate and its timestamp, the ADR-0024 rule that
a consolidated multi-currency figure is indicative only. This is a decision, not a gap: a converted
figure stored here would be a second FX authority, and the rate that produced it would age silently.

## Alternatives considered

- **Build Asset/Interest now, in the ADR-0301 bootstrap.** Rejected on ADR-0210's measurement: it
  triples the first slice, imports ADR-0072's entity-resolution problem, and pays off only for a
  consumer that does not exist. The trigger in D2 buys the same correctness at the moment it starts
  to matter.
- **Never split; deduplicate at the edge.** Rejected: the edge sees two rows with no shared key and
  free-text references, so it can only guess. Pushing a correctness problem to a layer with strictly
  less information is how the 150%-household answer becomes permanent.
- **Require `externalReference` on every declaration and make it the key.** Rejected as insufficient
  and harmful: the customer with a watch and no serial number cannot declare it at all, and two
  spellings of one cadastre number still produce two objects. It makes matching look solved.
- **Model co-ownership as a list of parties on one holding.** Rejected: it makes one customer's row
  carry another customer's identity, which fails the ADR-0118 erasure model — erasing one co-owner
  would have to reach into a record the other owns.

## Consequences

**Positive**
- The double count becomes detectable at the moment a household view exists, rather than being
  discovered in front of a customer.
- Look-through, cross-collateralisation and per-object valuation all fall out of one split instead
  of being three separate features.
- wealth-service stays one small aggregate until a consumer earns the second.

**Negative**
- The migration, when it comes, is real: every existing holding becomes an Asset with exactly one
  Interest, and the valuation series re-points at the Asset. Mechanical, but not free.
- Between now and D2, any cross-party total is wrong and this ADR is the only thing saying so. That
  is a prose control, which this repo has repeatedly measured as not a control at all — hence D6's
  tightening of ADR-0301 D7 to counts, which is checkable in review.

**Neutral**
- D7 fixes a boundary rather than building anything; `openbank-fx-service` already exposes what the
  edge needs.

### Gate

No new gate at `proposed`. The one candidate worth naming: once D2 fires, a check that no
cross-party aggregation reads `declared_holdings` directly would be decidable, because the
aggregation would have to go through `Interest`. Until the split exists there is nothing to check,
and a gate over a model that does not exist is the kind of scope-by-list this repo has rejected.

### Delivery check

```bash
# D1 holds: the flat model is still what ships, and no cross-party aggregate reads it
test -f openbank-wealth-service/src/main/kotlin/com/openbank/wealth/domain/model/DeclaredHolding.kt
! git grep -rlE 'household|crossParty' openbank-wealth-service/src/main
# D6 interim constraint: no consumer of the wealth topic sums amounts
! git grep -rl 'openbank.wealth.events' -- '*/src/main/resources/application.yaml'
```

Expected: the first prints a path, the two negated ones print nothing. When any of the last two
starts printing, D2 has fired and this ADR moves to `partial`.

## Compliance impact

- PCI DSS: not applicable — no cardholder data.
- DORA:    not applicable — wealth-service is not a critical or important function.
- GDPR:    applies — D5's matching decides that two people's declarations describe one object, which
  is a linkage between data subjects and needs the four-eyes case rather than an automatic join;
  D4's alternative was rejected partly on the ADR-0118 erasure model.
- PSD2:    not applicable — no payment account and no XS2A exposure.
- CNB:     not applicable — this ADR offers no investment service, custody, advice or credit.

## References

- ADR-0301 — declared off-platform holdings; this ADR is the tail its review left.
- ADR-0210 — a read model that owns no facts is a query; a second store is the cost.
- ADR-0072 — deterministic identity resolution with a four-eyes fallback, the shape D5 reuses.
- ADR-0282 — household (D9) and micro-segments (D6), the consumers that fire D2.
- ADR-0284 — entity parties, the other end of D4's look-through walk.
- ADR-0028 — lending collateral, which cross-collateralisation must match when it exists.
- ADR-0212 — versioned effective-dated packs, the shape D3 and D6 borrow.
- ADR-0024 — a consolidated multi-currency figure is indicative only.
