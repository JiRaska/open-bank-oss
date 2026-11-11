// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.product

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Every committed golden scenario passes against the one interpreter with the production arithmetic. */
class GoldenScenariosTest {

    private val runner = GoldenScenarioRunner()

    @TestFactory
    fun `golden scenarios`() = GoldenScenarios.names().map { name ->
        DynamicTest.dynamicTest(name) { runner.assertPasses(GoldenScenarios.load(name)) }
    }

    @Test
    fun `the golden set covers the six behaviours ADR-0352 phase 1 names`() {
        val names = GoldenScenarios.names()
        assertThat(names).hasSizeGreaterThanOrEqualTo(6)
        assertThat(names.joinToString()).contains("fixed", "tiered", "bonus", "fee", "term-deposit", "reject")
        assertThat(GoldenScenarios.all().map { it.revision.revisionHash }).doesNotHaveDuplicates()
    }

    @Test
    fun `the runner reports a wrong expectation with both byte forms`() {
        val scenario = GoldenScenarios.load("preposting-reject-insufficient-funds.json")
        val broken = scenario.copy(
            steps = scenario.steps.mapIndexed { i, s ->
                if (i ==
                    0
                ) {
                    s.copy(expectedCanonical = "[]")
                } else {
                    s
                }
            },
        )
        val error = runCatching { runner.assertPasses(broken) }.exceptionOrNull()
        assertThat(error).isInstanceOf(AssertionError::class.java)
        assertThat(error!!.message).contains("step 0", "expected []", "actual   [{").contains("INSUFFICIENT_FUNDS")
    }

    @Test
    fun `a step that is rejected does not apply its posting, an accepted one does`() {
        val results = runner.run(GoldenScenarios.load("preposting-reject-insufficient-funds.json"))
        assertThat(results.map { it.matches }).containsOnly(true)
        // 60 over a balance of 50 is still rejected, so the rejected 150 was never applied
        assertThat(results[2].instructions).hasSize(1)
    }
}
