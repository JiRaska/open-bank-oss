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

## Authoring surfaces and the toolkit (phase 5a)

A product reaches the catalog through one of three authoring surfaces: the `obp` CLI
(openbank-products), the MCP product tools in openbank-agent-service (`product_lint`,
`product_compile`, `product_simulate`, `product_explain`, `product_propose`), and the Studio form
(phase 5b: the IR editor in Product Studio, which validates through the catalog's
`POST /api/v2/product-ir/lint` and saves a DRAFT revision). The first two share one implementation, `ProductToolkit`
(`com.openbank.libs.product.toolkit`): lint, compile, simulate, explain and the DRAFT-revision
proposal plan. Every function is pure and returns a tree written with `CanonicalJson`, so the same
document yields the same bytes on every surface.

The toolkit deliberately lives here and not in openbank-products: that module depends on
openbank-libs-testing, which must never reach a service's production classpath. Simulation takes
the `ProductArithmetic` port as an argument; both surfaces pass `FinanceProductArithmetic`.

Nothing in the toolkit signs, approves or publishes. `obp sign` is CLI-only and accepts only an
offline test key; there is no MCP sign or publish tool at all.

## Approval backtest and `obp diff` (phase 5b)

The repricing backtest of ADR-0352 §11.4 has one engine, one portfolio reader and two surfaces:

- `ReplayPortfolioJson` (`com.openbank.libs.product.replay`) reads a portfolio document: the golden
  scenario's `state` + `steps` grammar of `openbank-products/portfolios/<name>.json`, plain dated
  `postings`, or both. A step with a `posting` is a customer movement on its calendar day in the
  portfolio `zone`; scheduled steps are skipped, because the replay runs the schedules each revision
  registers in `onOpen`. Keys are closed and amounts are plain decimals (at most 15 integer digits,
  12 decimals; no exponent form), so one number cannot sidestep the replay's bounds.
- `obp diff <revA> <revB> --behavioural --portfolio <file> [--from D] [--to D] [--threshold X]`
  prints the `ProductBehaviourDiff` document (schema `openbank.product-behaviour-diff.v1`) unchanged.
  The range defaults to the earliest `openedOn` through the last fixture day; a resolved range that is
  empty or longer than `ReplayLimits.MAX_DAYS` exits 64, and a replay that does not conserve money
  exits 1.
- The catalog's `POST /api/v2/offerings/{id}/revisions/{revisionId}/behaviour-diff` runs the same
  engine on the same reader (base: the revision published now, unless `baseRevisionId` names one) and
  Studio's approval panel renders that document — per account, per rule, instruction counts, and the
  accounts that changed sign or crossed the materiality threshold, and per account which rule moved it.
  It is bounded (500 accounts, 1000 events per account, 200 000 account-days). Concurrency is capped
  by one non-blocking semaphore per catalog pod — `openbank.catalog.behaviour-diff.max-concurrent`,
  default 2 — shared by every caller (it is global, not per principal): a backtest that finds no free
  slot gets 429 with `Retry-After` at once rather than queueing behind CPU-bound work.
- `BehaviourDiffInputs` resolves the window (default: earliest `openedOn` through the last portfolio
  day; empty or longer than `ReplayLimits.MAX_DAYS` is refused) and the threshold (the bounded
  plain-decimal grammar above, non-negative) for both surfaces, so the CLI and the catalog accept and
  refuse the same inputs and compute the same diff from them.

**Approval needs the approver's own backtest over the server's golden portfolio (ADR-0352 §7).**
`openbank-products/portfolios/approval.json` names, per `productCode` and then per currency, the
golden portfolio a product's approval backtest runs over; product-catalog packages that directory
at build time. A backtest request WITHOUT a portfolio runs over that golden portfolio, and only such
a run — valid, by a verified JWT principal — is recorded as evidence (`catalog_revision_backtests`,
immutable: no UPDATE, DELETE or TRUNCATE). The row binds the principal's JWT issuer + subject and the
revision's approval digest (content + row version), and keeps the portfolio's name and sha-256,
`identical`, `changedAccounts` and the diff's sha-256 so it can be re-checked later. A request WITH
a portfolio is an exploration: it runs and renders, but it is never evidence, because the approver
chose what to look at. `publish-ir` refuses with 409 unless the checker has such evidence for the
revision as it stands, matched by issuer + subject (never by display name); a role approval of an
IR-bearing revision needs the same from the approving subject. Editing the draft invalidates earlier
evidence. Running a backtest is its own action, `catalog.backtest` (`rest.rego`: a human
operator/admin or revision-approval authority, never a machine), not a catalog read. The four-eyes
and role-approval rules are unchanged.

**Breaking for `obp diff` consumers.** Until phase 5b `obp diff --behavioural` printed a CLI-local
report. It now prints the schema-v1 document above. Two members are gone — `changedRules` and
`rules[]` (with `rules[].accounts[].customerDepositDelta`) — and one CHANGED TYPE: `accounts` was the
account COUNT (a number) and is now the ARRAY of changed-account rows. Read the count from
`portfolio.accounts` (or `changedAccounts` for the changed ones), per-rule deltas from
`aggregate.posted[].deltaNet`, and per-account movement from `accounts[].outcome`.

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
| `product-ir-authoring-parity` | advisory until 2026-12-31 | a committed `ir/<name>.json` with no DSL product, canonical DSL output that differs from the committed file, or the CLI and MCP surfaces compiling one fixture to different bytes (and the Studio lint returning anything but the committed bytes) |
| `product-ir-purity` | enforced | an I/O, clock, logging or metrics import under the package, or the detekt rule not active |
