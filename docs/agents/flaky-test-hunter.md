---
id: flaky-test-hunter
plane: development
adr: ADR-0168
---

# flaky-test-hunter

## Mission

Static analyzer for fleet-wide silent-test-failure patterns in Kotlin test sources. On a weekly
sweep plus reactively whenever a CI test-suite run reports a failure, this agent generalizes the
`check-test-runblocking-unit.sh` CI guard (an expression-body test function using
`runBlocking`/`runTest`/`GlobalScope.launch`/`GlobalScope.async` without an explicit `: Unit` return
type, which JUnit5 silently drops instead of running) beyond its one hardcoded builder, flags Pact
provider verification tests gated on `pactbroker.url` (always skipped by a local `./gradlew test`
run), flags any provider with two colliding `@Provider` test classes, and cross-checks
declared-vs-executed `@Test` counts per module. Every finding becomes a tracking ticket through the
HITL queue for nearly all cases — guessing wrong on a fix here (`suspend fun` vs. `: Unit`, which
`@Provider` class should own an interaction) risks silently masking a real bug instead of fixing it.
It never writes to a test file directly.

## Why this agent exists

This repo has a narrow, working CI guard for exactly one silent-test-failure shape
(`check-test-runblocking-unit.sh`, which has genuinely fired pre-merge more than once — PR #931's
`ApprovalResourceTest`, PR #994's standing-order consumer tests) and two more documented-but-
unverified shapes: `CLAUDE.md`'s "Contract tests (Pact) pitfalls" section describes both the
local-verification blind spot (provider tests gated on `pactbroker.url` never run outside CI) and
the two-provider-verification-class collision (two `@Provider` classes for the same provider both
pull every pact the broker holds, one failing with `UnsupportedOperationException`), but nothing
periodically re-scans the fleet to confirm the documented fix still holds, or that no other service
has quietly grown the same mistake. A fourth, independent check (test-count drift) does not
presuppose which of the other three explains a mismatch — a useful cross-check even when checks 1-3
find nothing.

## Human oversight

- `any_flaky_test_finding` — every finding needs a human who reads the test's actual intent before
  anything changes; the agent cannot make that call itself.
- `every: proposal` — the agent never merges a PR or edits a test file directly; segregation of
  duties matches every other agent.
- `tokens_per_run: 50000` — capped so the agent's own running cost stays a rounding error next to
  the cost of a silently-dropped test reaching production undetected.

## Known gaps

- The `TestScanPort` adapter is a genuine (not stubbed) grep/text-scan implementation, but
  deliberately shallow — line-local pattern matching, not a Kotlin PSI/compiler-frontend parse. A
  multi-line function signature, a coroutine builder reached through a type alias or indirection, or
  a `@Provider` annotation built from a constant instead of a string literal are all invisible to it;
  a `draft.ticket` gives a human the citation, not a verdict.
- Check 4 (test-count drift) only samples a module that already has at least one JUnit XML report
  under `build/test-results/test` in the mounted checkout — a module never built there is excluded
  from the sample, not misreported as a 100% drop. The deployment-side checkout-mount wiring (a
  sidecar or init-container that runs the fleet's tests, or mounts CI's own artifacts) is tracked
  separately.
- Check 4 only flags `executedCount < declaredCount`; `executedCount > declaredCount` (common with
  parameterized/dynamic tests) is deliberately not flagged, to avoid false-flagging every
  parameterized suite in the fleet — a real but rarer drift shape (a source-level `@Test` that
  somehow inflates the reported count) is out of scope.
- `LlmDiagnosisPort.proposeFixDiff` only branches non-null-eligible for `TEST_COUNT_DRIFT`, and even
  that branch still returns `null` unconditionally in v1 — this agent never proposes a fix diff for
  any check type yet, the same bootstrap-stub state every sibling agent shipped with, but a
  deliberately more conservative default (see Mission) than most of them.
- The `LlmDiagnosisPort` and `GitHubProposalPort` adapters are stubs pending the shared LiteLLM
  gateway and GitHub App installation-token wiring, the same bootstrap state every sibling agent
  shipped with.
- `repo-root` (`FLAKY_TEST_HUNTER_REPO_ROOT`) must point at a mounted, up-to-date checkout of `main`
  for the `TestScanPort` checks to be meaningful — the deployment-side checkout-mount wiring is
  tracked separately and not yet part of this PR's gitops manifest.
