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

## Behavioural diff and replay (phase 4)

`ProductBehaviourDiff` (`openbank-libs-domain`, package `com.openbank.libs.product.replay`)
answers "this change moves X accounts by Y" before anyone approves a revision. It takes two
revisions, a portfolio and a date range:

```kotlin
ProductBehaviourDiff(ProductInterpreter(FinanceProductArithmetic))
    .diff(revA, revB, portfolio, ReplayRange(from, to, zone), DiffOptions(materialityThreshold))
    .canonicalJson
```

`ProductReplay` runs every account under one revision, day by day, through the pure interpreter.
On an account's first replayed day it evaluates `onOpen`, whose `Schedule` instructions register
its schedules. Every day after that it runs the customer postings (`prePosting`, the posting
itself if not rejected, then `postPosting`) and then the schedules that fall due. `MONTHLY` runs
on the last day of the month, `QUARTERLY` on a quarter's last day, `ANNUALLY` on 31 December and
`AT_MATURITY` once. `ProductStateTransition` moves the state between evaluations, exactly as in
the golden runner. An evaluation that rejects counts its `Reject` only, never a `Post` or
`Schedule` from the same batch. History older than the longest predicate window either revision reads is
dropped, because no predicate can see it. A run is bounded by `ReplayLimits.MAX_DAYS` and the
interpreter's per-evaluation cap.

It lives in the domain module, next to the interpreter, rather than in openbank-libs-testing. The
Studio approval panel runs it inside a service, and the benchmark measures it. Neither may depend on
the test-fixture library (ADR-0352 §5). It does no I/O. The only inputs are its arguments.

The output is canonical JSON with keys sorted and no whitespace. Every amount is a fixed-scale
decimal string at scale 12. The same input gives the same bytes, and a test hashes two runs to
prove it. The schema is `product-ir/behaviour-diff.v1.schema.json` (ADR-0258 trusted profile:
closed objects, closed enums, local `$ref` only):

| Member | Content |
|---|---|
| `schema` | `openbank.product-behaviour-diff.v1` |
| `revisionA`, `revisionB` | `productCode`, `revisionHash`, `irVersion` |
| `range`, `portfolio` | the calendar, the account count and the generator `seed` (a string, or null for recorded history) |
| `valid` | both runs conserved money. `false` means an interpreter defect, and `obp` and Studio must treat the whole diff as an error |
| `identical` | `valid`, and no account or aggregate bucket differs. Never true when `valid` is false |
| `aggregate.posted` | per `ruleId` × accounting class: debit, credit and net under each revision, and `deltaNet` |
| `aggregate.instructionCounts` | per `ruleId` × instruction kind: counts under each revision |
| `aggregate.outcome` | net customer-deposit movement the product caused (interest, fees, penalties) |
| `accounts` | the same rows per changed account, plus outcome, final balance and rejected postings |
| `signChanged`, `thresholdCrossed` | accounts whose outcome changed sign, or moved by at least `materialityThreshold` |
| `conservation.a`, `conservation.b` | debit and credit totals per currency of every `Post`, and whether they balance |

Rows list only buckets whose two sides differ, so identical revisions give empty lists.

openbank-libs-testing's `SyntheticPortfolio.generate(seed, size, shape)` builds a reproducible
portfolio for it. The balances are skewed like a retail book. It adds accounts a cent either side
of each tier or threshold, month-end credits, card spending, withdrawals, savers that never
withdraw, and accounts that open inside the range, including on 29 February.

openbank-simulation's `ProductReplayScenario` replays the signed reference products for several
years. It books every instruction into an in-memory double-entry shadow ledger and checks that
the trial balance is zero at every day end. `ProductBehaviourDiffBench` measures diff throughput.
It was measured on 2026-10-29 on a developer laptop that was running other builds at the time. One
op is 1 000 accounts × one year under two revisions, which is about 732 000 `evaluate` calls. Its
three iterations took 3.3, 3.4 and 4.2 s, a mean of 3.6 s and about 5 µs per evaluation. So
10 000 accounts × 1 year takes about 36 s. In the same run, `ProductInterpreterBench` measured a
p99 of 0.048 ms per golden-set op, inside the 2 ms per-`evaluate` budget. The op allocates about
5.4 GB, recorded in `alloc-baseline.json`.

### Replay findings

The first replay of the reference products found four behaviours. Each was decided as follows:

| Finding | Decision |
|---|---|
| `term-deposit` had no balance rule in `prePosting`, so a withdrawal larger than the balance overdrew it | Defect, fixed in `openbank-products`: a `PROJECTED_BALANCE LT 0.00` reject (`INSUFFICIENT_FUNDS`), ordered before the early-withdrawal penalty so a refused withdrawal is never charged one |
| The savings monthly fee can take a dormant account below zero | Accepted in IR v1. `prePosting` guards customer postings; a scheduled `CHARGE_FEE` is a contractual charge and posts in full. Bounding it needs a new IR construct, and the v1 schema is immutable (ADR-0352 §1); tracked as #14207, folded into the IR v2 issue #14210 |
| The `PROJECTED_BALANCE` reject leaves the early-withdrawal penalty out (`balance + signed`), so a full withdrawal before maturity passed it and the 1 % penalty overdrew the deposit to -penalty | v1 interpreter defect fix, not a product change: published v1 revisions keep their `revisionHash`, and outcomes change only where the old outcome broke the no-negative-deposit invariant, i.e. `balance - amount - penalty < 0` on a `RATE` penalty. There `EARLY_WITHDRAWAL_PENALTY` of kind `RATE` now rejects with `INSUFFICIENT_FUNDS` when `balance - amount - penalty < 0`. Capping the penalty at the balance left was rejected, because a full withdrawal would then pay no penalty at all. IR v1 cannot express balance-minus-penalty, so the check lives in the action, not in a product rule. `FORFEITED_INTEREST_FRACTION` debits accrued interest, not the deposit, and needs no check. `EarlyWithdrawalPenaltyFixInvarianceTest` replays every golden scenario against outputs captured from the pre-fix interpreter: every step outside the condition is byte-identical |
| Capitalisation rounds accrued interest HALF_UP to the cent, so `accruedInterest` can carry a sub-cent negative remainder into the next period | Unchanged in this IR version. Capitalisation rounding is part of every published revision's behaviour, so changing it (to DOWN, or to sweeping the residue at maturity or close) is an IR-version decision, not a shared-arithmetic edit (#14210). Each HALF_UP capitalisation leaves `|r| <= 0.005`, carried rather than accumulated, and negative when the cent was rounded up; an `AT_MATURITY` revision keeps its last `r` on `ACCRUED_INTEREST_PAYABLE`. The replay reports this as the named limitation `v1-capitalisation-residue` and asserts the bound |

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
