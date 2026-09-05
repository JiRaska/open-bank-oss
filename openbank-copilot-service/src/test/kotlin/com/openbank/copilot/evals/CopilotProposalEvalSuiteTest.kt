// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.evals

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/** Regression floor read from `src/test/resources/evals/copilot-proposal-baseline.json`. */
private data class ProposalBaseline(val suite: String, val version: String, val minPassRate: Double)

private val proposalBaselineMapper = ObjectMapper().registerKotlinModule()

/**
 * The **copilot proposal-quality** eval pack — issue #4463's second scenario pack, deferred when the
 * fraud pack shipped (PR #5105) and scoped here to what is honestly assertable. Mirrors
 * `FraudReviewEvalSuiteTest` in openbank-fraud-service, with one structural addition: a third
 * outcome.
 *
 * 1. The `copilot proposal scenarios` [TestFactory] gives **per-scenario pass/fail** in CI's JUnit
 *    XML. A [ScenarioOutcome.UNAVAILABLE] scenario is `abort`ed, so JUnit reports it as **skipped**
 *    with its reason — a third status in the XML, distinct from both green and red. It is not
 *    asserted green (which would claim a measurement nobody made) and not asserted red (which would
 *    report an agent-quality regression for a wiring gap).
 * 2. The regression gate archives a JSON report to `build/eval-reports/` (ADR-0235 "results archived
 *    per-run") and fails below the declared floor — the ADR-0020 ratchet. The floor is applied to
 *    `passed / (passed + failed)`; unavailable scenarios are counted and reported, never scored.
 *
 * See [CopilotProposalEvalHarnessSelfTest] for the falsification proof, and
 * [ProposalPathAvailabilityTest] for the guard that stops an UNAVAILABLE declaration outliving the
 * gap it describes.
 */
class CopilotProposalEvalSuiteTest {

    @TestFactory
    fun `copilot proposal scenarios`(): List<DynamicTest> = COPILOT_PROPOSAL_SCENARIOS.map { scenario ->
        DynamicTest.dynamicTest("${scenario.id}: ${scenario.description}") {
            val result = CopilotProposalEvalRunner.evaluate(scenario)

            if (result.outcome == ScenarioOutcome.UNAVAILABLE) {
                abort<Unit>(
                    "UNAVAILABLE (not a pass, not a failure) — ${scenario.id}: ${result.actual}. " +
                        "Requirement: ${scenario.requirement}. Tracked by ${result.trackedBy}.",
                )
            }

            assertThat(result.outcome)
                .withFailMessage(
                    "scenario '%s' FAILED (%s)%n  expected: %s%n  actual:   %s",
                    scenario.id,
                    scenario.requirement,
                    result.expected,
                    result.actual,
                )
                .isEqualTo(ScenarioOutcome.PASS)
        }
    }

    @Test
    fun `regression gate archives the run and fails below the declared baseline`() {
        val baseline = readBaseline()
        val report = CopilotProposalEvalRunner.run(minPassRate = baseline.minPassRate)

        val outDir = File("build/eval-reports")
        outDir.mkdirs()
        File(outDir, "copilot-proposal-quality.json").writeText(report.toJson())

        // A null rate means nothing was assertable at all. That is a broken pack, not a passing one,
        // and it must not read as 0.0 either — hence the explicit branch.
        assertThat(report.passRate)
            .withFailMessage(
                "copilot-proposal-quality has NO assertable scenarios (%d/%d unavailable) — the pack " +
                    "measures nothing and cannot clear its floor. See build/eval-reports/.",
                report.unavailable,
                report.total,
            )
            .isNotNull()

        assertThat(report.passRate!!)
            .withFailMessage(
                "copilot-proposal-quality eval pack regressed: pass rate %.4f is below the declared " +
                    "floor %.4f (%d passed, %d failed, %d unavailable of %d) — see " +
                    "build/eval-reports/copilot-proposal-quality.json",
                report.passRate,
                baseline.minPassRate,
                report.passed,
                report.failed,
                report.unavailable,
                report.total,
            )
            .isGreaterThanOrEqualTo(baseline.minPassRate)
    }

    private fun readBaseline(): ProposalBaseline {
        val stream = javaClass.getResourceAsStream("/evals/copilot-proposal-baseline.json")
            ?: error("missing src/test/resources/evals/copilot-proposal-baseline.json")
        return stream.use { proposalBaselineMapper.readValue(it, ProposalBaseline::class.java) }
    }
}
