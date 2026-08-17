// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.evals

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.fraud.domain.model.FraudVerdict
import com.openbank.fraud.domain.rules.FraudRuleEngine
import java.time.Clock
import java.time.Instant

/** Outcome of comparing one [FraudReviewScenario]'s ground truth against a live engine call. */
data class ScenarioResult(
    val id: String,
    val description: String,
    val pass: Boolean,
    val expectedVerdict: String,
    val actualVerdict: String,
    val expectedSurfacedInQueue: Boolean,
    val actualSurfacedInQueue: Boolean,
    val missingReasons: List<String>,
    val actualReasons: List<String>,
)

/** The archived-per-run report (ADR-0235 "results archived per-run for prompt-drift analysis"). */
data class SuiteReport(
    val suite: String,
    val version: String,
    val ruleVersion: String,
    val runAt: String,
    val total: Int,
    val passed: Int,
    val passRate: Double,
    val minPassRate: Double,
    val regressed: Boolean,
    val results: List<ScenarioResult>,
)

/**
 * The runner. Deliberately tiny and dependency-free beyond what fraud-service already ships
 * (Jackson + Kotlin module are `implementation` deps of the main source set, so they are on the
 * test classpath with no new dependency): call the real [FraudRuleEngine.score], compare against
 * declared ground truth, and report. No I/O beyond the archived JSON write the caller opts into.
 */
object FraudReviewEvalRunner {
    const val SUITE = "fraud-review-queue"
    const val VERSION = "v1"

    /** Pure — the one comparison the whole gate rests on. No I/O, no clock, no randomness. */
    fun evaluate(scenario: FraudReviewScenario): ScenarioResult {
        val score = FraudRuleEngine.score(scenario.request)
        val missing = scenario.expectedReasons.filterNot { expected ->
            score.reasons.any { it.equals(expected, ignoreCase = true) }
        }
        val surfaced = score.verdict == FraudVerdict.REVIEW
        val pass = score.verdict == scenario.expectedVerdict &&
            surfaced == scenario.expectedSurfacedInQueue &&
            missing.isEmpty()
        return ScenarioResult(
            id = scenario.id,
            description = scenario.description,
            pass = pass,
            expectedVerdict = scenario.expectedVerdict.name,
            actualVerdict = score.verdict.name,
            expectedSurfacedInQueue = scenario.expectedSurfacedInQueue,
            actualSurfacedInQueue = surfaced,
            missingReasons = missing,
            actualReasons = score.reasons,
        )
    }

    fun run(
        scenarios: List<FraudReviewScenario> = FRAUD_REVIEW_SCENARIOS,
        minPassRate: Double,
        clock: Clock = Clock.systemUTC(),
    ): SuiteReport {
        val results = scenarios.map { evaluate(it) }
        val passed = results.count { it.pass }
        val rate = if (results.isEmpty()) 0.0 else passed.toDouble() / results.size
        return SuiteReport(
            suite = SUITE,
            version = VERSION,
            ruleVersion = FraudRuleEngine.RULE_VERSION,
            runAt = Instant.now(clock).toString(),
            total = results.size,
            passed = passed,
            passRate = rate,
            minPassRate = minPassRate,
            regressed = rate < minPassRate,
            results = results,
        )
    }
}

private val reportMapper: ObjectMapper = ObjectMapper()
    .registerKotlinModule()
    .registerModule(JavaTimeModule())
    .apply { enable(SerializationFeature.INDENT_OUTPUT) }

fun SuiteReport.toJson(): String = reportMapper.writeValueAsString(this)
