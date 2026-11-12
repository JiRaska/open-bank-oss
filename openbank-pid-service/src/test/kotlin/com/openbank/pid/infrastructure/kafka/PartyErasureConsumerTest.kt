// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pid.application.port.`in`.ErasePartyUseCase
import com.openbank.pid.application.port.`in`.ErasureOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class PartyErasureConsumerTest {

    private val erasure = mockk<ErasePartyUseCase>()
    private val consumer = PartyErasureConsumer(erasure, ObjectMapper())
    private val partyId = UUID.randomUUID()

    @Test
    fun `PARTY_ERASED erases the party`(): Unit = runBlocking {
        coEvery { erasure.erase(partyId) } returns ErasureOutcome.ERASED

        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId","erasedAt":"2026-11-12T10:00:00Z"}""")

        coVerify(exactly = 1) { erasure.erase(partyId) }
    }

    @Test
    fun `other party events and malformed payloads are acked without erasing`(): Unit = runBlocking {
        consumer.consume("""{"eventType":"PARTY_CREATED","partyId":"$partyId"}""")
        consumer.consume("""{"eventType":"PARTY_ERASED"}""")
        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"not-a-uuid"}""")
        consumer.consume("not json")

        coVerify(exactly = 0) { erasure.erase(any()) }
    }

    @Test
    fun `a failing erase is rethrown after the bounded retries so the connector dead-letters it`() {
        coEvery { erasure.erase(partyId) } throws IllegalStateException("db down")

        assertThatThrownBy {
            runBlocking { consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""") }
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
