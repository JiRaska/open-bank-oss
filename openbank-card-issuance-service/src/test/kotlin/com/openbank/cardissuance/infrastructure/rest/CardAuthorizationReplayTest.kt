// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.openbank.cardissuance.application.port.`in`.CardUseCase
import com.openbank.cardissuance.domain.model.Card
import com.openbank.cardissuance.domain.model.CardNetwork
import com.openbank.cardissuance.domain.model.CardStatus
import com.openbank.cardissuance.domain.model.CardType
import com.openbank.cardissuance.infrastructure.persistence.repository.CardCategoryRuleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The authorization POST is a fresh, read-only decision, not a state transition (#8845).
 * A retry against unchanged inputs repeats the verdict; a later card block must change it.
 * Strict mocks make an unexpected write fail rather than letting this exception hide one.
 */
class CardAuthorizationReplayTest {
    @Test
    fun `retry recomputes from current card state without writing`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val active = activeCard(id)
        val useCase = mockk<CardUseCase>()
        val rules = mockk<CardCategoryRuleRepository>()
        coEvery { useCase.getCard(id) } returnsMany listOf(active, active, active.copy(status = CardStatus.BLOCKED))
        coEvery { rules.findByCard(id) } returns emptyList()
        val resource = CardAuthorizationResource(useCase, rules)
        val request = AuthorizationDecisionRequest(amountMinorUnits = 100, channel = "CHIP_AND_PIN", mcc = "5411")

        val first = resource.authorize(id, request).entity as AuthorizationDecisionResponse
        val retry = resource.authorize(id, request).entity as AuthorizationDecisionResponse
        val afterBlock = resource.authorize(id, request).entity as AuthorizationDecisionResponse

        assertThat(first.approved).isTrue()
        assertThat(retry).isEqualTo(first)
        assertThat(afterBlock.approved).isFalse()
        assertThat(afterBlock.declineReason).isEqualTo("CARD_NOT_ACTIVE")
        coVerify(exactly = 3) { useCase.getCard(id) }
        coVerify(exactly = 3) { rules.findByCard(id) }
        confirmVerified(useCase, rules)
    }

    private fun activeCard(id: UUID) = Card(
        id = id,
        idempotencyKey = "issued",
        partyId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        productCode = "STANDARD",
        cardType = CardType.DEBIT,
        network = CardNetwork.VISA,
        maskedPan = "**** 1234",
        cardholderName = "Test Holder",
        embossedName = "Test Holder",
        expiryDate = LocalDate.of(2030, 1, 1),
        status = CardStatus.ACTIVE,
        dailyLimitMinorUnits = 50_000,
        monthlyLimitMinorUnits = 500_000,
        currency = "CZK",
        deliveryAddress = null,
        activatedAt = Instant.EPOCH,
        blockedAt = null,
        blockedReason = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
