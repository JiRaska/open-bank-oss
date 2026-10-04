// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.sepa.application.port.out.FraudScoreCommand
import com.openbank.sepa.application.port.out.FraudVerdict
import com.openbank.sepa.infrastructure.observability.FraudScoringMetrics
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
 * The fraud-scoring adapter is fail-OPEN on purpose (the verdict is observed, never enforced), so
 * these tests do **not** challenge the ALLOW. They challenge the thing that was actually broken
 * (#4221): that the synthetic ALLOW was indistinguishable from a real one, at the outcome and in
 * the metrics, so a scorer that was down looked exactly like a stream of clean payments.
 */
class FraudScoringAdapterTest {

    private val client = mockk<FraudScoreClient>()
    private val registry = SimpleMeterRegistry()
    private val metrics = FraudScoringMetrics().apply { bindTo(registry) }
    private val adapter = FraudScoringAdapter(client, metrics).also { it.self = it }

    private fun command() = FraudScoreCommand(
        amount = BigDecimal("100.00"),
        currency = "EUR",
        rail = "SEPA",
        accountId = UUID.randomUUID(),
        counterpartyId = null,
    )

    private fun counter(result: String): Double =
        registry.find(FraudScoringMetrics.OUTCOMES_METRIC).tag("result", result).counter()?.count() ?: -1.0

    @Test
    fun `a real verdict is mapped through and marked as not synthetic`() {
        every { client.score(any()) } returns Uni.createFrom().item(
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
        assertThat(outcome.ruleVersion).isEqualTo("v3")
        assertThat(outcome.synthetic).isFalse()
        assertThat(counter(FraudScoringMetrics.RESULT_REAL)).isEqualTo(1.0)
        assertThat(metrics.degradedValue()).isZero()
    }

    @Test
    fun `an unrecognised remote verdict is UNKNOWN, never a clean ALLOW`() {
        every { client.score(any()) } returns
            Uni.createFrom().item(FraudScoreClientResponse(verdict = "QUARANTINE", score = 0))

        val outcome = runBlocking { adapter.score(command()) }

        // #4403: fraud-service answered with a word this rail cannot read. Coercing that to ALLOW
        // manufactured a clean score out of an unreadable one; an enforcing caller must not see it.
        assertThat(outcome.verdict).isEqualTo(FraudVerdict.UNKNOWN)
        // Not an outage either — the scorer was reachable, so neither synthetic nor degraded.
        assertThat(outcome.synthetic).isFalse()
        assertThat(metrics.degradedValue()).isZero()
        assertThat(counter(FraudScoringMetrics.RESULT_UNRECOGNISED)).isEqualTo(1.0)
        assertThat(counter(FraudScoringMetrics.RESULT_REAL))
            .describedAs("an unreadable answer must not be tallied as a scored payment")
            .isZero()
    }

    @Test
    fun `a blank remote verdict is UNKNOWN as well`() {
        every { client.score(any()) } returns Uni.createFrom().item(FraudScoreClientResponse(verdict = "", score = 0))

        assertThat(runBlocking { adapter.score(command()) }.verdict).isEqualTo(FraudVerdict.UNKNOWN)
    }

    @Test
    fun `an outage still fails open, but the ALLOW is marked synthetic and counted`() {
        every { client.score(any()) } returns Uni.createFrom().failure(RuntimeException("connection refused"))

        val outcome = runBlocking { adapter.score(command()) }

        // Fail-open preserved — this is deliberate, see the adapter KDoc.
        assertThat(outcome.verdict).isEqualTo(FraudVerdict.ALLOW)
        assertThat(outcome.score).isZero()
        assertThat(outcome.ruleVersion).isEqualTo("unavailable")
        assertThat(outcome.reasons).containsExactly("fraud-service-unavailable")
        // ...and now distinguishable, which is the actual fix.
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

        every { client.score(any()) } returns Uni.createFrom().failure(RuntimeException("connection refused"))
        val synthetic = runBlocking { adapter.score(command()) }

        assertThat(real.verdict).isEqualTo(synthetic.verdict)
        assertThat(real)
            .describedAs("the two outcomes must differ somewhere a caller can see")
            .isNotEqualTo(synthetic)
    }

    @Test
    fun `an Error from the client is contained, not propagated out of the fail-open path`(): Unit = runBlocking {
        // An `Error` is not an `Exception`: before #4221 this escaped `score` and propagated into
        // the payment path, from an adapter whose whole contract is that it cannot affect it.
        every { client.score(any()) } throws NoClassDefFoundError("com/openbank/fraud/Boom")

        val outcome = adapter.score(command())

        assertThat(outcome.verdict).isEqualTo(FraudVerdict.ALLOW)
        assertThat(outcome.synthetic).isTrue()
        assertThat(metrics.degradedValue()).isEqualTo(1L)
    }
}
