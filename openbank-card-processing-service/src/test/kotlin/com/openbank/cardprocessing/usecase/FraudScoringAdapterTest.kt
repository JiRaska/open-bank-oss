// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.usecase

import com.openbank.cardprocessing.application.port.out.FraudScoringOutcome
import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.infrastructure.client.FraudScoreRequest
import com.openbank.cardprocessing.infrastructure.client.FraudScoreResponse
import com.openbank.cardprocessing.infrastructure.client.FraudScoringAdapter
import com.openbank.cardprocessing.infrastructure.client.FraudServiceClient
import com.openbank.cardprocessing.infrastructure.observability.CardProcessingMetricsAdapter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Shadow fraud scoring must be non-blocking AND visible. Before #12064 every request was refused
 * with a 400 and the only trace was a debug line; these pin the request shape fraud-service
 * actually accepts, and that a refusal is counted rather than swallowed.
 */
class FraudScoringAdapterTest {

    private val now = Instant.parse("2026-09-05T12:00:00Z")
    private val client = mockk<FraudServiceClient>()

    private fun authorization(currency: String = "CZK", amount: Long = 2_500) = CardAuthorization(
        id = UUID.randomUUID(),
        cardId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        partyId = UUID.randomUUID(),
        amountMinorUnits = amount,
        currencyCode = currency,
        channel = PresentmentChannel.ONLINE,
        mcc = "5411",
        merchantName = "Potraviny",
        merchantCountry = "CZ",
        status = AuthorizationStatus.APPROVED,
        category = "GROCERIES",
        declineReason = null,
        clearedAmountMinorUnits = 0,
        networkReference = "acq-1",
        authorizedAt = now,
        expiresAt = now.plusSeconds(3600),
        updatedAt = now,
    )

    @Test
    fun `sends currency, rail CARD and major units, and reads verdict`(): Unit = runBlocking {
        val sent = slot<FraudScoreRequest>()
        coEvery { client.score(capture(sent)) } returns FraudScoreResponse(verdict = "ALLOW", score = 7)

        val auth = authorization()
        val result = FraudScoringAdapter(client, scoringEnabled = true).score(auth)

        assertThat(result.outcome).isEqualTo(FraudScoringOutcome.SCORED)
        assertThat(result.decision).isEqualTo("ALLOW")
        assertThat(result.score).isEqualTo(7.0)
        assertThat(sent.captured.currency).isEqualTo("CZK")
        assertThat(sent.captured.rail).isEqualTo("CARD")
        assertThat(sent.captured.accountId).isEqualTo(auth.accountId)
        // 2 500 minor units of CZK is 25.00 — not 2 500, which would inflate every amount rule 100x.
        assertThat(sent.captured.amount).isEqualByComparingTo(BigDecimal("25.00"))
    }

    @Test
    fun `a zero-decimal currency is scored in whole units`(): Unit = runBlocking {
        val sent = slot<FraudScoreRequest>()
        coEvery { client.score(capture(sent)) } returns FraudScoreResponse(verdict = "ALLOW", score = 0)

        FraudScoringAdapter(client, scoringEnabled = true).score(authorization(currency = "JPY", amount = 1_200))

        assertThat(sent.captured.amount).isEqualByComparingTo(BigDecimal("1200"))
    }

    @Test
    fun `a 4xx from fraud-service is FAILED, never thrown, and counted by the failure series`(): Unit = runBlocking {
        coEvery { client.score(any()) } throws WebApplicationException(BAD_REQUEST)
        val registry = SimpleMeterRegistry()
        val metrics = CardProcessingMetricsAdapter(registry)

        val result = FraudScoringAdapter(client, scoringEnabled = true).score(authorization())
        metrics.fraudScoring(result.outcome)

        assertThat(result.outcome).isEqualTo(FraudScoringOutcome.FAILED)
        assertThat(result.decision).isNull()
        val failed = registry.find("openbank.card.processing.fraud.scores")
            .tag("outcome", FraudScoringOutcome.FAILED.name)
            .counter()
        assertThat(failed).describedAs("FAILED fraud-score series not registered").isNotNull()
        assertThat(failed!!.count()).isEqualTo(1.0)
    }

    private companion object {
        const val BAD_REQUEST = 400
    }
}
