---
date: 2026-09-12
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [architecture, customer-edge, privacy-gdpr]
summary: "Affluent/private-banking starts with one new bounded context, openbank-wealth-service, owning customer-DECLARED off-platform holdings; net worth is composed at the edge from owning services, never copied; custody and advice are later ADRs."
---

# ADR-0301 — Wealth layer: declared off-platform holdings in a new wealth-service, net worth composed at the edge

## Context

The platform serves retail customers and, since ADR-0284, legal entities. It has no notion of
what a customer owns *outside* the bank, and therefore no way to present the one view an affluent
customer asks for first: "what am I worth, across everything". A private-banking proposition
(net-worth view, collectibles as an asset class, lombard credit against a portfolio, custody,
advice, concierge) was drafted as eight new services. This ADR is the result of grilling that
draft against the decision history, and it is deliberately much smaller than the draft.

What the decision history already settles, verified on `origin/main` on 2026-09-12:

- **A read model that owns no facts is a query, not a service.** ADR-0199 decided a new
  `crm-service` for Customer 360; ADR-0210 superseded it because analytics-sink's
  `silver_current_state` already *is* that projection, and a second consumer group writing a
  second database was the whole cost. A "net-worth aggregator service" that consumes account,
  balance, lending and kyb events into its own tables is ADR-0199 again under a new name.
- **Every customer-facing figure comes from the owning service** (ADR-0089, restated as
  ADR-0210 D3/D4). The 360 view is operator-facing precisely because its figures are
  non-authoritative. A customer-facing net worth cannot be served from silver; it must be composed
  from `balance-service`, `lending-service`, `account-service` and `kyb-service` at the customer
  edge, the way the app home already composes accounts and cards.
- **The family / owner graph is decided three times over.** ADR-0284 D3 puts the entity party and
  `PartyMandate` in party-service and D8 declares the owner graph an ecosystem asset consumed by
  loyalty, 360, catalog and campaigns. ADR-0232/0249 own person-to-person delegation and the
  dispositor. ADR-0282 D9 adds the household (*Háj*). A "family-office tree" service would be a
  fourth owner of the same edges.
- **Collateral is loan-bound.** `openbank-lending-service`'s `Collateral` (`CollateralType`
  `REAL_ESTATE | VEHICLE | SECURITIES | CASH_DEPOSIT | GUARANTEE | OTHER`, four-eyes gated since
  issue #621, summed into IFRS 9 LGD) carries a `loanId`. A painting the customer merely *has* is
  not collateral, and making lending store loan-less rows would put a non-credit fact inside a
  money-path aggregate whose every row moves a provision.
- **Crypto and treasury are out of scope** (ADR-0188, ADR-0185), and ADR-0188 fixes the shape any
  future external-network integration takes: a new bounded context behind a rail-style port, never
  an extension of the ledger. Securities custody is exactly that shape and is *not* decided here.
- **Case work, approvals and engagement have homes.** Concierge-style cases are ADR-0244 Temporal
  case workflows with ADR-0227's approval inbox; education and lifestyle surfaces are ADR-0220/0261
  engagement surfaces; premium tiers are product-catalog eligibility segments plus ADR-0282
  micro-segments. None of those needs a wealth-specific service.

What is left after subtraction is one genuinely new kind of fact: **a holding the customer declares
and the bank does not hold** — real estate, art, watches, wine, vehicles, securities kept at another
custodian, a stake in a company the platform has not onboarded, a claim, a private loan given. It
has a value the customer asserts or an expert attests, a valuation date, a source, supporting
documents (title deed, provenance chain, appraisal, insurance) and a natural link into two things the
platform already does: it can be *promoted* into lending collateral, and it can be *evidenced* by
document-service's WORM-stored, PAdES-sealed documents (ADR-0162). No existing aggregate can hold it
without lying about what it is: party-service is identity, account-service is a bank product,
lending's collateral is a credit-risk input.

Why now: the ADR-0284 owner graph and ADR-0282 micro-segments make "affluent" detectable and
addressable for the first time, and every lifestyle feature in the draft (collectibles, lombard,
advice) reads or writes this fact. Building any of them first would create the aggregate ad hoc
inside the wrong service.

## Decision

**D1 — One new bounded context, `openbank-wealth-service`, owns the `DeclaredHolding` aggregate
and nothing else that another service owns.** A declared holding is a customer- or banker-attested
record of an asset or liability outside the platform: `holdingType` (a closed enum, first slice
`REAL_ESTATE | COLLECTIBLE | EXTERNAL_SECURITIES | EXTERNAL_COMPANY_STAKE | VEHICLE |
EXTERNAL_DEPOSIT | PRIVATE_CLAIM | EXTERNAL_LIABILITY`), a `Valuation` (money, currency, `valuedAt`,
`source` ∈ `CUSTOMER_DECLARED | EXPERT_APPRAISAL | MARKET_REFERENCE`, appraiser reference), a list of
document-service document ids as evidence, ownership share, and the owning party (a natural person
or an ADR-0284 entity party). It is hexagonal (ADR-0002), Postgres-per-service (ADR-0009),
outbox-published (ADR-0003) as `openbank.wealth.events` with `HoldingDeclared`, `HoldingRevalued`,
`HoldingWithdrawn`. It is **not** a money-path service: it moves no money and posts nothing to the
ledger.

**D2 — Net worth is composed at the customer edge from owning services and is never persisted
by wealth-service.** `openbank-customer-edge` gains a `GET /customer/v1/net-worth` that fans out to
`balance-service` (on-platform balances), `lending-service` (loans, and approved collateral), the
ADR-0284 owner graph (stakes in onboarded entities) and `wealth-service` (declared holdings), and
returns a typed tree whose every leaf names its source service and its `asOf`. `wealth-service`
holds no copy of any on-platform figure. The customer-facing rule of ADR-0089 is kept by
construction: an authoritative figure is only ever a proxied answer from its owner, and a declared
holding is labelled `CUSTOMER_DECLARED` on the wire so no consumer can mistake it for a bank
position. The route is a flat noun under the edge's own prefix, which is `/customer/v1` and not
`/api/v1`: every resource there is `/accounts`, `/cards`, `/activity`, `/complaints`, with the party
taken from the `party_id` JWT claim, and no `/me` namespace exists to join. This ADR said
`/api/v1/net-worth` when it was written and that prefix belongs to the backing services, not to the
edge; corrected in the change that built it (#9772).

**D3 — A declared holding can be promoted to lending collateral; lending never reads
wealth-service.** Promotion is a lending operation: the existing `POST
/api/v1/lending/loans/{id}/collateral` gains an optional `declaredHoldingId`. Lending copies the
valuation into its own `Collateral` row at that moment (the credit-risk input is *its* fact from then
on, subject to its own four-eyes and haircut) and stores the id as provenance only. The dependency
direction is `wealth → lending` (wealth-service may subscribe to `collateral.decided` to show
"pledged"), never `lending → wealth`; a money-path service does not depend on a lifestyle one. A
declared holding that is pledged cannot be withdrawn until lending releases the collateral, enforced
in wealth-service from the event, not by a synchronous call.

**D4 — Evidence lives in document-service, provenance is a document chain, and nothing is
tokenised.** A holding references document-service documents (ADR-0162 WORM storage, PAdES seal
where the bank attests). A collectible's provenance is an ordered list of such documents, and a bank
attestation of provenance is a sealed document, not a token or an on-chain record — ADR-0188 stands.
wealth-service stores ids, never binary content (ADR-0161).

**D5 — Ownership, family and company structure are consumed from their owners, not modelled
again.** Who may see or declare a holding is decided by party-service's mandates (ADR-0284 D3) and
delegation-service's grants (ADR-0232), evaluated by the OPA sidecar (ADR-0034) exactly as for
accounts. The "family net worth" view is the household of ADR-0282 D9 applied to D2's composition.
wealth-service carries `ownerPartyId` and an ownership share, and no graph edges of its own.

**D6 — Custody, investment advice and lombard credit are explicitly not decided here.** Each
enters as its own ADR when funded: custody as a money-path bounded context behind a rail-style port
(ADR-0188's shape, with ledger postings through transaction-service per ADR-0108), advice with its
own suitability consent type in consent-service (ADR-0126) and MiFID II treatment, lombard as a
lending product whose collateral arrives through D3. This ADR makes them cheaper, not present. Until
those ADRs exist, wealth-service offers no valuation opinion, no recommendation and no price feed
of its own: `MARKET_REFERENCE` valuations are supplied by a caller with a named source, not fetched.

**D7 — Affluent is a segment, not a service.** The premium tier is an `eligibilitySegment` in
product-catalog (the ADR-0284 D10 mechanism) fed by an ADR-0282 D6 micro-segment rule that may read
the *count and declared total* of holdings from `openbank.wealth.events` — never the itemised list,
which stays inside wealth-service and the customer's own view.

## Alternatives considered

- **Eight services as drafted (wealth, custody, advisory, lombard, collectibles, concierge,
  family-office, academy).** Rejected: five of them re-own facts ADR-0210, 0284, 0232, 0282, 0244,
  0227 and 0220 already placed, and the aggregator half is ADR-0199 repeated. The one fact none of
  those ADRs holds is the declared holding; that is what D1 keeps.
- **wealth-service as a full aggregator with its own copies of balances, loans and stakes (own
  consumer group, own Postgres projection).** Rejected on ADR-0210's measurement: the projection
  exists in silver, and a customer-facing copy would violate ADR-0089's owning-service rule and
  create a second erasure implementation (ADR-0210 D5).
- **Loan-less collateral inside lending-service.** Rejected: every `Collateral` row is a
  credit-risk input summed into LGD under four-eyes; a "not pledged" flag inside that table is a
  non-credit fact inside a money-path aggregate, and every lifestyle read (a customer browsing their
  watch collection) would hit a money-path service. D3 keeps the promotion path and the direction.
- **Holdings on party-service.** Rejected: party is identity (ADR-0072, 0179, 0284); a valued,
  documented, revaluable asset is a lifecycle of its own, and party-service's GDPR export and
  erasure semantics (ADR-0118) would silently expand to cover asset data.
- **Holdings as product-catalog products / account-service accounts.** Rejected: a declared
  holding is not a bank product and has no balance the bank is liable for; ADR-0257's kernel models
  what the bank *offers*, not what the customer *has elsewhere*.
- **No persistence — ask the customer each time.** Rejected as not a system; listed because "do
  nothing" is the honest baseline and it is the state today.

## Consequences

**Positive**
- The affluent proposition gets its one missing fact with one new service, and every later
  feature (custody, lombard, advice, collectibles UX, concierge) attaches to a defined aggregate
  instead of inventing one.
- Net worth is correct by construction: authoritative leaves come from their owners, declared
  leaves are labelled, and nothing is copied.
- lending-service's money-path boundary is untouched in direction and in four-eyes.

**Negative**
- Composition at the edge is a fan-out: one net-worth call is four upstream calls, and the
  first surface must render partial trees honestly (a leaf whose owner did not answer is shown as
  unavailable, never as zero — the ADR-0210 D9 lesson).
- A `CUSTOMER_DECLARED` valuation is what the customer typed. The surface must label it, and
  no downstream rule (segment, offer, credit) may treat it as verified; D7 limits segments to
  counts and totals for that reason.
- New service bootstrap cost: `governance.yaml`, release-please registration, threat-model
  triage (non-money-path, so no mandatory threat model under ADR-0030, but the ownership check in
  D5 is an authorization surface and gets a threat model regardless).

**Neutral**
- `openbank.wealth.events` is a new topic under ADR-0006/0260 (JSON Schema, BACKWARD_TRANSITIVE).
- analytics-sink may ingest the topic later so the operator 360 (ADR-0210) shows "has N declared
  holdings"; that is a consumer, not a change to this ADR.

### Gate

No new gate. What this ADR forbids is a dependency direction (`lending → wealth`) and a copy of
authoritative figures into wealth-service; both are structural and checked by the delivery check
below rather than by a new checker. The existing new-service gates (hexagonal layering,
`governance.yaml` cross-check per ADR-0196, release-registration consistency, entity column names,
outbox dispatch flag) apply unchanged. `delivery-status` starts as `planned`; it becomes `partial`
with a `followup:` once D1 ships and D2 does not.

### Delivery check

```bash
# D1: the service exists and is a released component
test -f openbank-wealth-service/version.txt && grep -q '"openbank-wealth-service"' release-please-config.json
# D2: the edge composes net worth; wealth-service persists no on-platform figure
git grep -l 'net-worth' openbank-customer-edge/src/main/resources/openapi.yaml
! git grep -lE 'balance-service|lending-service' openbank-wealth-service/src/main/resources/db/migration
# D3: dependency direction — lending never imports or calls wealth
! git grep -il 'wealth' openbank-lending-service/src/main
# D4: evidence is by document id only — no binary column in wealth-service migrations
! git grep -ilE 'bytea|blob' openbank-wealth-service/src/main/resources/db/migration
```

Expected: the first two commands print a path, the three negated ones print nothing.

## Compliance impact

- PCI DSS: not applicable — no cardholder data.
- DORA:    not applicable — wealth-service is not a critical or important function; it moves no
  money and the platform operates without it.
- GDPR:    applies — declared holdings are personal data about the customer's wealth; they follow
  the ADR-0118 lifecycle (anonymise on `PARTY_ERASED`, included in the Art. 15 export by
  party-service calling wealth-service, retention as contract data). Consent basis is the customer
  relationship; no profiling is introduced (D7 limits derived use to counts and totals).
- PSD2:    not applicable — no payment account, no payment initiation, no XS2A exposure.
- CNB:     not applicable — this ADR offers no investment service, custody, advice or credit;
  each of those is deferred to its own ADR with its own regulatory treatment (D6).

## References

- ADR-0199 / ADR-0210 — Customer 360: a read model that owns no facts is a query over silver.
- ADR-0089 — every customer-facing figure comes from the owning service.
- ADR-0284 — entity parties, `PartyMandate`, the owner graph as an ecosystem asset.
- ADR-0232 / ADR-0249 — delegation grants and the dispositor.
- ADR-0282 — Lípa: micro-segments (D6), household (D9).
- ADR-0028 — lending collateral, four-eyes (issue #621), IFRS 9 LGD.
- ADR-0161 / ADR-0162 — object storage and document-service (WORM, PAdES).
- ADR-0188 / ADR-0185 — crypto and treasury out of scope; rail-style port for a future network.
- ADR-0118 — GDPR lifecycle.
- ADR-0034 — OPA sidecar authorization.
