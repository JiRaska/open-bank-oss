// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.engagement.application.port.out.AdverseStateRepository
import com.openbank.engagement.domain.model.AdverseState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class PartyErasureConsumerTest {

    private val adverseState = mockk<AdverseStateRepository>(relaxed = true)
    private val consumer = PartyErasureConsumer(adverseState, ObjectMapper())

    @Test
    fun `PARTY_ERASED sets ERASURE_REQUESTED active`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId","occurredAt":"2026-08-07T00:00:00Z"}""")
        coVerify { adverseState.setActive(partyId, AdverseState.ERASURE_REQUESTED, any()) }
    }

    @Test
    fun `an unrelated event type is ignored`(): Unit = runBlocking {
        consumer.consume("""{"eventType":"PARTY_UPDATED","partyId":"${UUID.randomUUID()}"}""")
        coVerify(exactly = 0) { adverseState.setActive(any(), any(), any()) }
    }

    @Test
    fun `malformed payload is acked without applying anything or throwing`(): Unit = runBlocking {
        consumer.consume("not json")
        consumer.consume("""{"eventType":"PARTY_ERASED"}""") // no partyId
        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"not-a-uuid"}""")
        coVerify(exactly = 0) { adverseState.setActive(any(), any(), any()) }
    }

    /**
     * The #5698 half a malformed-payload test can never reach. This consumer's state is TERMINAL —
     * ADR-0220 D3.5 never re-derives ERASURE_REQUESTED and there is no un-erase event — so a write
     * that was acked without happening leaves the party permanently targetable, with nothing
     * anywhere to notice. The write must escape so the connector dead-letters.
     */
    @Test
    fun `a transient write failure is retried and RETHROWN so the connector dead-letters`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { adverseState.setActive(any(), any(), any()) } throws TransientDbFailure()

        assertThrows<TransientDbFailure> {
            runBlocking { consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""") }
        }

        coVerify(exactly = 3) { adverseState.setActive(partyId, AdverseState.ERASURE_REQUESTED, any()) }
    }
}
