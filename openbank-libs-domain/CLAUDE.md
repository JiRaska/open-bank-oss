# openbank-libs-domain — agent notes

Scoped notes for this module. The fleet-wide rules are in the root `CLAUDE.md`.

## Product IR (`com.openbank.libs.product`, ADR-0352)

- **The interpreter is pure.** No I/O, no ambient clock, no logging, no metrics under the package —
  `ProductIrPurityRule` (detekt) and the enforced gate `product-ir-purity` hold it. The only clock is
  the `ProductClock` argument; arithmetic goes through the `ProductArithmetic` port, implemented in
  `openbank-libs-finance` (`FinanceProductArithmetic`). Do not compute or round money in this package.
- **The hash is the canonical form, never the authored text.** `revisionHash` = SHA-256 of
  `ProductIrCanonical.canonicalJson`: `CanonicalJson` key order, no whitespace, fixed-scale decimal
  strings, null members dropped. Any change to that form changes every hash, so treat it as a
  breaking change. A new IR field needs a canonical-form entry, a schema entry
  (`src/main/resources/product-ir/v1.schema.json`) and a parser check; `ProductIrSchemaAgreementTest`
  keeps schema and parser in step.
- **Limits live in three places that must agree:** `IrLimits`, the schema's `maxItems`/description,
  and `check-product-ir-run-limits.py` (which reads the schema). The interpreter enforces the
  64-instruction cap at runtime.
- **Golden scenarios** live in `openbank-libs-testing/src/main/resources/product-golden/` and must be
  listed in its `index.txt` (gate `product-ir-determinism`). They move to `openbank-products` in
  phase 2. Expected instructions are written without `revisionHash`.
