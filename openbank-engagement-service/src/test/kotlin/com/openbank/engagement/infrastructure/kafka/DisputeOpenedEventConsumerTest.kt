// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.engagement.application.port.out.AdverseStateRepository
import com.openbank.engagement.domain.model.AdverseState
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The payloads below are copied from `DisputeService.openedOutboxMessage` /
 * `resolvedOutboxMessage` in `openbank-dispute-service` (the hand-built JSON strings that are the
 * actual wire format), not invented — a consumer test written against a payload the producer never
 * emits is green about nothing.
 */
class DisputeOpenedEventConsumerTest {

    private val adverseState = mockk<AdverseStateRepository>(relaxed = true)
    private val consumer = DisputeOpenedEventConsumer(adverseState, ObjectMapper())

    private fun opened(partyId: UUID) =
        """{"eventType":"dispute.opened","disputeId":"${UUID.randomUUID()}","reference":"DSP-1",""" +
            """"partyId":"$partyId","disputeType":"UNAUTHORISED_TRANSACTION","status":"OPEN",""" +
            """"openedAt":"2026-08-09T10:00:00Z"}"""

    private fun resolved(partyId: UUID) =
        """{"eventType":"dispute.resolved","disputeId":"${UUID.randomUUID()}","reference":"DSP-1",""" +
            """"partyId":"$partyId","outcome":"UPHELD","status":"RESOLVED",""" +
            """"resolvedAt":"2026-08-09T12:00:00Z","occurredAt":"2026-08-09T12:00:00Z"}"""

    @Test
    fun `dispute opened sets DISPUTE_OPENED active`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        consumer.consume(opened(partyId))
        coVerify(exactly = 1) { adverseState.setActive(partyId, AdverseState.DISPUTE_OPENED, any()) }
    }

    @Test
    fun `dispute resolved lifts the exclusion`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        consumer.consume(resolved(partyId))
        coVerify(exactly = 1) { adverseState.clearActive(partyId, AdverseState.DISPUTE_OPENED) }
        coVerify(exactly = 0) { adverseState.setActive(any(), any(), any()) }
    }

    @Test
    fun `another event type on the shared topic is ignored`(): Unit = runBlocking {
        // openbank.dispute.events carries three event types; only two of them are ours.
        consumer.consume(
            """{"eventType":"dispute.remediation_requested","partyId":"${UUID.randomUUID()}",""" +
                """"amount":"100.00","currency":"CZK"}""",
        )
        coVerify(exactly = 0) { adverseState.setActive(any(), any(), any()) }
        coVerify(exactly = 0) { adverseState.clearActive(any(), any()) }
    }

    @Test
    fun `malformed payload is acked without applying anything or throwing`(): Unit = runBlocking {
        consumer.consume("not json")
        consumer.consume("""{"eventType":"dispute.opened"}""") // no partyId
        consumer.consume("""{"eventType":"dispute.opened","partyId":"not-a-uuid"}""")
        coVerify(exactly = 0) { adverseState.setActive(any(), any(), any()) }
        coVerify(exactly = 0) { adverseState.clearActive(any(), any()) }
    }
}
