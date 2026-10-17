---
date: 2026-10-17
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [product-catalog, fees-billing, ledger, regulatory-reporting]
summary: "Every product-bearing contract pins a published catalog revision; its prices, accounting class and regulatory class come only from that revision, the ledger maps accounting class to GL, and no service keeps its own copy."
followup: "#668 — adoption is one service per PR (interest, billing, pension-fund, treasury, lending phase D, account); #13167 tracks day-end participation"
---

# ADR-0341 — Pinned product revision is the single source of price, accounting class and regulatory class

## Context

ADR-0257 built an industry-neutral catalog kernel (specification, offering, immutable published
revision, maker-checker) and said a contract *may* pin a revision. ADR-0264 decided that loans
*must* pin one, fail-closed. Neither decides what every other product-bearing service reads its
terms from, where the GL account for a product's postings comes from, or which vocabulary the
regulatory mappers classify products by. ADR-0257 already decides how the v1 product API is
retired (ADR-0145 window plus zero live traffic), so that is not re-decided here.

Measured on `origin/main` 2a0d1d75fc (2026-10-17, issue #668):

- **Price comes from five places.** Billing reads the v1 `/api/v1/products/{id}/fees`.
  Interest uses its own `InterestRateConfig`; the v2 sync exists but `INTEREST_CATALOG_SYNC_ENABLED`
  defaults false and no manifest sets it. Lending takes a caller-supplied `nominalAnnualRate`
  unless `catalogOfferingId` is sent, and no caller sends it. Pension-fund accrues
  `managementFeeRate` in NAV while the catalog pension pack's `feeSchedule` is display-only, and
  the annuity simulator hard-codes two fee rates. Treasury's rates are dealer-entered.
- **No contract pins a revision outside lending and interest.** `product_revision_id` or
  `revision_id` appears in the migrations of interest and lending only; billing, pension,
  pension-fund, treasury and account have none.
- **GL mapping is hard-coded per service.** Billing, interest, `LendingGlChart` and
  `TreasuryChart` each carry their own literal chart codes. Nothing ties them to the product.
  The consequence was a real defect: finrep's GL allowlist knew none of treasury's codes, so
  every booked treasury deal produced `UNCLASSIFIED_GL_ACCOUNT` and blocked F 01/F 02 (#13146).
- **Four product-type vocabularies.** Catalog `ProductType`, libs `CreditProductKind`, AnaCredit
  `InstrumentType` (entered by hand, default `OVERDRAFT`, #9890) and treasury `ProductType`.
- **Zero references by id.** No fee-schedule, interest-plan or rate-config id exists anywhere;
  every term is embedded or free text.

So "what were this contract's terms, how was it booked, how was it reported" has no single
answer, and the same product can be priced, booked and classified three different ways.

## Decision

We will make the pinned published revision the only source of a contract's commercial,
accounting and regulatory terms.

1. **Pin at opening.** Every product-bearing contract (account, loan, fee-bearing arrangement,
   pension contract, fund membership, treasury deal) records the `productRevisionId` of the
   published revision it was opened under. Opening without a resolvable published revision fails
   closed, as ADR-0264 decided for loans; account-service's fail-open existence check (ADR-0158)
   becomes fail-closed for products with price components.
2. **Terms from the pinned revision only.** Fees, rates, tiers and management fees are read from
   the pinned revision's price components, never from "the currently published" revision and
   never from a service-local constant. A service may cache a revision's terms locally, keyed by
   revision id; such a cache is never edited by its own API. Billing moves from v1 fees to v2 price
   components. A change of terms is a new revision plus an explicit re-pin, which is an auditable
   contract event, not an edit.
3. **Accounting class, not GL codes, in the catalog.** A revision carries a closed
   `accountingClass` (for example `CUSTOMER_DEPOSIT_DEMAND`, `LOAN_RETAIL_INSTALLMENT`,
   `INTERBANK_PLACEMENT`, `FEE_INCOME_ACCOUNT`). The ledger owns the chart of accounts and the
   `accountingClass → GL account` map. Product services post with the accounting class; the ledger
   resolves the account. Product services stop carrying literal GL codes.
4. **One regulatory classification.** A revision carries a closed `regulatoryClass` drawn from the
   reporting taxonomy (FINREP instrument and counterparty class, AnaCredit instrument type).
   Finrep, AnaCredit and tax reporting read it from the pinned revision recorded on the posting or
   exposure, instead of keeping their own enums. Treasury's own-dealing instruments are catalog
   offerings too: their revision carries accounting and regulatory class and limits, while the
   deal price stays dealer-entered.
5. **Adoption is one service per change**, in this order: interest (turn sync on, needs the catalog
   read grant from #8481), billing, pension-fund, treasury, lending phase D, account. Each step
   updates its threat model (ADR-0030) and adds a consumer pact against the catalog provider.

## Alternatives considered

- **Keep the catalog as reference data and let each service own its pricing.** This is the state
  measured above. It costs nothing to migrate, but terms, bookings and reports already disagree,
  and one such disagreement (#13146) blocked a regulatory return. Rejected.
- **Copy a terms snapshot into each contract at opening instead of pinning a revision id.**
  Reproducible and offline, but each service would invent its own snapshot shape, and nothing would
  link two contracts opened under the same terms. Rejected; a local cache keyed by revision id
  (point 2) keeps the offline benefit without losing provenance.
- **Put GL account codes directly into the revision.** One fewer indirection, but the catalog would
  own the bank's chart of accounts, every chart change would need a catalog revision for every
  product, and the standalone catalog distribution (ADR-0257) would carry one bank's GL codes.
  Rejected in favour of an accounting class the ledger maps.
- **A shared libs enum for product type instead of a revision attribute.** Simpler to adopt, but it
  would be a fifth vocabulary rather than one, and a regulatory reclassification would need a fleet
  rebuild instead of a reviewed revision. Rejected.

## Consequences

**Positive**
- A contract's price, booking account and regulatory class are reproducible from one immutable,
  maker-checker-approved record.
- A chart-of-accounts change is one ledger change; a new product cannot reach the ledger with a GL
  code finrep has never heard of.
- AnaCredit and finrep stop guessing product classes.

**Negative**
- Every product-bearing service gains a hard dependency on catalog availability at opening (cached
  terms keep running contracts unaffected).
- Six money-path services change, each needing a threat-model update and a pact; this is a
  multi-quarter adoption.
- Existing contracts need a backfill to a revision; contracts whose terms match no published
  revision need a grandfathering revision created under maker-checker.

**Neutral**
- Treasury deal prices stay dealer-entered; only their classification moves.
- Tax treatment stays a party and event attribute (ADR-0339), not a revision property.

### Gate

A new checker `.github/scripts/check-product-revision-pinned.py`, gate `product-revision-pinned`,
reads a new `rules.yaml: product_bearing_services` list and fails when a listed service has no
`product_revision_id` column on its contract table, or when its `src/main` contains a literal
four-digit GL code outside the ledger chart. It starts advisory with a `target_enforce_date`
(ADR-0144) and the list grows one service per adoption PR.

### Delivery check

```bash
for s in billing interest lending pension pension-fund treasury account; do
  git grep -l product_revision_id -- "openbank-$s*/src/main/resources/db/migration" | head -1
done                                            # expect 7 paths, one per service
git grep -n 'INTEREST_CATALOG_SYNC_ENABLED:false' -- openbank-interest-service   # expect nothing
yq '.gates[] | select(.id == "product-revision-pinned") | .mode' .github/gates/gates.yaml  # expect enforced
```

## Compliance impact

- PCI DSS: not applicable — no cardholder data is involved in product terms.
- DORA: not applicable — no change to ICT risk, incident or third-party arrangements.
- GDPR: not applicable — product revisions carry no personal data.
- PSD2: not applicable — no payment-initiation or access-to-account surface changes.
- CNB: regulatory returns (FINREP, AnaCredit) classify from the revision's regulatory class instead
  of per-service mappings; the change in derivation is evidenced by the delivery check above.

## References

- ADR-0105, ADR-0138, ADR-0143, ADR-0145, ADR-0158, ADR-0257, ADR-0258, ADR-0264, ADR-0339
- #668 (measured state 2026-10-17), #8481, #9890, #13146, #13167
