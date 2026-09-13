// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import com.openbank.fx.application.port.out.FraudScoreCommand
import com.openbank.fx.application.port.out.FraudVerdict
import com.openbank.fx.infrastructure.observability.FraudScoringMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * The fail-OPEN posture below is deliberate and unchanged (the verdict is observed, never enforced).
 * What #4221 added are the assertions that a synthetic ALLOW is *distinguishable* from a real one —
 * on the outcome and in the metrics. The pre-existing verdict-mapping tests are untouched.
 */
class FraudScoringAdapterTest {

    private val client = mockk<FraudScoreClient>()
    private val registry = SimpleMeterRegistry()
    private val metrics = FraudScoringMetrics().apply { bindTo(registry) }
    private val adapter = FraudScoringAdapter(client, metrics).also { it.self = it }

    private fun counter(result: String): Double =
        registry.find(FraudScoringMetrics.OUTCOMES_METRIC).tag("result", result).counter()?.count() ?: -1.0

    private fun command() = FraudScoreCommand(
        amount = BigDecimal("100.00"),
        currency = "EUR",
        rail = "FX",
        accountId = UUID.randomUUID(),
        counterpartyId = null,
    )

    @Test
    fun `a DECLINE verdict is mapped through unchanged`() {
        every { client.score(any()) } returns
            Uni.createFrom().item(
                FraudScoreClientResponse(
                    verdict = "DECLINE",
                    score = 95,
                    reasons = listOf("velocity-cap"),
                    ruleVersion = "v3",
                ),
            )

        val outcome = runBlocking { adapter.score(command()) }

        assertThat(outcome.verdict).isEqualTo(FraudVerdict.DECLINE)
        assertThat(outcome.score).isEqualTo(95)
        assertThat(outcome.reasons).containsExactly("velocity-cap")
        assertThat(outcome.ruleVersion).isEqualTo("v3")
    }

    @Test
    fun `an unrecognised remote verdict string defaults to ALLOW`() {
        every { client.score(any()) } returns
            Uni.createFrom().item(FraudScoreClientResponse(verdict = "UNKNOWN_VALUE", score = 0))

        val outcome = runBlocking { adapter.score(command()) }

        assertThat(outcome.verdict).isEqualTo(FraudVerdict.ALLOW)
    }

    @Test
    fun `fraud-service unavailable fails open with a shadow ALLOW, not an exception`() {
        every { client.score(any()) } returns Uni.createFrom().failure(RuntimeException("timeout"))

        val outcome = runBlocking { adapter.score(command()) }

        assertThat(outcome.verdict).isEqualTo(FraudVerdict.ALLOW)
        assertThat(outcome.score).isZero()
        assertThat(outcome.ruleVersion).isEqualTo("unavailable")
        assertThat(outcome.reasons).containsExactly("fraud-service-unavailable")
        assertThat(outcome.synthetic)
            .describedAs("a verdict the adapter invented must not be readable as one fraud-service gave")
            .isTrue()
        assertThat(counter(FraudScoringMetrics.RESULT_SYNTHETIC)).isEqualTo(1.0)
        assertThat(counter(FraudScoringMetrics.RESULT_REAL)).isZero()
        assertThat(metrics.degradedValue()).isEqualTo(1L)
    }

    @Test
    fun `a real ALLOW and a synthetic ALLOW are not equal`() {
        every { client.score(any()) } returns Uni.createFrom().item(FraudScoreClientResponse(verdict = "ALLOW"))
        val real = runBlocking { adapter.score(command()) }
        assertThat(real.synthetic).isFalse()
        assertThat(metrics.degradedValue()).isZero()

        every { client.score(any()) } returns Uni.createFrom().failure(RuntimeException("timeout"))
        val synthetic = runBlocking { adapter.score(command()) }

        assertThat(real.verdict).isEqualTo(synthetic.verdict)
        assertThat(real)
            .describedAs("the two outcomes must differ somewhere a caller can see")
            .isNotEqualTo(synthetic)
    }

    @Test
    fun `an Error from the client is contained, not propagated out of the fail-open path`(): Unit = runBlocking {
        // An `Error` is not an `Exception`: before #4221 this escaped `score` and propagated into
        // the conversion path, from an adapter whose whole contract is that it cannot affect it.
        every { client.score(any()) } throws NoClassDefFoundError("com/openbank/fraud/Boom")

        val outcome = adapter.score(command())

        assertThat(outcome.verdict).isEqualTo(FraudVerdict.ALLOW)
        assertThat(outcome.synthetic).isTrue()
        assertThat(metrics.degradedValue()).isEqualTo(1L)
    }

    @Test
    fun `a CHALLENGE verdict is mapped through unchanged`() {
        every { client.score(any()) } returns
            Uni.createFrom().item(FraudScoreClientResponse(verdict = "CHALLENGE", score = 40))

        val outcome = runBlocking { adapter.score(command()) }

        assertThat(outcome.verdict).isEqualTo(FraudVerdict.CHALLENGE)
    }

    @Test
    fun `a REVIEW verdict is mapped through unchanged`() {
        every { client.score(any()) } returns
            Uni.createFrom().item(FraudScoreClientResponse(verdict = "REVIEW", score = 60))

        val outcome = runBlocking { adapter.score(command()) }

        assertThat(outcome.verdict).isEqualTo(FraudVerdict.REVIEW)
    }
}
