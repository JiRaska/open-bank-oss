// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.cardissuance.application.port.out.CardRepository
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class PartyEventConsumerTest {

    private val cardRepository = mockk<CardRepository>()
    private lateinit var consumer: PartyEventConsumer

    @BeforeEach
    fun setUp() {
        consumer = PartyEventConsumer().also {
            it.cardRepository = cardRepository
            it.objectMapper = ObjectMapper()
        }
    }

    @Test
    fun `PARTY_ERASED anonymises cardholder PII`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coJustRun { cardRepository.anonymizeByPartyId(partyId) }

        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""")

        coVerify(exactly = 1) { cardRepository.anonymizeByPartyId(partyId) }
    }

    @Test
    fun `non-PARTY_ERASED events are ignored`(): Unit = runBlocking {
        consumer.consume("""{"eventType":"PARTY_CREATED","partyId":"${UUID.randomUUID()}"}""")

        coVerify(exactly = 0) { cardRepository.anonymizeByPartyId(any()) }
    }

    @Test
    fun `malformed payload is acked without throwing`(): Unit = runBlocking {
        consumer.consume("not json")
        consumer.consume("""{"eventType":"PARTY_ERASED"}""") // no partyId

        coVerify(exactly = 0) { cardRepository.anonymizeByPartyId(any()) }
    }

    @Test
    fun `anonymisation failure is swallowed to protect the consumer group`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coJustRun { cardRepository.anonymizeByPartyId(partyId) }

        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""")
    }
}
