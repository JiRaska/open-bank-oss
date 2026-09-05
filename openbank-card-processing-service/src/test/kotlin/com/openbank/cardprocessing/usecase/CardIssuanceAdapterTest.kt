// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.usecase

import com.openbank.cardprocessing.domain.model.CountedSpend
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.infrastructure.client.CardIssuanceAdapter
import com.openbank.cardprocessing.infrastructure.client.CardIssuanceClient
import com.openbank.cardprocessing.infrastructure.client.CardSummaryResponse
import com.openbank.cardprocessing.infrastructure.client.IssuerAuthorizationRequest
import com.openbank.cardprocessing.infrastructure.client.IssuerAuthorizationResponse
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The adapter that carries the authorisation decision, and the direction it fails in.
 *
 * Failing CLOSED is the property under test. An issuer that cannot evaluate its own controls must
 * not let spend through, or "payments abroad off" holds only while the network is healthy — and the
 * unavailability must carry its own reason, never one borrowed from the policy, because in a
 * dispute "the issuer was down" and "you turned gambling off" are different facts.
 */
class CardIssuanceAdapterTest {

    private val client = mockk<CardIssuanceClient>()
    private val cardId = UUID.randomUUID()

    private fun adapter() = CardIssuanceAdapter(client, defaultCurrency = "CZK")

    @Test
    fun `a decision is carried through with the issuer's own decline reason`() = runBlocking {
        val sent = slot<IssuerAuthorizationRequest>()
        coEvery { client.authorize(cardId, capture(sent)) } returns
            IssuerAuthorizationResponse(approved = false, declineReason = "CATEGORY_BLOCKED", category = "GAMBLING")

        val decision = adapter().decide(
            cardId = cardId,
            amountMinorUnits = 5_000,
            channel = PresentmentChannel.ONLINE,
            mcc = "7995",
            countryCode = "CZ",
            counted = CountedSpend(1_000, 2_000, 500),
        )

        assertThat(decision.approved).isFalse()
        assertThat(decision.reason).isEqualTo("CATEGORY_BLOCKED")
        // The counters this service measured are what the issuer is asked to judge against.
        assertThat(sent.captured.spentTodayMinorUnits).isEqualTo(1_000)
        assertThat(sent.captured.spentThisMonthInCategoryMinorUnits).isEqualTo(500)
        Unit
    }

    @Test
    fun `an unreachable issuer declines, under its own reason`() = runBlocking {
        coEvery { client.authorize(any(), any()) } throws
            WebApplicationException(Response.status(SERVICE_UNAVAILABLE).build())

        val decision = adapter().decide(
            cardId = cardId,
            amountMinorUnits = 5_000,
            channel = PresentmentChannel.CONTACTLESS,
            mcc = "5411",
            countryCode = "CZ",
            counted = CountedSpend(0, 0, 0),
        )

        assertThat(decision.approved).isFalse()
        assertThat(decision.reason).isEqualTo("ISSUER_UNAVAILABLE")
        Unit
    }

    @Test
    fun `an unknown card is null rather than an error`() = runBlocking {
        coEvery { client.getCard(cardId) } throws WebApplicationException(Response.status(NOT_FOUND).build())

        assertThat(adapter().lookup(cardId)).isNull()
        Unit
    }

    @Test
    fun `a card without a published currency falls back to the configured issuing currency`() = runBlocking {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        coEvery { client.getCard(cardId) } returns CardSummaryResponse(cardId, accountId, partyId, null, "ACTIVE")

        val ownership = adapter().lookup(cardId)

        assertThat(ownership?.accountId).isEqualTo(accountId)
        assertThat(ownership?.partyId).isEqualTo(partyId)
        // Stated in configuration rather than hidden in a literal: card-issuance does not publish a
        // per-card currency yet, and this is the one place that gap is visible.
        assertThat(ownership?.currencyCode).isEqualTo("CZK")
        Unit
    }

    private companion object {
        const val NOT_FOUND = 404
        const val SERVICE_UNAVAILABLE = 503
    }
}
