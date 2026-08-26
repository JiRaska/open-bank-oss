// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.domain.model

import java.math.BigDecimal
import java.time.Instant

// One value per silent-test-failure pattern this agent re-verifies fleet-wide (ADR-0168). Check 1
// generalizes an existing, narrow CI guard (`.github/scripts/check-test-runblocking-unit.sh`) that
// has genuinely fired pre-merge more than once (PR #931, PR #994); checks 2 and 3 surface a gap
// documented in this repo's own CLAUDE.md ("Contract tests (Pact) pitfalls") that no automated check
// currently re-verifies; check 4 is an independent cross-check with no prior incident of its own.
enum class FlakyTestCheckType {
    RUNBLOCKING_UNIT_MISSING,
    PACT_LOCAL_VERIFICATION_BLIND_SPOT,
    PACT_PROVIDER_CLASS_COLLISION,
    TEST_COUNT_DRIFT,
    MISSING_EXECUTION_EVIDENCE,
    FAILED_TEST_EVIDENCE,
    OBSERVED_FAILING_TESTS,
    OBSERVED_FLAKY_TESTS,
    STALE_TEST_EVIDENCE,
    UNPROVEN_TEST_INFRASTRUCTURE,
    UNTERMINATED_TEST_INFRASTRUCTURE,
}

enum class FindingSeverity { WARNING, CRITICAL }

enum class FindingStatus { OPEN, DIAGNOSED, PROPOSED, APPROVED, REJECTED, RESOLVED }

/** One expression-body function in test source matching the JUnit5 silent-drop shape: `fun f(...) =
 * <builder> { ... }` with no explicit `: Unit` return type. [builder] is the coroutine entry point
 * matched — `runBlocking` mirrors `check-test-runblocking-unit.sh`'s exact pattern; `GlobalScope.launch`
 * and `GlobalScope.async` are the "similar mistake with a different coroutine builder" near-misses the
 * CI guard's single literal pattern does not cover. Deliberately does NOT match `runTest`:
 * kotlinx-coroutines-test's `runTest` has its lambda parameter type fixed at `Unit` (JVM `actual
 * typealias TestResult = Unit`), unlike `runBlocking`'s generic `<T>`, so `fun f(...) = runTest { ... }`
 * always infers `Unit` and can never hit this bug — and this repo's own CLAUDE.md recommends `runTest`
 * as the fix for the `runBlocking` footgun, so flagging it would mark the correct remediation as a
 * violation. Not restricted to `@Test`-annotated functions — a private helper a test method calls
 * indirectly matches the same textual shape and is exactly as invisible to JUnit5 if the helper itself
 * is what silently returns a non-Unit value. */
data class RunBlockingViolation(val file: String, val line: Int, val builder: String, val snippet: String)

/** One test class in [module] gated on `@EnabledIfSystemProperty(named = "pactbroker.url", ...)` —
 * always skipped when a developer runs `./gradlew test` locally with no broker reachable, so a
 * local all-green run never actually verified this class's Pact contracts. */
data class PactGatedTestClass(val file: String, val module: String, val className: String)

/** One `@Provider("...")` class-level annotation found anywhere in test source, tagged with the
 * provider name it declares. Two or more distinct classes declaring the SAME provider name is the
 * shape of the two-provider-verification-class collision (fixed for transaction-service by unifying
 * into one `@Provider` test with a per-interaction target, documented in this repo's CLAUDE.md
 * "Contract tests (Pact) pitfalls"). */
data class PactProviderDeclaration(val file: String, val providerName: String)

/** One module's declared-vs-executed `@Test` count comparison. [declaredCount] is a source-level
 * grep of `@Test`-annotated function declarations; [executedCount] is the sum of JUnit XML
 * `<testsuite tests="N">` totals under that module's `build/test-results/test/` directory, when
 * present. A module with no test-results reports yet (tests never run in this checkout) is not
 * sampled — see [TestScanSnapshot] — rather than fabricated as a false "0 executed" drift. */
data class TestCountSample(val module: String, val declaredCount: Int, val executedCount: Int)

/**
 * One collect-phase snapshot: every raw signal the four implemented checks need, gathered in a
 * single repo-checkout pass (TestScanPort). The judging (turning a raw signal into a finding, or
 * deciding it is benign) happens in DetectDriftActivityImpl, not here — mirrors every sibling
 * agent's collect/detect split.
 */
data class TestScanSnapshot(
    val testFilesScanned: Int,
    // Check 1.
    val runBlockingViolations: List<RunBlockingViolation>,
    // Check 2.
    val pactGatedClasses: List<PactGatedTestClass>,
    // Check 3.
    val pactProviderDeclarations: List<PactProviderDeclaration>,
    // Check 4: only modules with at least one JUnit XML report under build/test-results/test/ are
    // included — a module never built in this checkout is silently excluded, not misreported.
    val testCountSamples: List<TestCountSample>,
)

data class FlakyTestFinding(
    val id: String,
    val checkType: FlakyTestCheckType,
    val severity: FindingSeverity,
    val detectedAt: Instant,
    val title: String,
    // The test file, or the module, this finding is about.
    val component: String,
    val filePath: String,
    val rawMetricValue: BigDecimal,
    val threshold: BigDecimal,
    val rootCause: String? = null,
    val proposalUrl: String? = null,
    val proposedFixDiff: String? = null,
    val status: FindingStatus = FindingStatus.OPEN,
    val diagnosedAt: Instant? = null,
    val proposedAt: Instant? = null,
)

data class FlakyTestReport(
    val runId: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val testFilesScanned: Int,
    val findingsDetected: List<FlakyTestFinding>,
    val findingsProposed: Int,
    val tokensUsed: Int,
    val trigger: RunTrigger,
)

enum class RunTrigger { SCHEDULED, CI_TEST_SUITE_FAILURE_WEBHOOK, OPERATOR_MANUAL }

/** Privacy-bounded projection received only from the authenticated Admin UI BFF. */
data class TestIntelligenceAnalysisRequest(
    val snapshotId: String,
    val collectedAt: Instant,
    val components: List<TestIntelligenceComponentInput>,
)

data class TestIntelligenceComponentInput(
    val component: String,
    val moneyPath: Boolean,
    val evidence: List<TestIntelligenceEvidenceInput>,
    val declaredInfrastructure: List<String>,
    val observedInfrastructureStarts: Int,
    /** A start event proves neither teardown nor an isolated next test. */
    val observedInfrastructureStops: Int = 0,
    val flakyTests: Int = 0,
    val failingTests: Int = 0,
    val sameCommitTransitions: Int = 0,
    val wastedDurationMs: Int = 0,
)

data class TestIntelligenceEvidenceInput(val kind: String, val state: String)
