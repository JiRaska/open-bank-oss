// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.evals

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/** Regression floor read from `src/test/resources/evals/fraud-review-baseline.json`. */
private data class Baseline(val suite: String, val version: String, val minPassRate: Double)

private val baselineMapper = ObjectMapper().registerKotlinModule()

/**
 * The fraud-review-queue eval pack — issue #4463 ("evals: runnable benchmark suite for agent
 * quality"), scoped to the fraud review scenario pack (see `evals/README.md` for why the copilot
 * proposal-quality pack is deferred to a follow-up).
 *
 * Two things this class proves, deliberately split the same way
 * `openbank-libs/governance/evals/README.md` splits the ADR-0148 LLM evals gate into "structural
 * validation" and "the runner":
 *
 * 1. The `fraud review scenarios` [TestFactory] — each [FraudReviewScenario] becomes its own JUnit
 *    [DynamicTest], so CI's JUnit XML reports **per-scenario pass/fail**, not just a suite-level
 *    boolean — the same signal an `@ParameterizedTest` would give, generated from data instead of
 *    annotations so the scenario pack itself stays pure data (see `FraudReviewScenarios.kt`).
 * 2. The `regression gate archives the run and fails below the declared baseline` test runs the
 *    whole suite once more through [FraudReviewEvalRunner.run], **archives a JSON report** to
 *    `build/eval-reports/` (the CI workflow uploads it as a build artifact — ADR-0235 "results
 *    archived per-run for prompt-drift analysis"), and **fails the build** if the pass rate drops
 *    below [Baseline.minPassRate] — the ADR-0020 coverage-ratchet pattern this ADR-0148's own evals
 *    gate already applies to prompt/model replay, applied here to a deterministic business-logic
 *    pack instead.
 *
 * See [FraudReviewEvalHarnessSelfTest] for the falsification proof that this gate can actually go
 * red, not merely run.
 */
class FraudReviewEvalSuiteTest {

    @TestFactory
    fun `fraud review scenarios`(): List<DynamicTest> = FRAUD_REVIEW_SCENARIOS.map { scenario ->
        DynamicTest.dynamicTest("${scenario.id}: ${scenario.description}") {
            val result = FraudReviewEvalRunner.evaluate(scenario)

            assertThat(result.actualVerdict)
                .withFailMessage(
                    "scenario '%s' expected verdict %s but FraudRuleEngine returned %s (reasons=%s)",
                    scenario.id,
                    result.expectedVerdict,
                    result.actualVerdict,
                    result.actualReasons,
                )
                .isEqualTo(result.expectedVerdict)

            assertThat(result.actualSurfacedInQueue)
                .withFailMessage(
                    "scenario '%s' expected review-queue surfacing=%s but verdict %s implies %s",
                    scenario.id,
                    result.expectedSurfacedInQueue,
                    result.actualVerdict,
                    result.actualSurfacedInQueue,
                )
                .isEqualTo(result.expectedSurfacedInQueue)

            assertThat(result.missingReasons)
                .withFailMessage(
                    "scenario '%s' expected reasons %s missing from actual reasons %s",
                    scenario.id,
                    scenario.expectedReasons,
                    result.actualReasons,
                )
                .isEmpty()
        }
    }

    @Test
    fun `regression gate archives the run and fails below the declared baseline`() {
        val baseline = readBaseline()
        val report = FraudReviewEvalRunner.run(minPassRate = baseline.minPassRate)

        val outDir = File("build/eval-reports")
        outDir.mkdirs()
        File(outDir, "fraud-review-queue.json").writeText(report.toJson())

        assertThat(report.passRate)
            .withFailMessage(
                "fraud-review-queue eval pack regressed: pass rate %.4f is below the declared floor " +
                    "%.4f (%d/%d scenarios passed) — see build/eval-reports/fraud-review-queue.json",
                report.passRate,
                baseline.minPassRate,
                report.passed,
                report.total,
            )
            .isGreaterThanOrEqualTo(baseline.minPassRate)
    }

    private fun readBaseline(): Baseline {
        val stream = javaClass.getResourceAsStream("/evals/fraud-review-baseline.json")
            ?: error("missing src/test/resources/evals/fraud-review-baseline.json")
        return stream.use { baselineMapper.readValue(it, Baseline::class.java) }
    }
}
