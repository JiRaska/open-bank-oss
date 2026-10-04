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
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** The wire fields and money unit used by fraud-service, not the acquirer's card-message fields. */
class FraudScoringAdapterTest {

    private val client = mockk<FraudServiceClient>()

    @Test
    fun `card authorisation is scored in major CZK units with the provider verdict`(): Unit = runBlocking {
        val sent = slot<FraudScoreRequest>()
        coEvery { client.score(capture(sent)) } returns FraudScoreResponse(10.0, "ALLOW")
        val auth = authorization("CZK", 12_345)

        val result = FraudScoringAdapter(client, scoringEnabled = true).score(auth)

        assertThat(result.outcome).isEqualTo(FraudScoringOutcome.SCORED)
        assertThat(result.decision).isEqualTo("ALLOW")
        assertThat(result.score).isEqualTo(10.0)
        assertThat(sent.captured.amount).isEqualByComparingTo(BigDecimal("123.45"))
        assertThat(sent.captured.currency).isEqualTo("CZK")
        assertThat(sent.captured.rail).isEqualTo("CARD")
        assertThat(sent.captured.accountId).isEqualTo(auth.accountId)
        assertThat(sent.captured.counterpartyId).isNull()
    }

    @Test
    fun `zero-decimal currency is never divided by one hundred`(): Unit = runBlocking {
        val sent = slot<FraudScoreRequest>()
        coEvery { client.score(capture(sent)) } returns FraudScoreResponse(0.0, "ALLOW")

        val result = FraudScoringAdapter(client, scoringEnabled = true).score(authorization("JPY", 12_345))

        assertThat(result.outcome).isEqualTo(FraudScoringOutcome.SCORED)
        assertThat(sent.captured.amount).isEqualByComparingTo(BigDecimal("12345"))
    }

    private fun authorization(currency: String, minorUnits: Long): CardAuthorization {
        val now = Instant.parse("2026-10-04T00:00:00Z")
        return CardAuthorization(
            id = UUID.randomUUID(),
            cardId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            amountMinorUnits = minorUnits,
            currencyCode = currency,
            channel = PresentmentChannel.ONLINE,
            mcc = "5411",
            merchantName = "Pact merchant",
            merchantCountry = "CZ",
            status = AuthorizationStatus.APPROVED,
            category = "GROCERIES",
            declineReason = null,
            clearedAmountMinorUnits = 0,
            networkReference = "fraud-test",
            authorizedAt = now,
            expiresAt = now.plusSeconds(3600),
            updatedAt = now,
        )
    }
}
