// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

import com.openbank.sepainstant.application.port.out.FraudScoreCommand
import com.openbank.sepainstant.application.port.out.FraudVerdict
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Unit tests for the fail-OPEN fraud-scoring adapter (ADR-0084 §1, SHADOW phase). Unlike the
 * sanctions adapter, an outage here must map to ALLOW — shadow scoring may never touch a customer.
 */
class FraudScoringAdapterTest {

    private val client = mockk<FraudScoreClient>()
    private val adapter = FraudScoringAdapter(client).also { it.self = it }

    private val accountId = UUID.fromString("33333333-3333-3333-3333-333333333333")

    private fun command() = FraudScoreCommand(
        amount = BigDecimal("250.00"),
        currency = "EUR",
        rail = "SCT_INST",
        accountId = accountId,
        counterpartyId = null,
    )

    private fun response(verdict: String, score: Int = 10) = FraudScoreClientResponse(
        verdict = verdict,
        score = score,
        reasons = listOf("velocity-cap"),
        ruleVersion = "v3",
    )

    @Test
    fun `a DECLINE verdict is mapped with its score reasons and rule version`() {
        every { client.score(any()) } returns Uni.createFrom().item(response("DECLINE", score = 99))

        val outcome = adapter.score(command()).await().indefinitely()

        assertThat(outcome.verdict).isEqualTo(FraudVerdict.DECLINE)
        assertThat(outcome.score).isEqualTo(99)
        assertThat(outcome.reasons).containsExactly("velocity-cap")
        assertThat(outcome.ruleVersion).isEqualTo("v3")
    }

    @Test
    fun `verdict mapping is case-insensitive across the remote vocabulary`() {
        every { client.score(any()) } returns Uni.createFrom().item(response("allow"))
        assertThat(adapter.score(command()).await().indefinitely().verdict).isEqualTo(FraudVerdict.ALLOW)

        every { client.score(any()) } returns Uni.createFrom().item(response("Challenge"))
        assertThat(adapter.score(command()).await().indefinitely().verdict).isEqualTo(FraudVerdict.CHALLENGE)

        every { client.score(any()) } returns Uni.createFrom().item(response("REVIEW"))
        assertThat(adapter.score(command()).await().indefinitely().verdict).isEqualTo(FraudVerdict.REVIEW)
    }

    @Test
    fun `an unknown remote verdict defaults to ALLOW in shadow`() {
        every { client.score(any()) } returns Uni.createFrom().item(response("QUARANTINE"))

        assertThat(adapter.score(command()).await().indefinitely().verdict).isEqualTo(FraudVerdict.ALLOW)
    }

    @Test
    fun `the request carries amount currency rail and account`() {
        val requestSlot = slot<FraudScoreClientRequest>()
        every { client.score(capture(requestSlot)) } returns Uni.createFrom().item(response("ALLOW"))

        adapter.score(command()).await().indefinitely()

        assertThat(requestSlot.captured.amount).isEqualByComparingTo("250.00")
        assertThat(requestSlot.captured.currency).isEqualTo("EUR")
        assertThat(requestSlot.captured.rail).isEqualTo("SCT_INST")
        assertThat(requestSlot.captured.accountId).isEqualTo(accountId)
        assertThat(requestSlot.captured.counterpartyId).isNull()
    }

    @Test
    fun `a fraud-service outage fails open to ALLOW instead of propagating`() {
        every { client.score(any()) } returns Uni.createFrom().failure(RuntimeException("connection refused"))

        val outcome = adapter.score(command()).await().indefinitely()

        assertThat(outcome.verdict).isEqualTo(FraudVerdict.ALLOW)
        assertThat(outcome.score).isEqualTo(0)
        assertThat(outcome.ruleVersion).isEqualTo("unavailable")
        assertThat(outcome.reasons).containsExactly("fraud-service-unavailable")
    }
}
