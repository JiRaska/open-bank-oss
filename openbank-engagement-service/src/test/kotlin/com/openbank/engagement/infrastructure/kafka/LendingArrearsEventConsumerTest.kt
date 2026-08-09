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

class LendingArrearsEventConsumerTest {

    private val adverseState = mockk<AdverseStateRepository>(relaxed = true)
    private val consumer = LendingArrearsEventConsumer(adverseState, ObjectMapper())

    @Test
    fun `a positive daysPastDue sets ARREARS active`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        consumer.consume(
            """{"eventType":"loan.stage_changed","loanId":"${UUID.randomUUID()}","partyId":"$partyId",""" +
                """"previousStage":"STAGE_1","newStage":"STAGE_2","daysPastDue":45,""" +
                """"period":"2026-08","asOf":"2026-08-07T00:00:00Z"}""",
        )
        coVerify { adverseState.setActive(partyId, AdverseState.ARREARS, any()) }
    }

    @Test
    fun `daysPastDue back to zero clears ARREARS`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        consumer.consume(
            """{"eventType":"loan.stage_changed","loanId":"${UUID.randomUUID()}","partyId":"$partyId",""" +
                """"previousStage":"STAGE_2","newStage":"STAGE_1","daysPastDue":0,""" +
                """"period":"2026-08","asOf":"2026-08-07T00:00:00Z"}""",
        )
        coVerify { adverseState.clearActive(partyId, AdverseState.ARREARS) }
    }

    @Test
    fun `an unrelated event type is ignored`(): Unit = runBlocking {
        consumer.consume("""{"eventType":"loan.disbursed","loanId":"${UUID.randomUUID()}"}""")
        coVerify(exactly = 0) { adverseState.setActive(any(), any(), any()) }
        coVerify(exactly = 0) { adverseState.clearActive(any(), any()) }
    }

    @Test
    fun `malformed payload is acked without applying anything or throwing`(): Unit = runBlocking {
        consumer.consume("not json")
        consumer.consume("""{"eventType":"loan.stage_changed"}""") // no partyId
        consumer.consume(
            """{"eventType":"loan.stage_changed","partyId":"${UUID.randomUUID()}"}""",
        ) // no daysPastDue
        coVerify(exactly = 0) { adverseState.setActive(any(), any(), any()) }
        coVerify(exactly = 0) { adverseState.clearActive(any(), any()) }
    }
}
