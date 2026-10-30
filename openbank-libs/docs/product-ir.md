# Product IR v1 (ADR-0352, phase 1)

`com.openbank.libs.product.ir` in openbank-libs-domain turns a deposit product's
behaviour into data: a typed IR document, validated against
`product-ir/v1.schema.json`, hashed over its canonical form and executed by one
pure interpreter. No service evaluates a revision yet; phase 2 adds signing and
the pinned-revision loader (below) and the `openbank-products` content module.

## The pieces

- `ProductIrParser` parses and validates a document. It rejects unknown keys, a
  missing or duplicate `ruleId`, values beyond the run limits in `IrLimits`, a
  decimal more precise than its canonical scale, a `REJECT` outside
  `prePosting`, and a same-cycle schedule cycle.
- `IrJson` is the strict JSON reader beneath the parser. A `\u` escape must be
  followed by exactly four hex digits (`[0-9a-fA-F]`); a signed (`\u+041`,
  `\u-001`) or non-hex (`\u00G1`) escape is rejected with `invalid \u escape`
  rather than decoded.
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

## Signing and loading (phase 2)

`com.openbank.libs.productrevision` in openbank-libs-runtime signs a published
revision and lets a consumer load it only after verification.

- `RevisionIdentity(productCode, revisionId, revisionNumber, irVersion,
  revisionHash)` is what a signature binds and what a consumer pins. The signed
  bytes are `signingInput()`: canonical JSON of those five fields plus the
  domain tag `openbank.product-revision-signature.v1`. A bare hash is not
  enough: two revisions with the same behaviour share a hash, and a hash says
  nothing about which revision row a contract pinned.
- `IrSignature(keyId, algorithm, signature)` is a detached signature over that
  envelope, not over the JSON bytes. `algorithm` names the
  scheme so a verifier refuses what it cannot interpret; `keyId` lets rotation
  keep older revisions verifiable.
- `IrSignerPort` signs; `IrSignatureVerifierPort` verifies. Only the catalog's
  publish of an IR-bearing revision holds a signer wired to a real key.
  Consumers get a verify-only port.
- `OpenBaoTransitIrSigner` implements both over OpenBao Transit `sign`/`verify`
  on an asymmetric key (`ed25519` or `ecdsa-p256`). The publish identity gets
  `update` on the sign path, consumers only on the verify path, and nobody gets
  `create` on keys. A 403 refreshes the token and retries once, then fails
  closed. `verify` returns `false` for a wrong signature but throws
  `IrSigningException` when OpenBao is unavailable, so "could not check" never
  reads as "checked and failed". It is a plain class, not a CDI bean; the
  owning service produces it.
- `HmacIrSigner` (HMAC-SHA256) is for development and tests only. A shared
  secret cannot show who signed, so a deployment must use the Transit signer.
- `PinnedRevisionLoader.load(pin)` loads a revision by its full
  `RevisionIdentity` from a local `SignedRevisionSource` the consumer owns
  (never a live catalog call). It requires the copy's identity, and the parsed
  IR's `productCode` and `irVersion`, to equal the pin, checks that the
  canonical hash equals the pin, and verifies the signature against the pin's
  envelope before returning. Any failure throws
  `PinnedRevisionUnavailableException`, and the consumer must not post. Verified
  revisions go into an immutable cache keyed by pin.
- Each load increments `openbank_product_revision_load_total` with an `outcome`
  tag: `cache_hit`, `loaded`, `missing`, `invalid_ir`, `hash_mismatch`,
  `identity_mismatch`, `signature_invalid` or `verifier_unavailable`. A failed verification has its
  own value and never shares one with success.

Governance for the publish step: `catalog.publish-ir` is its own four-eyes
action, allowed by `rest.rego` only to a human with `ROLE_OPERATOR` or
`ROLE_ADMIN` who is not a machine principal. Maker and checker must differ,
which product-catalog enforces separately.

## Shadow comparison (phase 3)

`ProductShadowRecorder` (`openbank-libs-runtime`, package `productrevision`) is the shared
metrics surface for a service that evaluates the IR in **shadow mode**. That means
asynchronously, off the posting path. An input reaches the shadow in one of two ways:

- **Outbox row (billing).** The service writes a shadow-input row in the same transaction as
  the authoritative decision, together with the frozen facts the IR needs.
- **After-commit row pickup (interest).** The service writes nothing new. A scheduler later
  enqueues the committed decision rows themselves (accruals, capitalisations) that have no queue
  row yet.

Either way, the shadow never runs inside the posting transaction, and its result never changes
what the legacy code decided.

| Metric | Meaning |
|---|---|
| `openbank_product_shadow_inputs_total` | inputs queued, counted after the authoritative transaction commits |
| `openbank_product_shadow_compared_total` | inputs where the IR result was compared with the authoritative one |
| `openbank_product_shadow_diverged_total` | comparisons that disagreed |
| `openbank_product_shadow_not_comparable_total{reason}` | inputs that could not be compared. The `reason` is one value of the closed `ShadowNotComparable` set: no IR, revision unavailable, verification disabled, verification failed, verifier unavailable, rule unmapped, required input unknown, or IR evaluation failed |

Inputs arriving while `compared` stays flat is the "shadow compared nothing" state. An
all-not-comparable shadow reads as quiet, not as broken, so it gets its own alert
(`ProductShadowComparedNothing`), alongside `ProductShadowStalled` and
`ProductShadowDiverged`. billing-service is the first consumer.

## Testing

openbank-libs-testing holds the golden-scenario runner, `ProductArb` property
generators and `assertMoneyConserved`. Golden scenarios live under
`product-golden/` in that module's resources and must be listed in its
`index.txt`.

## Gates

| Gate | Mode | Fails on |
|---|---|---|
| `product-ir-determinism` | enforced | a scenario missing from the index, an unsupported `irVersion`, a disabled determinism test |
| `product-ir-run-limits` | enforced | a committed IR or scenario over the schema maxima |
| `product-ir-authoring-parity` | advisory until 2027-03-31 | a committed `ir/<name>.json` with no DSL product, or canonical DSL output that differs from the committed file |
| `product-ir-purity` | enforced | an I/O, clock, logging or metrics import under the package, or the detekt rule not active |
