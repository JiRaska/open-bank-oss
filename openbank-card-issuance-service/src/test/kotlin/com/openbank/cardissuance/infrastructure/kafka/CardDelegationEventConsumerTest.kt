// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private lateinit var consumer: CardDelegationEventConsumer

    private val grantId: UUID = UUID.randomUUID()
    private val cardId: UUID = UUID.randomUUID()
    private val grantee: UUID = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        consumer = CardDelegationEventConsumer(repository, objectMapper)
    }

    private fun event(type: String, resourceType: String = "CARD", resourceId: UUID? = cardId): String =
        objectMapper.writeValueAsString(
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
            ),
        )

    @Test
    fun `DelegationActivated upserts an active projection row`(): Unit = runBlocking {
        consumer.consume(event("DelegationActivated"))
        coVerify {
            repository.upsertActive(
                match<DelegatedCardGrant> {
                    it.id == grantId && it.cardId == cardId && it.active && "CARD_VIEW" in it.capabilities
                },
            )
        }
    }

    @Test
    fun `close events mark the row inactive`(): Unit = runBlocking {
        for (type in listOf("DelegationRevoked", "DelegationSuspended", "DelegationRenounced", "DelegationExpired")) {
            consumer.consume(event(type))
        }
        coVerify(exactly = 4) { repository.closeById(grantId) }
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

    @Test
    fun `poison pill is acked, never retried`(): Unit = runBlocking {
        consumer.consume("not json at all")
        consumer.consume("""{"eventType":"DelegationActivated"}""")
        coVerify(exactly = 0) { repository.upsertActive(any()) }
    }

    @Test
    fun `transient failure is retried then escapes to the DLQ`(): Unit = runBlocking {
        coEvery { repository.upsertActive(any()) } throws IllegalStateException("db blip")
        assertThatThrownBy { runBlocking { consumer.consume(event("DelegationActivated")) } }
            .isInstanceOf(IllegalStateException::class.java)
        coVerify(exactly = 4) { repository.upsertActive(any()) }
    }
}
