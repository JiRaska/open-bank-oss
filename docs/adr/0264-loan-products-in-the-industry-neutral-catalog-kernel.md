---
date: 2026-08-16
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [product-catalog, lending, governance, api-contract]
summary: "Loan products join the ADR-0257/0258 catalog kernel via a banking.loan-v1 schema pack, no parallel versioning; lending-service will pin a resolved revision fail-closed, diverging from account-service's fail-open precedent."
followup: "#668 — phase C (money-path wiring) is built: lending resolves a published offering via ProductCatalogLoanProfileAdapter. Phase E (interest resolution) is built but INERT — CatalogInterestProfileSynchronizer is gated by INTEREST_CATALOG_SYNC_ENABLED, which defaults false and is set by no manifest, pending a catalog read grant for the openbank-services principal (#8481). Phases D (repayment allocation) and F (restructuring re-pin) remain unbuilt: zero allocation logic in lending src/main, and no restructuring path touches a catalog revision"
---

# ADR-0264 — Loan Products in the Industry-Neutral Catalog Kernel

## Context

Issue #668 asked for a "declarative product model" so a product's shape — attributes, pricing,
eligibility — is authored data instead of hand-written Kotlin per product type. ADR-0257
(industry-neutral catalog kernel), ADR-0258 (trusted JSON Schema profile) and ADR-0259
(AI-assisted authoring) shipped exactly that for deposits and a generic v2 model (PR #4501,
#4969, 2026-08-12/13): `ProductType`/`ProductSpecification`/`ProductOffering`/`ProductRevision`
in `openbank-product-catalog/.../domain/catalog/CatalogKernel.kt`, a closed JSON Schema profile
(`additionalProperties: false`, no scripts, local `$ref` only), maker-checker enforced in
`ProductRevision`'s own constructor (`checkerId != makerId`), and immutability once
`RevisionState.PUBLISHED`.

Loans got none of it. As of this ADR:
- `openbank-lending-service` has zero references to `productId`, `ProductCatalog`, or any
  revision concept anywhere in `src/main`. `LoanApplicationRequest.nominalAnnualRate` is
  caller-supplied free text; there is no catalog resolution at origination at all.
- `LendingService.recordRepayment` pays by installment id with no allocation-order constraint —
  the code's own comment says so.
- No loan schema pack exists under `catalog-packs/` (only `banking/deposit-v1`,
  `banking/legacy-product-v1`, `insurance/term-life-v1`/`v2`).
- `openbank-interest-service`'s `interest_rate_config` table and `InterestService` are scoped
  entirely to **accounts**: `accrue`/`accrueAll` walk `AccountDirectoryPort`, gate on
  `openbank.interest.accruable-account-types` (default `CURRENT,SAVINGS`), and the config row is
  keyed by `(account_id, product_id, currency)`. There is no loan concept anywhere in that
  service today — a future loan-interest phase cannot reuse this table's existing rows, only its
  general shape.

Two consuming services already call `product-catalog` with **zero contract-test coverage**:
`openbank-billing-service`'s `ProductCatalogRestClient.getProductFees` (`GET
/api/v1/products/{id}/fees`) and `openbank-account-service`'s `ProductCatalogClient.getById`
(`GET /api/v1/products/{id}`, the fail-open product-existence check behind
`ProductCatalogAdapter`, issue #668's own account-open validation). Neither has an entry in
`pacts/`. This is the exact gap class that caused incident #2269 (finrep-service calling a ledger
path that never existed, invisible to unit tests because nothing replayed the contract) — the
provider harness (`ProductCatalogPactProviderVerificationTest`, `@PactFolder("../pacts")`) already
exists and already runs on every PR of this module; it simply has nothing to verify for these two
call sites.

`#668`'s own text never mentions **reconstructability**: what happens to a loan's contractual
terms after the catalog revision it was priced from is superseded. Every real core banking
platform (Mambu, Vault, Temenos) treats "what were this loan's terms on the day it was booked" as
a permanent, catalog-change-proof fact — the grandfathering guarantee. That requirement is added
here because a loan without it cannot survive an audit: a regulator asking "what rate applied to
loan X in March" must get an answer that does not depend on what the catalog looks like today.

## Decision

We extend the existing ADR-0257 kernel with a new **`banking.loan-v1`** JSON Schema pack. We do
**not** build a second/parallel versioning mechanism, and we do **not** add a `LoanConfig` block
to the old v1 `Product.kt` — see Alternatives for why both are rejected outright.

**Full scope (this ADR governs all six phases; only A and B ship now):**

- **Phase A — contract-test debt.** Consumer pacts for `openbank-billing-service` →
  `GET /api/v1/products/{id}/fees` and `openbank-account-service` → `GET /api/v1/products/{id}`.
  Zero coupling to loans; closes the #2269-class gap independently of everything else in this ADR.
- **Phase B — `banking.loan-v1` schema pack + kernel authoring only.** Registers the pack in
  `CatalogPackSeeder`, proves draft → publish → pin through the real `/api/v2` REST surface the
  same way it is proven for insurance term-life. No `openbank-lending-service` or
  `openbank-interest-service` change of any kind.
- **Phase C — lending-service resolves and pins a revision at origination.** `productRevisionId`
  on `LoanApplicationRequest`/the `Loan` aggregate, a `ProductCatalogPort` adapter that is
  **fail-closed** (no disbursement without a resolved, valid, published revision), a
  `docs/threat-models/openbank-lending-service.md` update (this is new outbound trust-boundary
  code touching money-path decisions, per ADR-0030), and its own consumer pact. **This phase needs
  its own ADR** — it is a money-path wiring decision, not a catalog-model decision, and must ship
  alone, never bundled with D or E.
- **Phase D — repayment allocation order.** Depends on C: `LendingService.recordRepayment` walks
  the pinned revision's `allocationOrder` (see the loan pack shape below) instead of paying by
  installment id with no ordering constraint.
- **Phase E — interest-service catalog resolution for loans.** Once wired, loan interest must
  resolve the **loan's pinned revision**, never "whatever is currently published" — see
  Reconstructability below. Given the interest_rate_config scope finding above, this is new
  schema/wiring in `openbank-interest-service`, not a reuse of the existing account-keyed rows.
- **Phase F — does restructuring re-pin a revision or not.** Left as an explicit owner decision
  point for whoever implements it; not resolved by this ADR.

### The `banking.loan-v1` pack shape

Modeled on `deposit-v1.schema.json`'s conventions (JSON Schema 2020-12, `additionalProperties:
false` everywhere an object is declared, per ADR-0258's trusted profile):

- `productType`: closed enum (`INSTALLMENT_LOAN`, `MORTGAGE`, `AUTO_LOAN`, `STUDENT_LOAN`,
  `CREDIT_LINE`).
- `amortizationMethod`: `ANNUITY` | `EQUAL_PRINCIPAL` | `BULLET` — copied **verbatim** from
  `com.openbank.libs.lending.AmortizationMethod` (`openbank-libs-domain`), the enum
  `LendingService`/`Amortization` already use, so schema vocabulary and the lending-service enum
  can never drift into two different truths for the same three words. There is no shared Kotlin
  type to reference across the module boundary (JSON Schema has no import), so this is a
  documented, deliberate duplication — the alternative (a free-text field lending-service parses
  at origination) would let the catalog accept a fourth value the lending domain cannot represent.
- `allocationOrder`: a closed, **ordered** list drawn from `FEES` | `PENALTY` | `INTEREST` |
  `PRINCIPAL` (`uniqueItems: true`, 1–4 entries). This is the vocabulary Phase D's repayment
  allocation will walk in list order; it is a list rather than an enum because the whole point is
  that the order is authored data, not a hardcoded sequence.
- `tenorMonths`, `accrualBasis`, `gracePeriodDays`, `collateralRequired`,
  `minPrincipalAmount`/`maxPrincipalAmount`, and a bounded `fees` list (`code`, `name`,
  `amountKind: FIXED|PERCENT_OF_PRINCIPAL`, `waivable`, `waiveCondition` as a bounded free-text
  string in the ADR-0138 `WaiveConditionParser` grammar — the schema only bounds the string's
  shape, never evaluates it; evaluation stays `WaiverEvaluator`'s job).
- The base annual rate is **not** an attribute field. It is expressed as a kernel-native
  `PriceComponent` (`kind: RATE`, `code: BASE_RATE_ANNUAL`, `cadence: ANNUALLY`) on the revision,
  the same way `deposit-v1` products price via `RevisionContent.prices` rather than an
  attributes-JSON rate field. Money values already have one home in the kernel
  (`PriceComponent`); duplicating a rate inside `attributes` would create two places a reviewer
  has to check for the same number.

### Two-layer governance (unchanged from how ADR-0257 already works — restated for loans explicitly)

1. **Pack/schema SHAPE changes** (adding a field to `loan-v1`, changing the allocation-order
   vocabulary) go through normal code review — ADR-0258's classpath-trusted pattern. The pack is a
   file in this repo; changing it is a PR like any other.
2. **REVISION CONTENT changes** (an actual rate or fee for a specific loan offering) go through
   the existing runtime maker-checker already enforced in `ProductRevision`'s constructor
   (`checkerId != makerId`, required non-blank on `PUBLISHED`). No new approval mechanism is
   introduced for loans; they use the one the kernel already has.

`openbank-product-catalog` itself stays **out** of `rules.yaml: money_path_services` — correct
per ADR-0257's own boundary: it holds reference data and does not move money. What needs
money-path rigor is the **consuming side** — `openbank-lending-service` resolving and pinning a
revision at origination is new outbound trust-boundary code, which is why Phase C requires a
threat-model update and its own ADR, not a rubber stamp under this one.

### Reconstructability (the actual audit bar)

A `Loan` aggregate must store the **`productRevisionId`** it was originated against — not just a
`productId` — pinned **at origination**. A loan's contractual terms must be forever reconstructable
regardless of any later catalog change: this is the grandfathering guarantee real cores implement,
and it is a real gap in issue #668's own framing, not a restatement of something #668 already said.
When Phase E resolves loan interest, it must resolve **the loan's pinned revision**, never
"whichever revision is currently published" — resolving the live revision would silently reprice
every existing loan the moment the catalog changes, with no code path admitting it happened. This
ADR does not implement Phase E; it fixes the requirement so nobody implements Phase E without it.

### Fail-closed for lending, diverging from account-service's fail-open precedent

`openbank-account-service`'s existing `ProductCatalogAdapter` (issue #668) is deliberately
**fail-open**: an unreachable catalog must never block account opening, because product-catalog is
reference data on a non-money-path service. Phase C's future lending adapter must be the opposite:
**no disbursement without a resolved, valid, published revision.** This is a deliberate,
stated divergence — the two adapters answer different questions (`ProductLookupResult` for
account-open validation vs. "is it safe to move money against this rate today" for loan
origination) and must not be unified into one shared posture just because they call the same
provider. Copying account-service's fail-open shape onto lending's origination path would be the
silent-bypass failure mode ADR-0158 was written to prevent, applied to money instead of reference
data.

## Alternatives considered

- **Bolt a `LoanConfig` block onto the old v1 `Product.kt`**, next to `OverdraftConfig` and
  `TermDepositConfig`. Rejected: this is the exact anti-pattern ADR-0257 itself was written to
  replace — a new hand-written Kotlin data class per product type, no schema versioning, no
  maker-checker on content, and every future field addition is a code change instead of an
  authored one. Building it for loans after having just paid the migration cost off v1 for
  everything else would recreate the debt ADR-0257 exists to retire.
- **A separate, loan-specific versioning/approval table**, parallel to `ProductRevision`. Rejected:
  the kernel's `ProductRevision` (immutability once `PUBLISHED`, `checkerId != makerId` maker-checker,
  `SchemaRef`-pinned attributes) already provides exactly the properties a loan revision needs.
  Building a second table would mean two places implement "what does maker-checker mean for a
  catalog change," which is precisely the kind of drift ADR-0257's kernel exists to prevent, and
  it would leave loans unable to participate in the kernel's shared audit trail
  (`catalog_audit`/`catalog_outbox`) without a bespoke bridge.
- **Reuse `openbank-account-service`'s fail-open `ProductCatalogAdapter` posture for lending's
  future origination call.** Rejected explicitly (see Fail-closed section above): fail-open is
  correct for reference-data validation on account-open, wrong for a money-moving disbursement
  decision. Silently copying it would be reusing code that was right for a different question.
- **Seed permanent example `ProductOffering`/`ProductRevision` rows for `loan-v1` via a Flyway
  migration**, mirroring what was assumed (incorrectly) to be the existing convention for
  `deposit-v1`/`term-life`. Rejected on inspection: neither `deposit-v1` nor `term-life` has
  permanently seeded example offerings in production — `deposit-v1` has zero end-to-end coverage
  of its own schema file today (only a domain-level `SchemaRef` reference in
  `CatalogKernelTest.kt`), and `term-life`'s draft→publish→pin proof
  (`CatalogPlatformResourceTest`) creates its fixtures at test run time through the real `/api/v2`
  REST surface, not via migration-seeded rows. Introducing a migration-seeded example for loans
  alone would be new, inconsistent precedent; this ADR instead follows the `term-life` pattern
  (REST-driven, at-test-time fixtures) and, in doing so, gives `loan-v1` stronger coverage than
  `deposit-v1` currently has for its own schema file.

<!-- Required (enforced): both real alternatives above were genuinely evaluated against the
     current repository state, not invented for form. -->

## Consequences

**Positive**
- Loans get a declarative, versioned, maker-checker-governed product model without inventing a
  second mechanism — closing issue #668's actual scope item using infrastructure that already
  passed its own review (ADR-0257/0258/0259).
- `amortizationMethod` and the allocation-order vocabulary are pinned in this ADR against the real
  `AmortizationMethod` enum and the ADR-0138 fee-rule model, so a future Phase D cannot silently
  diverge from what the schema promises.
- Phase A closes a live, unrelated audit gap (two uncontracted `product-catalog` consumers) that
  predates this issue and was only found while researching it.
- The fail-closed decision for Phase C is made **now**, in the calm of a catalog-model ADR, instead
  of being implicitly decided by whichever engineer wires the first lending call.

**Negative**
- `amortizationMethod`'s vocabulary now has two independent copies (the Kotlin enum and the JSON
  Schema enum) with no compiler-enforced link between them — a change to one silently drifts from
  the other unless a reviewer catches it. No automated guard is added in this phase; a follow-up
  gate (schema-enum-vs-Kotlin-enum equality, evaluated at build time) is left for whoever
  implements Phase C, since that is the first phase where a drift would have a live blast radius.
- Phases C–F remain unbuilt after this PR; issue #668 stays open. A reader of this ADR alone might
  believe loans are catalog-integrated end-to-end — the delivery note below and the issue tracker
  are both needed to see the real state.

**Neutral**
- `openbank-product-catalog` gains a fourth trusted pack with no change to its trust boundary,
  REST surface, or `rules.yaml` money-path classification — `GenericCatalogResource` is already
  pack-agnostic, so no code path in this repo changes shape to add `loan-v1`, only data.

## Compliance impact

- PCI DSS: not applicable — no cardholder data touched by this catalog change.
- DORA: not applicable in this phase — no ICT third-party dependency or resilience posture
  changes; Phase C's fail-closed disbursement gate is an operational-resilience decision that
  belongs to Phase C's own ADR and threat model, not this one.
- GDPR: not applicable — loan product *specifications* carry no personal data; a specific
  customer's loan terms (Phase C onward) are covered by `openbank-lending-service`'s existing
  data-protection posture, unchanged by this ADR.
- PSD2: not applicable — this ADR governs product reference data, not payment initiation or
  account access.
- CNB: not applicable in this phase. Phase C (fail-closed origination pinning) and Phase E
  (interest resolved against the pinned revision, never the live one) are the parts of this
  design that speak to Czech consumer-credit rate-transparency expectations; they are unbuilt, so
  there is no live behavior yet to make a compliance claim about. This ADR records the requirement
  so the phase that builds it does not have to re-derive it.

## References

- ADR-0257 — industry-neutral product catalog kernel and standalone distribution
- ADR-0258 — trusted JSON Schema profile for industry packs
- ADR-0259 — AI-assisted product catalog authoring and offer intelligence
- ADR-0138 — configuration-driven product fee rule engine (waiver vocabulary reused by the
  `loan-v1` fee list)
- ADR-0158 — product-catalog caller authentication (the fail-open `ProductCatalogAdapter`
  precedent this ADR deliberately diverges from for Phase C)
- ADR-0030 — money-path service threat modeling (governs the required Phase C threat-model update)
- ADR-0048 — independent API-contract versioning axis
- ADR-0063 — git-pact consumer-driven contracts (the mechanism Phase A extends)
- ADR-0105 — canonical product identity derivation (`ProductIds.canonicalId`)
- Issue #668 — declarative product model (this ADR's parent issue; stays open after Phase A/B)
- Incident #2269 — the uncontracted-consumer failure class Phase A closes for two more call sites
- `openbank-product-catalog/src/main/kotlin/.../domain/catalog/CatalogKernel.kt`
- `openbank-libs-domain/src/main/kotlin/com/openbank/libs/lending/Amortization.kt`
