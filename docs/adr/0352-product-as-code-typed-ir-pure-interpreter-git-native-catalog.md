---
date: 2026-10-22
decision-status: proposed
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [product-catalog, interest, fees-billing, libs]
summary: "Product behaviour becomes a typed, total, signed JSON IR authored in git and executed by one pure interpreter shared by production, simulation, CI and Studio; publishing needs both PR review and Studio role approval."
followup: "#668 — phase 1 (IR, interpreter, finance primitives, golden runner, gates) built; phases 2-6 (signing, loader, openbank-products, consumers, simulation, Studio) unbuilt"
---

# ADR-0352 — Product as Code: typed IR, pure interpreter, git-native product catalog

## Context

ADR-0257 built an industry-neutral catalog kernel (specification, offering, immutable published
revision, maker-checker, `contentHash`), ADR-0258 closed its content to a trusted JSON Schema
profile with no scripts and local `$ref` only, ADR-0341 decided that the pinned revision is the only
source of a contract's price and classes, and ADR-0345 moved approval roles into `pack.json`. All
four decide what a product *says*. None decides what a product *does*: today a revision carries
attributes and price components, and every consumer re-implements the behaviour around them in
Kotlin. This ADR is needed because the behaviour — when interest accrues and capitalises, which day
count applies, when a bonus rate is earned, when a fee is charged or waived, what a term deposit does
at maturity or on early withdrawal — has no shared, executable, verifiable form.

Measured on `origin/main` 3a697f5c1f (2026-10-22):

- **Interest arithmetic lives in the service, not in a finance primitive.**
  `openbank-interest-service/.../application/usecase/InterestService.kt:167-168` computes
  `dailyRate = config.dayCount.dailyRate(config.annualRate, request.accrualDate)` and then
  `accruedAmount = request.balance.multiply(dailyRate).setScale(6, RoundingMode.HALF_UP)` — the
  multiplication and the scale-6 rounding are service code. `dailyRate` (`InterestModels.kt:54-56`)
  delegates the year fraction to `com.openbank.libs.domain.calendar.DayCount` at scale 10 HALF_UP,
  and capitalisation rounds through `RoundingPolicy.LEDGER_POSTING` (ADR-0318). The service imports
  **nothing** from `com.openbank.libs.finance` (`git grep 'libs.finance' -- openbank-interest-service/src/main`
  is empty), although `openbank-libs-finance/.../cashflow/Accrual.kt` exists. So the fleet has three
  places that know how interest is computed — the kernel calendar, the finance module and the
  service — and the one that posts money is the service.
- **Interest resolves its rate from its own `InterestRateConfig`.** The catalog sync
  (`CatalogInterestSnapshotScheduler`) caches published revisions; `interest.catalog-sync.enabled`
  defaults to `true` in `application.yaml:203` (the ADR-0264 follow-up text still says `false` — the
  default was flipped by ADR-0341's adoption). It copies a rate; it executes nothing.
- **`bonusRateCondition` is still free text** (`Product.kt:102`, seeded
  `"No withdrawals in calendar month"`, `ProductSeed.kt:131`). ADR-0138 phase 3 — extend the waiver
  grammar to temporal/event predicates — is the only open phase of that ADR and has been open since
  2026-07-17. The fee-waiver grammar itself (`openbank-libs-domain/.../product/WaiveCondition.kt`,
  `WaiverEvaluator.kt`) is a closed attribute/operator comparison with no event history, no time and
  no cadence.
- **Billing evaluates the shared waiver over REST-read terms.** `FeeAssessmentService.kt` reads the
  pinned revision's fees through `ProductRevisionPort` and skips the whole assessment when the catalog
  or pin store is unreachable (`assess`, lines 48-70). The catalog is therefore in billing's path on
  every cycle.
- **Lending has schedules but no allocation.** `LendingService.kt:929` calls
  `com.openbank.libs.lending.Amortization.schedule`; ADR-0264 phase D (repayment allocation over the
  revision's `allocationOrder`) is unbuilt and `recordRepayment` pays by installment id (issue #668,
  2026-10-06 audit).
- **Account opening fails open** on catalog faults (`ProductCatalogAdapter.kt:34`, ADR-0158), which
  ADR-0341 already decided to reverse for priced products.
- **The simulation harness never loads a product.** `openbank-simulation` has
  `InterestAccrualScenario`, `FeeBillingScenario` and `LendingEclCalibrationScenario`, and
  `git grep -n 'catalog\|ProductRevision' -- openbank-simulation/src/main` returns nothing: the
  scenarios drive hand-coded parameters, so no simulation today can say what a *revision* would do.
- **Studio compares structure, not behaviour.** `product-studio/page.tsx:175` renders
  `diffCatalogDocuments(liveDocument, parsedDraft)` — a path-level structural diff — and
  `simulateBundleImpact` over catalog fixtures (ADR-0259). A reviewer approving a revision sees which
  JSON paths changed, not which customers' interest or fees change by how much.
- **AI authoring is proposal-only and "no executable rules"** (ADR-0259; `CatalogReviewResource`).
  That boundary was drawn when the only executable form would have been a script.

The strategic frame is in `docs/strategy/02-competitor-analysis.md`: the commercial cores this
platform is measured against express product behaviour as vendor-language smart contracts. A
general-purpose language is expressive and unverifiable: a reviewer cannot prove a contract
terminates, balances or behaves identically on replay. The platform's existing bias — ADR-0138
rejected MVEL/CEL/scripting, ADR-0257's `EligibilityRule` forbids executable scripts, ADR-0213 made
credit policy versioned decision tables over a pure evaluator, ADR-0347 made fraud thresholds data
while "rule logic stays code" — points the other way: a **closed, typed, total** representation that
a machine can check and a pure function can execute. This ADR generalises that bias from one
predicate family to the whole behaviour of a deposit product, and names the thing the platform can
be better at than a scripting core: every revision is diffable, simulatable and replayable before it
is published, and nothing it says can be a side effect.

## Decision

We will make a product's behaviour code: a typed intermediate representation (IR) authored in git,
compiled from a Kotlin DSL, hashed and signed on publication, executed by one pure interpreter that
is the same code path in production, simulation, CI and Studio, and published only when both an
engineering review and a business approval have happened. Numbered sections follow.

### 1. IR v1 — scope, format, versioning

The IR is JSON under the ADR-0258 trusted profile (`additionalProperties: false`, closed enums,
local `$ref` only, no scripts), carried as the content of a catalog revision next to the existing
attributes and price components, and versioned by a top-level `irVersion` (integer). The schema for
each `irVersion` is immutable once published, exactly as a pack schema version is (ADR-0345).

Closed enums, fixed in v1: `rateType ∈ {FIXED, TIERED}`,
`dayCount ∈ {ACT_365_FIXED, ACT_360, ACT_ACT, THIRTY_360_BOND_BASIS}` (the kernel `DayCount` set),
`cadence ∈ {DAILY, MONTHLY, QUARTERLY, ANNUALLY, AT_MATURITY, ON_EVENT}` (a superset of the kernel's
`PriceCadence` that an adapter maps one-to-one; the kernel enum is not widened by this ADR).

v1 scope is **deposit products** only:

- interest: fixed and tiered rates, day count, accrual and capitalisation cadence;
- **bonus predicate over event history**: a closed predicate language over the account's event
  window (`noEvents(kind, window)`, `countEvents(kind, window) <op> n`, `sumEvents(kind, window)
  <op> amount`, `balanceMin(window) <op> amount`, with `and`/`or`/`not`), which replaces free-text
  `bonusRateCondition` and **closes ADR-0138 phase 3**;
- fees with a cadence and a waiver predicate — the ADR-0138 `WaivePredicate.Comparison` grammar
  migrates into the IR predicate language unchanged (same attributes, same operators), and
  `WaivePredicate.Unparseable` has no IR form because a revision that fails validation is never
  published;
- term deposit: maturity action (`CAPITALISE | PAY_OUT | ROLL_OVER`) and an early-withdrawal
  penalty expressed as a rate or a forfeited-interest fraction.

**Lending is deferred to IR v2** (amortisation method, repayment allocation order, restructuring),
because ADR-0264 phase D needs durable component balances the lending aggregate does not carry yet
(issue #668, 2026-10-06) and because v1 must prove the pipeline on the product with the smallest
state. The loan pack's `allocationOrder` vocabulary (ADR-0264) is the v2 input, not re-decided here.

### 2. Lifecycle hooks, instructions, hard run limits

A revision declares behaviour per hook: `onOpen`, `prePosting`, `postPosting`, `scheduled`,
`onParameterChange`, `onClose`. Each evaluation returns a list of **instructions** from a closed set:
`Post` (a balanced set of ledger lines with an accounting class per line), `Reject` (a stable reason
code, used only from `prePosting`), `Schedule` (a future hook at a cadence or date),
`Notify` (a template id and typed variables, per ADR-0176's closed variable schema), `Reprice`
(a parameter change the revision is allowed to make to itself, such as a tier move).

Every instruction carries `ruleId`, `revisionHash` and `irVersion`. `ruleId` is an **explicit
identifier declared in the IR**, unique within the revision and stable across revisions — never an
array index, so inserting a rule cannot silently rename the audit trail of every rule after it.

Hard run limits, part of the IR schema and checked at validation time, not at review:

- `maxInstructionsPerEvaluation` (v1 fixes it at 64) — more is a validation error;
- no recursion: a `Schedule` instruction may not target the hook that emitted it within the same
  cycle, and the hook graph a revision declares must be acyclic per cycle;
- no unbounded iteration: tiers, fee lists and predicate depth have schema maxima.

A gate (`product-ir-run-limits`, section 8) rejects any committed IR or golden scenario that exceeds
them. Review is not the control; the validator is.

### 3. Pure interpreter

```
evaluate(revision: ProductRevisionIr, state: ProductState, event: ProductEvent, clock: ProductClock)
    : List<Instruction>
```

The interpreter is a pure function: zero I/O, no clock reads other than the injected `clock`
(ADR-0100's clock-injection gate already forbids `Instant.now()` in domain layers), no randomness, no
logging, no metrics. State in, instructions out. The *same* class runs in the production consumer,
in `openbank-simulation`, in the CI golden runner and behind the Studio simulation endpoint. There is
no second implementation anywhere, including a JavaScript one in admin-ui: Studio calls a
product-catalog endpoint that runs the JVM interpreter.

### 4. Canonical hashing and signature — the hash is a security boundary

The published IR is hashed over its **canonical form**: object keys sorted by UTF-16 code unit
(RFC 8785 ordering, as `com.openbank.libs.audit.CanonicalJson` already does for the audit chain),
no insignificant whitespace, and every number written as a **fixed-scale decimal string**
(`"0.0250"`, never a float) so that `0.025` and `0.0250` in two authoring tools cannot produce two
hashes for one revision. `revisionHash` is the SHA-256 of that form and is what every instruction
carries.

The published revision is **signed**: a detached signature over `revisionHash` produced at publish
time with a key held in OpenBao (Transit `sign`/`verify` on a dedicated key, the ADR-0262 pattern;
cosign over the same digest is the acceptable alternative where the standalone distribution of
ADR-0257 has no OpenBao). The runtime **verifies the signature before executing a revision** and
refuses to evaluate an unsigned or mis-signed one. Consequence stated plainly: the hash and the key
are a security boundary. Whoever can produce a valid signature can change what every account under
that product does, so the signing capability is granted to the publish path only, never to a
developer, an agent or a service account, and key rotation follows ADR-0172.

### 5. Placement — ADR-0122 split, no new `openbank-libs-product` module

| Concern | Module |
|---|---|
| IR model, JSON parser, schema validator, canonical hash, interpreter | `openbank-libs-domain`, package `com.openbank.libs.product` (next to `WaiveCondition.kt`; the waiver types become the v1 predicate subset) |
| Day count, year fraction, accrual, compounding primitives | `openbank-libs-finance` (the interpreter calls these; the service stops computing them, closing the measurement above) |
| Amortisation and repayment allocation (IR v2) | `openbank-libs-lending` |
| Pinned-revision loader: local immutable cache keyed by `revisionHash`, signature verification, Micrometer metrics | `openbank-libs-runtime` |
| Golden-scenario runner, property generators (`ProductArb`), money-conservation assertion | `openbank-libs-testing` |
| Interpreter benchmark (`ProductInterpreterBench`) under the existing allocation baseline | `openbank-libs-benchmarks` |
| detekt rule: no I/O, no clock, no logging imports under `com.openbank.libs.product` | `openbank-libs-detekt-rules` |

No new `openbank-libs-product` module: ADR-0317 is mid-flight moving business packages *out* of
`openbank-libs-domain`, and the product package is platform-core by the test that ADR uses — every
product-bearing consumer and the catalog itself depend on it. If ADR-0317 phase 3 later classifies it
as a bounded-context package, it moves under that ADR's rules, not this one's.

**New content module `openbank-products`** holds the products themselves: a Kotlin DSL per product
compiled to IR JSON at build time (the DSL is authoring sugar; the IR is the artifact and the only
thing the catalog stores), the golden scenarios per product, and the property tests. It is
registered as a **released component** (`version.txt`, `release-please-config.json`,
`.release-please-manifest.json`), so every product change has a release and a changelog entry, and
`check-release-registration` keeps the three in lockstep.

### 6. Resilience — the catalog is never in the posting path

- A consumer reads the pinned revision from a **local, signed, immutable copy** (the
  `openbank-libs-runtime` loader) keyed by `revisionHash`. Product-catalog is a publication
  authority, not a runtime dependency: a posting never waits on a catalog call.
- **Fail-closed on a missing pin only.** A contract with no pinned revision, or a pin whose signed
  copy cannot be verified, does not post. Catalog *unavailability* is not a failure condition at
  posting time, because the posting path does not call it. This reconciles ADR-0158 (fail-open,
  because the catalog is reference data) with ADR-0264/0341 (fail-closed): both were right about
  different things, and the local signed copy is what makes both true at once.
- **Shadow runs are asynchronous.** During migration a consumer publishes its legacy decision as an
  outbox event; a shadow comparator evaluates the IR over the same inputs off the posting
  transaction and records agreement per `ruleId`. The shadow never runs inside the real posting
  transaction. The comparator emits `openbank_product_shadow_compared_total` and
  `openbank_product_shadow_diverged_total`, and **an alert fires when the compared counter is flat
  while the legacy path posts** — "shadow compared nothing" is a distinct outcome, never silent
  (the ADR-0252 phase 0 lesson: a skipped result must not share a signal with success).
- **Every `scheduled` hook emits the ADR-0237 workflow-liveness gauge** keyed by product and hook,
  so a dead scheduler for one product is visible at 2× its cadence like every other job.

### 7. Governance — two approvals, neither alone publishes

Publishing a revision requires **both**:

1. **Engineering approval**: the IR change lands in `openbank-products` through an ordinary PR with
   the repo's review rules, golden scenarios updated, CI gates green. The merged commit's IR hash
   is what the catalog is allowed to publish.
2. **Business approval**: the Studio role approvals the pack's `approvalPolicy` declares
   (ADR-0345), bound to the same `revisionHash`, maker ≠ checker as `ProductRevision` already
   enforces.

The publish action succeeds only when the hash the roles approved equals a hash present on
`origin/main` of `openbank-products` and the signature is produced for exactly that hash.
`rules.yaml: four_eyes.actions` gains `catalog.publish-ir` as its own action (the existing
`catalog.publish` stays for non-IR revisions); the guardrail in that section applies — no M2M caller
exists or is planned for it.

**ADR-0259 reconciled explicitly.** "No executable rules" remains the rule **for agents**: an AI
author may propose IR and golden scenarios *as a PR*, with the behavioural simulation diff attached
(section 8), and may never sign, approve or publish. The prohibition is **lifted for human-approved,
signed IR**, because the IR is total and typed: it cannot read, write, loop or call anything the
schema does not name, so the risk ADR-0259 guarded against — a model inducing an executable side
effect — has no carrier. The human remains the only authority, as that ADR requires.

**Relationship to prior ADRs.** This ADR takes over ADR-0138 phase 3 (ADR-0138 keeps its number and
its shipped phases; its delivery note points here). It extends ADR-0257 (revision content gains
behaviour), ADR-0264 (phase D moves to IR v2), and ADR-0341 (the pinned revision is now the source
of behaviour, not only of price and class).

### 8. Measured quality — fixed by this ADR

- **Coverage floor**: `com.openbank.libs.product` carries its own Kover rule at **90 % line**,
  fixed here rather than ratcheted from zero; `coverage-floor-ratchet` (enforced) then keeps it from
  moving down. The module floor of `openbank-libs-domain` (87 today) is unaffected.
- **Interpreter latency budget**: **p99 < 2 ms per `evaluate`** on the golden set, measured by
  `ProductInterpreterBench` in `openbank-libs-benchmarks` and compared by the existing
  `libs JMH allocation baseline` gate (it compares allocation per op; the benchmark records latency
  alongside and the gate's baseline file gains the latency bound).
- **Determinism gate** (`product-ir-determinism`): runs every golden scenario twice in one JVM and
  asserts byte-identical instruction lists; then runs the set under **both supported `irVersion`s**
  and asserts the documented equivalence table (identical, or a listed intentional difference).
- **Money conservation**: every `Post` instruction is balanced per currency. Asserted in CI over
  the golden scenarios by the `openbank-libs-testing` runner, and **at runtime by the consumer
  before the ledger call** — an unbalanced instruction is a defect in the interpreter, never a
  ledger error to discover later.
- **Run limits gate** (`product-ir-run-limits`): validates every committed IR against the schema
  maxima in section 2.
- **detekt** `ProductIrPurityRule`: no `java.io`, `java.nio.file`, `java.net`, `java.time.Clock`
  default constructors, `System`, logging or Micrometer imports under `com.openbank.libs.product`.
- **Threat model**: `docs/threat-models/openbank-libs-product.md` (the shared library, not a
  service), covering the signing key, the hash boundary, a malicious IR within schema limits, and
  the shadow comparator as an information channel.

All new gates start `advisory` with a `target_enforce_date` per ADR-0144 and flip to `enforced` at
the end of phase 2, before any consumer switches (phase 3).

### 9. Migration order and phases 0–6

Rule for every consumer: **shadow, then switch**. A consumer runs the IR in asynchronous shadow
(section 6) until the divergence counter has been zero across one full cadence of every hook it
owns (one month for monthly capitalisation), then flips to IR as the only decision path behind a
flag whose default is `false` until that evidence exists, and deletes the legacy path in the
following release.

| Phase | Deliverable | Done when |
|---|---|---|
| 0 | This ADR | merged |
| 1 | IR v1 schema, parser, validator, canonical hash, interpreter in `openbank-libs-domain`; finance primitives in `openbank-libs-finance`; golden runner in `openbank-libs-testing` | determinism + run-limits + purity gates exist (advisory), 90 % floor set |
| 2 | `openbank-products` module (DSL → IR, first deposit products, golden scenarios, property tests), released component; signing in product-catalog publish; loader in `openbank-libs-runtime`; gates flip to enforced | a deposit revision publishes signed and a consumer can verify and load it |
| 3 | Consumers, in order: **billing → interest → lending (allocation via IR v2 only; v1 pins and shadows nothing) → account** (`onOpen` eligibility, fail-closed on missing pin), each shadow then switch | each consumer's legacy decision code deleted |
| 4 | `ProductReplayScenario` in `openbank-simulation` loads a signed revision; revision-vs-revision backtest over recorded event history; asynchronous shadow ledger | Studio can show "this change moves X accounts by Y" |
| 5 | Studio: IR editor, behavioural diff (instructions, not paths), simulation before approval, per-rule explain | structural diff retired as the only diff |
| 6 | Formal properties in CI (termination, balance, monotone tier) over property generators; `irVersion` lifecycle (two supported, removal is breaking); multi-entity parameters per ADR-0337 | — |

### 10. Interest-service stops owning arithmetic

Decided independently of the measurement above, which only sizes the risk: **interest-service
stops owning interest arithmetic.** Day count, year fraction, rounding and tier mathematics come
from `openbank-libs-finance` primitives — one audited implementation — invoked only through the
pure interpreter. The service keeps its aggregates, its outbox and its ledger port; it no longer
multiplies, scales or picks a rounding mode.

The measurement makes this a **migration risk, not a refactor**: `InterestService.kt:168` rounds
the daily accrual to scale 6 HALF_UP in service code, and the kernel `DayCount` rounds the year
fraction to scale 10 before that multiplication. Any difference in where the finance primitive
rounds changes the sixth decimal of every accrual and, summed over a month, the capitalised amount.
Therefore the interest switch (phase 3) requires **bit-exact golden tests against
production-shaped data** — recorded accrual inputs and outputs exported from the running service,
not hand-written unit fixtures — and the shadow period must show zero divergence at the posted,
currency-scale amount *and* at the scale-6 accrual.

Every accrual and capitalisation posting carries `ruleId`, `revisionHash` and `irVersion`. A rate
change becomes a revision publication. Compliance consequence, stated against `rules.yaml`:
interest-service is in `money_path_services` and its operator writes (`interest.create`,
`interest.trigger`) are wired in `four_eyes.actions`, but — as that entry itself records — the
daily accrual and monthly capitalisation schedulers call the use cases in-process, so the HTTP
four-eyes gate never touches the automation that actually moves the money, and fleet-wide four-eyes
enforcement is still off (`authz.four-eyes.enforce`, ADR-0155). Under this ADR the amount every
automated posting moves is fixed by a revision that needed two independent approvals and a
signature before it existed. That is the first point at which the money interest-service moves is
under four-eyes, independent of whether the HTTP flag is ever flipped.

### 11. Developer and operator surface

The surface is designed to be better than commercial smart-contract cores on the axis they cannot
match from a closed model: everything runs offline, deterministically, from a public library.

1. **`obp` CLI** inside `openbank-products` (Kotlin, Gradle application plugin). Sub-commands:
   `obp lint`, `obp compile` (prints the canonical hash), `obp test` (golden + property),
   `obp simulate --from <date> --to <date> --portfolio <fixture|synthetic>`,
   `obp diff <revA> <revB> --behavioural` (replays the portfolio under both revisions and reports
   posting deltas per `ruleId`), `obp explain <postingId|instruction>` (`ruleId` to DSL source
   line), `obp sign` (requests the publish-path signature; available only to the publish identity),
   `obp propose` (opens the PR and creates a DRAFT revision through the catalog v2 API; **never
   publishes**). It runs the same pure interpreter, fully offline; there is no hosted simulation
   endpoint the CLI depends on. Output is canonical JSON — diffable and machine-readable.
2. **No restricted-script sandbox.** The IR is total by construction; the Kotlin DSL exists only
   at build time; the runtime never loads Kotlin, a script engine or any class it did not ship with.
3. **Public product library.** `openbank-products` ships reference products — savings, current
   account, term deposit; loan in IR v2 — as open source, and their golden scenarios double as
   **public conformance vectors**: a third party can prove an independent interpreter agrees with
   this one.
4. **Revision lifecycle.** Side-by-side revisions with effective dating; cohort or staged rollout
   where new contracts take the new revision while existing contracts keep their pin; and a
   governed **repricing path** for existing contracts — an `onParameterChange` hook plus a
   mandatory `obp diff --behavioural` backtest attached to the Studio approval, so nobody approves
   a reprice without seeing which contracts move by how much.

### 12. MCP and AI, consistent with ADR-0259 and ADR-0031

`openbank-mcp-service` exposes `product.lint`, `product.compile`, `product.simulate`,
`product.diff`, `product.explain` and `product.propose`. All are read-only or proposal-only;
**`product.publish` does not exist as an MCP tool, by construction** — not denied by policy, absent
from the surface. Each tool calls the same library the CLI calls, so an agent's result is
reproducible byte-for-byte by a human running `obp` on the same inputs.

The AI Author charter in `agents.yaml` receives exactly these tools (`tools.allow`), gated by the
OPA policy bundle like every charter (ADR-0031). Its PR must attach the `obp diff --behavioural`
output, and the Studio business approval displays that diff next to the role approval buttons. For
comparison: the first commercial path announced in 2026 for generating a product contract with a
language model relies on human sign-off alone. Here the sign-off is preceded by a mandatory
behavioural simulation diff and followed by a signature and two independent approvals.

## Alternatives considered

- **Vendor-style smart contracts in a general-purpose language (Python, JavaScript, Kotlin
  scripts) executed in a sandbox.** Maximally expressive and the industry's reference shape. Rejected:
  a sandbox bounds *what* a script may touch, not *whether* it terminates, balances or replays
  identically; the reviewer proves nothing, and ADR-0138, ADR-0257 and ADR-0259 all rejected
  executable scripts in catalog content for that reason. The verifiability is the product.
- **Generic rule/expression DSL (CEL, MVEL, a Rego-like language) over the kernel attributes.**
  Smaller than a scripting language, still open-ended: an expression language has no notion of
  hook, instruction, cadence or balance, so every guarantee in sections 2, 4 and 8 would be a
  convention on top of it. ADR-0138 rejected this for phase 1; the reasons hold.
- **Keep behaviour in each service's Kotlin and widen the kernel's attributes and prices.** The
  status quo plus ADR-0341. Cheapest. Rejected: it leaves three implementations of interest
  arithmetic (measured above), makes "what would this revision do" unanswerable before publication,
  and keeps the catalog in billing's posting path.
- **A separate `openbank-product-runtime` service that evaluates revisions over REST.** Rejected
  by ADR-0346's extraction test: it would put a network call in every posting and every scheduled
  accrual for no ownership or cadence benefit, and ADR-0122/0317 already give the in-process shape.
- **Hash without signature** (the kernel's `contentHash` as the integrity check). Rejected: a hash
  proves a document is the one that was hashed, not that anyone was allowed to publish it; anyone
  with database write access could insert a revision and its matching hash.

## Consequences

**Positive**
- One implementation of product behaviour, executed identically in production, simulation, CI and
  Studio; the three interest arithmetics collapse into `openbank-libs-finance`.
- A revision is diffable by behaviour, simulatable before approval and replayable after, which is
  the verifiability and governance story the platform can beat scripting cores on.
- The catalog leaves the posting path; the fail-open/fail-closed split between ADR-0158 and
  ADR-0264 resolves without a third policy.
- ADR-0138 phase 3 closes with a predicate language rather than a widened grammar; ADR-0259's
  boundary becomes precise (agents propose, humans sign).

**Negative**
- **A new executor is a new attack surface.** Anything that evaluates data it did not write can be
  made to misbehave by that data. The mitigation is the whole design, not a sandbox: the IR is
  *total* (every program terminates by construction — no loops, no recursion, bounded
  instructions), *typed* (the schema names every construct; nothing else parses), and *signed*
  (only a human-approved hash with a valid signature from the publish-only key executes). The
  residual risk is a *valid* revision with wrong economics, which is exactly what the mandatory
  simulation and the business approval exist to catch; and a compromised signing key, which is
  why it is a boundary (section 4) with its own threat model.
- **Removing an `irVersion` is a breaking change.** A pinned contract may reference a revision
  under either supported version for its whole life; dropping a version orphans every such pin.
  Removal therefore needs a migration of every pinned revision to the surviving version and a
  major release of `openbank-products`, never a quiet deletion.
- Two approvals per publish is more ceremony than today's maker-checker; it is the price of
  letting behaviour, not only price, change without a service release.
- Phase 3 carries a shadow period per consumer measured in product cadences; monthly
  capitalisation cannot be proven in less than a month.
- The interest switch is the riskiest step: a scale-6 service-side rounding (section 10) must be
  reproduced bit-exactly by a finance primitive, and only production-shaped golden data can show it.

**Neutral**
- **Three authoring surfaces, one artifact.** `obp`, the MCP tools and the Studio forms are the
  only ways to author a revision, and all three must produce identical canonical IR and the same
  hash: gate `product-ir-authoring-parity` compiles one fixture through each path and compares the
  bytes. A fourth surface is a change to this ADR.
- The industry pattern of a locked-down scripting subset is not adopted at all, so there is no
  sandbox to maintain, escape or audit; the cost is that anything the IR cannot express needs an
  `irVersion` bump through the engineering path rather than a script.
- The kernel's attributes, prices and `approvalPolicy` are unchanged; the IR is additional
  revision content under the same profile.
- Lending behaviour stays in Kotlin until IR v2; ADR-0264 phase D is sequenced, not re-decided.

### Gates

| Gate id | Mode at introduction | What it fails on |
|---|---|---|
| `product-ir-determinism` | advisory → enforced end of phase 2 | non-identical instruction lists across two in-process runs or across supported `irVersion`s |
| `product-ir-run-limits` | advisory → enforced end of phase 2 | committed IR or golden scenario exceeding schema maxima |
| `product-ir-purity` (detekt, `ProductIrPurityRule`) | enforced from phase 1 | an I/O, clock, logging or metrics import under `com.openbank.libs.product` |
| `libs JMH allocation baseline` (existing) | advisory | `ProductInterpreterBench` missing from the baseline or over its latency bound |
| `coverage-floor-ratchet` (existing, enforced) | enforced | the 90 % package floor lowered |
| `product-ir-authoring-parity` | advisory → enforced end of phase 5 | `obp`, MCP and Studio compile one fixture to different canonical bytes or hashes |

### Delivery check

```bash
# phase 1: the interpreter exists in the domain package and nothing in it does I/O
ls openbank-libs-domain/src/main/kotlin/com/openbank/libs/product/ir/ProductInterpreter.kt        # exists
git grep -lE 'java\.io|java\.nio\.file|java\.net|org\.jboss\.logging|io\.micrometer' -- \
  openbank-libs-domain/src/main/kotlin/com/openbank/libs/product                                 # expect nothing
# phase 2: the content module is a released component and the gates exist
test -f openbank-products/version.txt && grep -c '"openbank-products"' release-please-config.json .release-please-manifest.json   # 1 and 1
yq '.gates[] | select(.id | test("^product-ir-")) | .id + " " + .mode' .github/gates/gates.yaml  # 3 lines, enforced after phase 2
# phase 3: consumers evaluate the IR and no longer compute interest or waivers themselves
git grep -L 'com.openbank.libs.product.ir' -- openbank-billing-service/src/main openbank-interest-service/src/main  # expect nothing
git grep -n 'setScale(6, RoundingMode.HALF_UP)' -- openbank-interest-service/src/main              # expect nothing
git grep -n 'bonusRateCondition' -- openbank-product-catalog/src/main                             # expect nothing
# runtime: the shadow comparator is observable
git grep -n 'openbank_product_shadow_compared_total' -- openbank-libs-runtime/src/main openbank-infra/gitops  # metric and its alert rule
```

## Compliance impact

- PCI DSS: not applicable — product behaviour carries no cardholder data.
- DORA: the signed IR and its threat model add an ICT asset (the signing key) to the key inventory
  under ADR-0172; no change to incident or third-party arrangements.
- GDPR: not applicable — revisions and golden scenarios contain no personal data; the shadow
  comparator records rule ids and amounts, not identities.
- PSD2: not applicable — no payment-initiation or access-to-account surface changes.
- CNB: not applicable — regulatory classification stays as ADR-0341 decided; this ADR changes how
  behaviour is derived, not how it is reported.

## References

- ADR-0100, ADR-0122, ADR-0138, ADR-0143, ADR-0158, ADR-0172, ADR-0176, ADR-0213, ADR-0237,
  ADR-0252, ADR-0257, ADR-0258, ADR-0259, ADR-0262, ADR-0264, ADR-0317, ADR-0318, ADR-0337,
  ADR-0341, ADR-0345, ADR-0346, ADR-0347
- Issue #668 (product configurability; measured state 2026-10-06 and 2026-10-17)
- `docs/strategy/02-competitor-analysis.md`
- RFC 8785 (JSON Canonicalization Scheme) for the key ordering the canonical form reuses
