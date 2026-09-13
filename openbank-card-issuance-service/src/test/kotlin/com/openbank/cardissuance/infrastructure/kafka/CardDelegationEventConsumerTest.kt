// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.cardissuance.application.port.`in`.CardUseCase
import com.openbank.cardissuance.application.port.out.CardDelegationProjectionRepository
import com.openbank.cardissuance.domain.model.DelegatedCardGrant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class CardDelegationEventConsumerTest {

    private val repository: CardDelegationProjectionRepository = mockk(relaxed = true)
    private val cardUseCase: CardUseCase = mockk(relaxed = true)
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private lateinit var consumer: CardDelegationEventConsumer

    private val grantId: UUID = UUID.randomUUID()
    private val cardId: UUID = UUID.randomUUID()
    private val grantee: UUID = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        consumer = CardDelegationEventConsumer(repository, cardUseCase, objectMapper)
        coEvery { repository.applyClosed(any(), any()) } returns true
    }

    private fun event(
        type: String,
        resourceType: String = "CARD",
        resourceId: UUID? = cardId,
        revision: Long? = 1,
    ): String = objectMapper.writeValueAsString(
        mapOf(
            "eventType" to type,
            "aggregateId" to grantId,
            "grantorPartyId" to UUID.randomUUID(),
            "granteePartyId" to grantee,
            "resourceType" to resourceType,
            "resourceId" to resourceId,
            "capabilities" to listOf("CARD_VIEW", "CARD_MANAGE_LIMITS"),
            "validFrom" to "2026-07-31T12:00:00Z",
            "validTo" to "2027-07-31T12:00:00Z",
            "occurredAt" to "2026-08-01T12:00:00Z",
            "lifecycleRevision" to revision,
        ),
    )

    @Test
    fun `DelegationActivated upserts an active projection row`(): Unit = runBlocking {
        consumer.consume(event("DelegationActivated"))
        coVerify {
            repository.applyActive(
                match<DelegatedCardGrant> {
                    it.id == grantId && it.cardId == cardId && it.active && "CARD_VIEW" in it.capabilities
                },
                1,
            )
        }
    }

    @Test
    fun `close events mark the row inactive`(): Unit = runBlocking {
        for (type in listOf("DelegationRevoked", "DelegationSuspended", "DelegationRenounced", "DelegationExpired")) {
            consumer.consume(event(type))
        }
        coVerify(exactly = 4) { repository.applyClosed(grantId, 1) }
    }

    @Test
    fun `OFFERED and DECLINED never create an enforceable row`(): Unit = runBlocking {
        consumer.consume(event("DelegationOffered"))
        consumer.consume(event("DelegationDeclined"))
        coVerify(exactly = 0) { repository.upsertActive(any()) }
        coVerify(exactly = 0) { repository.closeById(any()) }
    }

    @Test
    fun `non-CARD lifecycle events are ignored`(): Unit = runBlocking {
        consumer.consume(event("DelegationActivated", resourceType = "ACCOUNT"))
        coVerify(exactly = 0) { repository.upsertActive(any()) }
    }

    // ── ADR-0249 D2: revocation reaches the card ───────────────────────────────

    @Test
    fun `an ACCOUNT-scoped revocation still blocks the cards that grant authorised`(): Unit = runBlocking {
        // This is the case the resourceType filter would have swallowed. A "dodatková karta" is
        // authorised by an ACCOUNT grant (the card did not exist when the grant was written), so if
        // the block ran after that filter, the one revocation that must kill the card is the one
        // event the consumer never sees.
        consumer.consume(event("DelegationRevoked", resourceType = "ACCOUNT", resourceId = null))

        coVerify(exactly = 1) { cardUseCase.blockCardsForRevokedGrant(grantId, "DELEGATION_DelegationRevoked") }
    }

    @Test
    fun `renounce and expiry end the card too — the delegate can hand it back`(): Unit = runBlocking {
        consumer.consume(event("DelegationRenounced", resourceType = "ACCOUNT", resourceId = null))
        consumer.consume(event("DelegationExpired", resourceType = "ACCOUNT", resourceId = null))

        coVerify(exactly = 1) { cardUseCase.blockCardsForRevokedGrant(grantId, "DELEGATION_DelegationRenounced") }
        coVerify(exactly = 1) { cardUseCase.blockCardsForRevokedGrant(grantId, "DELEGATION_DelegationExpired") }
    }

    @Test
    fun `a SUSPENDED grant closes the projection but does not block the card`(): Unit = runBlocking {
        consumer.consume(event("DelegationSuspended"))

        // Suspension is reversible; a block is not, and nothing here could tell a bank-frozen card
        // from a customer-frozen one on reinstatement. The delegate's controls still stop at once,
        // because the projection row closes and the edge asks delegation-service on every request.
        coVerify(exactly = 1) { repository.applyClosed(grantId, 1) }
        coVerify(exactly = 0) { cardUseCase.blockCardsForRevokedGrant(any(), any()) }
    }

    @Test
    fun `activation never blocks anything`(): Unit = runBlocking {
        consumer.consume(event("DelegationActivated"))
        coVerify(exactly = 0) { cardUseCase.blockCardsForRevokedGrant(any(), any()) }
    }

    @Test
    fun `poison pill is acked, never retried`(): Unit = runBlocking {
        consumer.consume("not json at all")
        consumer.consume("""{"eventType":"DelegationActivated"}""")
        coVerify(exactly = 0) { repository.upsertActive(any()) }
    }

    @Test
    fun `transient failure is retried then escapes to the DLQ`(): Unit = runBlocking {
        coEvery { repository.applyActive(any(), any()) } throws IllegalStateException("db blip")
        assertThatThrownBy { runBlocking { consumer.consume(event("DelegationActivated")) } }
            .isInstanceOf(IllegalStateException::class.java)
        coVerify(exactly = 4) { repository.applyActive(any(), 1) }
    }

    @Test
    fun `stale close does not trigger irreversible card blocking`(): Unit = runBlocking {
        coEvery { repository.applyClosed(grantId, 1) } returns false

        consumer.consume(event("DelegationRevoked", resourceType = "ACCOUNT"))

        coVerify(exactly = 0) { cardUseCase.blockCardsForRevokedGrant(any(), any()) }
    }
}
