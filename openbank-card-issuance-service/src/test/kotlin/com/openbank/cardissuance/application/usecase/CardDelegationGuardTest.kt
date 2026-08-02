// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.usecase

import com.openbank.cardissuance.application.port.out.CardDelegationProjectionRepository
import com.openbank.cardissuance.application.port.out.CardRepository
import com.openbank.cardissuance.domain.model.Card
import com.openbank.cardissuance.domain.model.CardDelegationIntent
import com.openbank.cardissuance.domain.model.DelegatedCardGrant
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class CardDelegationGuardTest {

    private val cardRepository: CardRepository = mockk()
    private val projectionRepository: CardDelegationProjectionRepository = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var guard: CardDelegationGuard

    private val cardId: UUID = UUID.randomUUID()
    private val holder: UUID = UUID.randomUUID()
    private val delegate: UUID = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.now(clock)

    @BeforeEach
    fun setUp() {
        guard = CardDelegationGuard(cardRepository, projectionRepository, clock)
        val card = mockk<Card>()
        io.mockk.every { card.partyId } returns holder
        coEvery { cardRepository.findById(cardId) } returns card
    }

    private fun grant(capabilities: Set<String>, validTo: OffsetDateTime? = now.plusDays(30), grantor: UUID = holder) =
        DelegatedCardGrant(
            id = UUID.randomUUID(),
            cardId = cardId,
            grantorPartyId = grantor,
            granteePartyId = delegate,
            capabilities = capabilities,
            validFrom = now.minusDays(1),
            validTo = validTo,
        )

    /**
     * The defect this guard shipped without, kept as the test that fails if the conjunct is removed.
     *
     * A grant is a row in a local projection fed by Kafka. Before the `issuedBy` check, ANY active
     * row for (cardId, delegate) authorised — including one a third party issued over a card they
     * do not hold. delegation-service refusing to mint such a grant (#3164 C1) is the upstream
     * half; this asserts the card side does not depend on it. #3143 makes the same assertion for
     * accounts and savings goals.
     */
    @Test
    fun `a grant issued by someone who is not the card holder authorises nothing`(): Unit = runBlocking {
        val stranger = UUID.randomUUID()
        coEvery { projectionRepository.findActiveByCardAndParty(cardId, delegate) } returns
            listOf(grant(setOf(DelegatedCardGrant.CAP_CARD_VIEW), grantor = stranger))

        assertThat(guard.isAuthorized(cardId, delegate, CardDelegationIntent.VIEW)).isFalse()
    }

    /** The mirror image: the identical grant, issued by the holder, does authorise. */
    @Test
    fun `the same grant issued by the holder authorises`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByCardAndParty(cardId, delegate) } returns
            listOf(grant(setOf(DelegatedCardGrant.CAP_CARD_VIEW), grantor = holder))

        assertThat(guard.isAuthorized(cardId, delegate, CardDelegationIntent.VIEW)).isTrue()
    }

    @Test
    fun `holder passes with an empty projection`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByCardAndParty(cardId, holder) } returns emptyList()
        assertThat(guard.isAuthorized(cardId, holder, CardDelegationIntent.VIEW)).isTrue()
        assertThat(guard.isAuthorized(cardId, holder, CardDelegationIntent.MANAGE_LIMITS)).isTrue()
    }

    @Test
    fun `delegate with CARD_VIEW passes VIEW but not MANAGE_LIMITS`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByCardAndParty(cardId, delegate) } returns
            listOf(grant(setOf("CARD_VIEW")))
        assertThat(guard.isAuthorized(cardId, delegate, CardDelegationIntent.VIEW)).isTrue()
        assertThat(guard.isAuthorized(cardId, delegate, CardDelegationIntent.MANAGE_LIMITS)).isFalse()
    }

    @Test
    fun `delegate with CARD_MANAGE_LIMITS passes MANAGE_LIMITS`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByCardAndParty(cardId, delegate) } returns
            listOf(grant(setOf("CARD_VIEW", "CARD_MANAGE_LIMITS")))
        assertThat(guard.isAuthorized(cardId, delegate, CardDelegationIntent.MANAGE_LIMITS)).isTrue()
    }

    @Test
    fun `expired grant denies`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByCardAndParty(cardId, delegate) } returns
            listOf(grant(setOf("CARD_VIEW"), validTo = now.minusDays(1)))
        assertThat(guard.isAuthorized(cardId, delegate, CardDelegationIntent.VIEW)).isFalse()
    }

    @Test
    fun `closed row denies`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByCardAndParty(cardId, delegate) } returns
            listOf(grant(setOf("CARD_VIEW")).copy(active = false))
        assertThat(guard.isAuthorized(cardId, delegate, CardDelegationIntent.VIEW)).isFalse()
    }

    @Test
    fun `unknown card denies`(): Unit = runBlocking {
        coEvery { cardRepository.findById(cardId) } returns null
        assertThat(guard.isAuthorized(cardId, delegate, CardDelegationIntent.VIEW)).isFalse()
    }

    @Test
    fun `no grant denies`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByCardAndParty(cardId, delegate) } returns emptyList()
        assertThat(guard.isAuthorized(cardId, delegate, CardDelegationIntent.VIEW)).isFalse()
    }
}
