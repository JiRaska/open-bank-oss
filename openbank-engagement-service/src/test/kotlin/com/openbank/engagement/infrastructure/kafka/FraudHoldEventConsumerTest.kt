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

/**
 * First cover for [FraudHoldEventConsumer] — it had none, which is why the catch-and-ack defect of
 * #5698 lived here undisturbed on the fraud-hold marketing-exclusion signal.
 */
class FraudHoldEventConsumerTest {

    private val adverseState = mockk<AdverseStateRepository>(relaxed = true)
    private val consumer = FraudHoldEventConsumer(adverseState, ObjectMapper())

    private fun holdChanged(partyId: UUID, active: Boolean) =
        """{"eventType":"fraud.hold_changed","partyId":"$partyId","active":$active}"""

    @Test
    fun `an active hold sets FRAUD_HOLD active`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        consumer.consume(holdChanged(partyId, active = true))
        coVerify(exactly = 1) { adverseState.setActive(partyId, AdverseState.FRAUD_HOLD, any()) }
    }

    @Test
    fun `a released hold clears FRAUD_HOLD`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        consumer.consume(holdChanged(partyId, active = false))
        coVerify(exactly = 1) { adverseState.clearActive(partyId, AdverseState.FRAUD_HOLD) }
        coVerify(exactly = 0) { adverseState.setActive(any(), any(), any()) }
    }

    @Test
    fun `malformed payload is acked without applying anything or throwing`(): Unit = runBlocking {
        consumer.consume("not json")
        consumer.consume("""{"eventType":"fraud.hold_changed"}""") // no partyId
        consumer.consume("""{"eventType":"fraud.hold_changed","partyId":"not-a-uuid"}""")
        coVerify(exactly = 0) { adverseState.setActive(any(), any(), any()) }
        coVerify(exactly = 0) { adverseState.clearActive(any(), any()) }
    }

    /**
     * The #5698 half a malformed-payload test can never reach: the write must ESCAPE so the
     * connector dead-letters. Before the fix the same generic catch swallowed both, and an acked
     * message that did no work is indistinguishable from one that succeeded.
     */
    @Test
    fun `a transient write failure is retried and RETHROWN so the connector dead-letters`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { adverseState.setActive(any(), any(), any()) } throws TransientDbFailure()

        assertThrows<TransientDbFailure> {
            runBlocking { consumer.consume(holdChanged(partyId, active = true)) }
        }

        coVerify(exactly = 3) { adverseState.setActive(partyId, AdverseState.FRAUD_HOLD, any()) }
    }
}
