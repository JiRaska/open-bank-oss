// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import com.openbank.fx.application.port.out.FraudScoreCommand
import com.openbank.fx.application.port.out.FraudVerdict
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FraudScoringAdapterTest {

    private val client = mockk<FraudScoreClient>()
    private val adapter = FraudScoringAdapter(client).also { it.self = it }

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
