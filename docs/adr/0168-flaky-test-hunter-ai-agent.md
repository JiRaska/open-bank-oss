# ADR-0168 — flaky-test-hunter AI agent

Date: 2026-07-13
Decision-Status: Accepted
Delivery-Status: Partial
Author(s): jiri.raska (paired with Claude Sonnet 5)

## Context

This repo has a narrow, working CI guard for exactly one silent-test-failure shape, and two more
shapes with no automated re-verification at all:

1. **The `runBlocking`-without-`: Unit` JUnit5 silent-drop.** `fun foo() = runBlocking { ... }`
   (expression-body form) infers a non-`Unit` return type from the lambda's last expression; JUnit5
   only runs `void`/`Unit`-returning methods, so such a test method is silently dropped — it
   compiles, the build stays green, and the test asserts nothing. `.github/scripts/
   check-test-runblocking-unit.sh` (part of the "Validate manifests" CI gate) catches the exact
   literal shape `) = runBlocking {` without a nearby `: Unit`, and has genuinely fired pre-merge
   more than once (PR #931's `ApprovalResourceTest`, PR #994's standing-order consumer tests) — the
   guard works as designed. But it is a single hardcoded pattern: a helper function using a
   different coroutine entry point with the identical non-Unit-inference shape (`GlobalScope.launch`,
   `GlobalScope.async`) is invisible to it, and nothing periodically re-confirms the guard itself has
   not regressed or been bypassed on an already-merged branch. (`runTest` looks like a candidate for
   the same generalization but is not: its lambda parameter type is fixed at `Unit` — JVM `actual
   typealias TestResult = Unit` — unlike `runBlocking`'s generic `<T>`, so it can never hit this bug,
   and is in fact this repo's own CLAUDE.md-recommended fix for it.)
2. **Pact provider verification's local-green blind spot.** Provider tests are gated
   `@EnabledIfSystemProperty(named = "pactbroker.url", …)` — always skipped when a developer runs
   `./gradlew test` locally with no broker reachable; only CI, against a real broker, verifies them.
   This is intentional and documented (`CLAUDE.md`: "Pact provider tests don't run locally without a
   broker"), but nothing surfaces the distinction to a developer who just watched `./gradlew test`
   report all-green and reasonably assumed the contract was checked.
3. **The two-provider-verification-class collision.** Two separate `@Provider("X")`-annotated test
   classes for the same provider — one targeting `HttpTestTarget`, one targeting
   `MessageTestTarget` — both pull EVERY pact the broker holds for that provider, because pact-jvm
   does not filter by target type. The HTTP class then also tries (and fails, with
   `UnsupportedOperationException`) to verify a message pact meant for the other class. `CLAUDE.md`
   ("Contract tests (Pact) pitfalls") documents the fix — one `@Provider` test that selects the
   target per interaction in `@BeforeEach` — but nothing re-scans the fleet to confirm no other
   provider has quietly grown a second verification class the same way.

A fourth, independent gap: nothing compares how many `@Test`-annotated functions a module's source
declares against how many JUnit actually reports as executed. A mismatch is a useful cross-check
that does not presuppose the cause — it could be pattern 1, a stale tag filter, or something this
agent's other three checks do not yet know to look for.

## Decision

We add **flaky-test-hunter** as a new AI agent (ADR-0031), a static analyzer over the fleet's Kotlin
test sources, following the same Temporal-orchestrated hexagonal shape as finops-agent, devops-agent,
control-liveness-sentinel, governance-auditor, release-steward, docs-truth-agent, and
authz-policy-auditor:

- **`plane: development`, not `control`** — the one deliberate divergence from all six prior agents.
  Every finding here is a candidate edit to test CODE inside a service (a coroutine builder's return
  type, which of two test classes should own an interaction, a stale tag filter), the same kind of
  artifact `ledger-domain-engineer`'s charter (the repo's one `plane: development` precedent) opens
  PRs against — not a governance document, an infrastructure manifest, or a release-metadata field
  the six `plane: control` agents' findings touch. The charter shape below still mirrors the
  control-plane agents' tier/`resources:` `tools.allow`/`deny` structure rather than
  `ledger-domain-engineer`'s flat glob-list + `owns:`/`skills:` fields, because this agent does not
  own one service the way `ledger-domain-engineer` owns `openbank-ledger-service` — it reads and
  reports across the whole fleet, the same breadth every `plane: control` agent has. `plane` here
  answers "what kind of artifact does a finding change," not "does this agent own a service."
- **Reads only**, via direct repository-checkout reads of every module's `src/test/kotlin` tree and
  `build/test-results/test` JUnit XML reports — this agent runs from within the monorepo, so a full
  Kotlin PSI/compiler-frontend parse is not needed for these four checks (mirrors
  `check-test-runblocking-unit.sh`'s own "grep, not a compiler-frontend parse" design and
  authz-policy-auditor's/docs-truth-agent's/release-steward's real, best-effort grep/text-scan
  adapter precedent).
- **Runs on a weekly sweep plus a reactive trigger** when a CI test-suite run reports a failure —
  the same "periodic sweep is the right cadence for a standing-claim check, reactive re-run on a
  relevant signal is a cheap addition" reasoning the prior agents' ADRs already established, applied
  here to a CI test-suite failure webhook instead of a source-file-changed webhook, since a
  silently-dropped test correlates more with "the suite behaved unexpectedly" than with any single
  file's edit.
- **Implements four checks, correlated into one triaged report** (`TestScanPort`):
  1. **`runBlocking`-without-`: Unit` (and near-miss builders).** Reuses
     `check-test-runblocking-unit.sh`'s exact regex shape for `runBlocking` — kept textually
     consistent with the guard by construction, not by parsing the shell script at runtime — and
     generalizes it to `GlobalScope.launch` and `GlobalScope.async`, the coroutine entry points that
     genuinely share the expression-body non-Unit-inference hazard. Deliberately excludes `runTest`:
     its lambda type is fixed at `Unit` so it can never hit this bug, and it is this repo's own
     documented fix for it — flagging it would mark the correct remediation as a violation.
  2. **Pact local-verification blind spot.** One finding per module summarizing how many
     `@EnabledIfSystemProperty(named = "pactbroker.url", …)`-gated classes it has — informational,
     not a defect: it exists so a developer reading this agent's report knows their own
     `./gradlew test` run never touched these contracts.
  3. **Pact provider-class collision.** Flags any provider name with more than one distinct
     `@Provider("...")`-annotated test class in the fleet — the exact shape `CLAUDE.md` documents
     the fix for.
  4. **Test-count drift.** Compares declared `@Test` occurrences in a module's source against the
     JUnit-reported executed count from that module's own `build/test-results/test` XML reports,
     when present; a module never built in this checkout is excluded from the sample, not
     misreported as a 100% drop.
- **Every finding from checks 1-3 is `draft.ticket` — never `openProposalPr`.** A wrong auto-fix
  here does not corrupt a governance document or an infrastructure manifest; it risks silently
  masking a real bug by guessing wrong on `suspend fun` vs. `: Unit`, on which of two colliding
  `@Provider` classes should own an interaction, or on why a Pact test is locally gated. Check 4
  (`TEST_COUNT_DRIFT`) is the one check `LlmDiagnosisPort.proposeFixDiff` branches on by shape (it
  could, in principle, have a narrow mechanical root cause like a stale tag filter), but even that
  branch still returns `null` unconditionally in v1 — the same deliberately-not-yet-decided posture
  release-steward's `APP_VERSION_OVERRIDE` branch started from before it was narrowed.
- `tools.deny` blocks every write/execute tier explicitly, matching every sibling agent — this agent
  can never edit a test file directly; it only reports.

## Alternatives considered

- **A Kotlin PSI/compiler-frontend parse (e.g. via `detekt`'s own AST) instead of text scanning.**
  Rejected for v1 for the same reason `check-test-runblocking-unit.sh` stays grep-based: no new
  build-time dependency, and the four checks are shallow enough (an expression-body shape, an
  annotation string, a provider-name string, a count comparison) that a correctly-scoped text scan
  has a low false-negative rate for the specific incidents observed. A structural parse would close
  real edge cases (e.g. a multi-line function signature, or a builder wrapped through an alias
  import) this agent's line-local scan cannot see through — tracked as a known gap, not attempted
  here.
- **Actually running `./gradlew test` fleet-wide as part of the collect activity, to always have a
  fresh test-count sample.** Rejected: this agent has read-only `data_scope` and no execute-tier
  tool grant (ADR-0031's "agents propose, governance disposes" principle) — invoking a build is a
  write/execute action on the repo's CI capacity, not a read. Check 4 instead reads whatever
  `build/test-results/test` reports already exist in the mounted checkout (freshest after a CI
  run), and is honestly excluded (not fabricated as zero) when none exist yet.
- **Fold into authz-policy-auditor or docs-truth-agent.** Rejected for the same least-privilege
  reasoning every prior agent's ADR used: authz-policy-auditor's charter is scoped to
  authorization-policy structural correctness (can a rego rule's condition ever fire), and
  docs-truth-agent's to documentation-vs-code claims — neither is "does a test method actually run
  under JUnit," a distinct axis with its own source directory (`src/test/kotlin`, not `src/main`,
  not `docs/adr`) and its own existing narrow guard to generalize.
- **`plane: control` (matching the other five agents) instead of `development`.** Considered, since
  this agent's `tools`/`data_scope` shape is structurally identical to the control-plane agents', not
  to `ledger-domain-engineer`'s. Rejected: `plane` is meant to signal what KIND of artifact a
  finding proposes to change, and every finding here targets test source inside a service — the same
  category `ledger-domain-engineer` opens PRs against, not a governance/infrastructure artifact. See
  Decision for why the tool shape still follows the control-plane pattern despite the plane choice.

## Consequences

**Positive**
- Generalizes `check-test-runblocking-unit.sh`'s one exact-literal pattern (already proven to fire
  pre-merge twice — PR #931, PR #994) to three additional coroutine builders sharing the identical
  hazard, as a periodic, standing re-verification rather than a merge-time-only gate.
- Makes the Pact local-verification blind spot and the two-provider-collision shape `CLAUDE.md`
  already documents in prose into actively re-verified facts fleet-wide, not just a comment a future
  editor might not read before adding a second `@Provider` class for some other service.
- An independent test-count cross-check (check 4) that does not presuppose which of the other three
  checks (or an entirely different cause) explains a drop — useful even when checks 1-3 find
  nothing.
- Same governance shape as its six siblings — no new review pattern for operators to learn.

**Negative**
- Detection only, never correction — every finding needs a human to read the test's intent before
  anything changes, the fleet's most conservative disposition stance alongside authz-policy-auditor's
  (for a different reason: epistemic uncertainty about masking a real bug, not security blast
  radius).
- A text-scan-based check has real false-negative edges: a multi-line function signature, a builder
  reached through a type alias or an indirection this agent's line-local scan cannot see through, or
  a `@Provider` annotation built from a constant instead of a string literal are all invisible to it.
  A `draft.ticket` gives a human the citation, not a verdict — the same shallow-and-honest posture
  every prior agent's ADR already accepted for its own grep-based checks.
- Check 4 depends on `build/test-results/test` reports already existing in the mounted checkout;
  a checkout that has never run `./gradlew test` for a given module samples nothing for it, not a
  fabricated zero — an honest gap, but a gap.
- An eighth Temporal-orchestrated agent adds one more workload reading test/CI-adjacent state other
  agents already read pieces of (devops-agent's `github-actions-readonly` scope overlaps loosely) —
  the same acceptable, least-privilege-scoped duplication trend the prior agents' ADRs already
  flagged.

**Neutral**
- No new infrastructure: reuses Temporal (ADR-0101) and the existing GitHub-proposal/HITL-queue
  pattern; the test-scan side is a new but simple integration (local file reads plus grep against a
  repo checkout), the same pattern authz-policy-auditor's `PolicyScanPort` and docs-truth-agent's
  `RepoScanPort` already established.

## Compliance impact

- PCI DSS: strengthens software-testing change-management evidence (requirement 6.x) that a test
  suite's reported "green" state actually reflects tests that ran, not tests silently dropped by a
  language-level footgun or a stale gating annotation.
- DORA: supports Art. 5 (ICT risk management framework) and Art. 9 (ICT risk detection) — a
  silently-skipped test is exactly the kind of latent ICT risk (a control that looks present but
  never actually executes) this agent surfaces proactively, generalizing a guard that has already
  proven necessary twice in this repo's own history.
- GDPR / PSD2 / CNB: indirect — this agent strengthens the reliability of the test evidence that
  underlies every other control's own compliance claims (a masked bug in a money-path service's test
  suite is a masked bug in whatever PCI/PSD2/GDPR guarantee that test was meant to verify), rather
  than asserting a compliance property of its own.

## References

- [ADR-0031](0031-ai-agent-governance-and-operations.md) — AI agent governance framework (charter
  shape, HITL, kill switch)
- [ADR-0163](0163-control-liveness-sentinel-ai-agent.md) — sibling agent (operational-liveness axis)
- [ADR-0164](0164-governance-auditor-ai-agent.md) — sibling agent (merged-PR compliance axis)
- [ADR-0165](0165-release-steward-ai-agent.md) — sibling agent (release/version axis); its
  `APP_VERSION_OVERRIDE`-only mechanical-fix branch is the structural precedent this agent's
  `TEST_COUNT_DRIFT` branch (still always-null in v1) follows
- [ADR-0166](0166-docs-truth-agent-ai-agent.md) — sibling agent (documentation-vs-code drift axis)
- [ADR-0167](0167-authz-policy-auditor-ai-agent.md) — sibling agent (authorization-policy structural
  axis); shares this agent's real-grep-not-a-parser design stance and ticket-only disposition
  reasoning, for a different underlying reason (security blast radius vs. epistemic uncertainty)
- [ADR-0101](0101-temporal-durable-execution.md) — Temporal orchestration
- PR #931 — `feat(transaction): wire four-eyes enforcement mechanism (ADR-0155)`; its
  `ApprovalResourceTest` runBlocking-Unit fixup is one of the two real pre-merge catches check 1
  generalizes
- PR #994 — `feat(standing-order): execute due orders via the SEPA rail (#889)`; its consumer-test
  runBlocking-Unit fixup is the other
- `.github/scripts/check-test-runblocking-unit.sh` — the narrow, single-builder CI guard this
  agent's check 1 generalizes
- `CLAUDE.md` "Contract tests (Pact) pitfalls" — documents both the local-verification blind spot
  (check 2) and the two-provider-class collision (check 3) this agent's checks re-verify fleet-wide
