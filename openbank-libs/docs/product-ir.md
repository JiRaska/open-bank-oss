# Product IR v1 (ADR-0352, phase 1)

`com.openbank.libs.product.ir` in openbank-libs-domain turns a deposit product's
behaviour into data: a typed IR document, validated against
`product-ir/v1.schema.json`, hashed over its canonical form and executed by one
pure interpreter. No service evaluates a revision yet; signing, the loader and
the `openbank-products` content module arrive in phase 2.

## The pieces

- `ProductIrParser` parses and validates a document. It rejects unknown keys, a
  missing or duplicate `ruleId`, values beyond the run limits in `IrLimits`, a
  decimal more precise than its canonical scale, a `REJECT` outside
  `prePosting`, and a same-cycle schedule cycle.
- `ProductIrCanonical` writes the canonical form: `CanonicalJson` key order, no
  whitespace, fixed-scale decimal strings, null members dropped.
  `revisionHash` is the SHA-256 of those bytes.
- `ProductInterpreter.evaluate(revision, state, event, clock)` returns
  instructions (`Post`, `Reject`, `Schedule`, `Notify`, `Reprice`), each carrying
  `ruleId`, `revisionHash` and `irVersion`. It reads only its arguments, holds
  the 64-instruction cap, refuses an unbalanced `Post`, and returns only the
  `Reject` when a posting is rejected.
- Arithmetic goes through the `ProductArithmetic` port, implemented by
  `FinanceProductArithmetic` in openbank-libs-finance. Rounding is named by
  `RoundingPolicy`; the accrual reproduces interest-service's current
  expression bit-exactly.

## Testing

openbank-libs-testing holds the golden-scenario runner, `ProductArb` property
generators and `assertMoneyConserved`. Golden scenarios live under
`product-golden/` in that module's resources and must be listed in its
`index.txt`.

## Gates

| Gate | Mode | Fails on |
|---|---|---|
| `product-ir-determinism` | advisory until 2026-12-31 | a scenario missing from the index, an unsupported `irVersion`, a disabled determinism test |
| `product-ir-run-limits` | advisory until 2026-12-31 | a committed IR or scenario over the schema maxima |
| `product-ir-purity` | enforced | an I/O, clock, logging or metrics import under the package, or the detekt rule not active |
