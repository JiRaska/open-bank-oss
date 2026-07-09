// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.anacredit.application.port.out.LoanStageProjectionRepository
import com.openbank.anacredit.domain.model.LoanStageProjection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class LoanStageEventConsumerTest {

    private val projections = mockk<LoanStageProjectionRepository>()
    private val fixedClock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC)
    private val consumer = LoanStageEventConsumer().also {
        it.projections = projections
        it.objectMapper = ObjectMapper()
        it.clock = fixedClock
    }

    @Test
    fun `loan stage_changed applies the projection with the parsed fields`(): Unit = runBlocking {
        val loanId = UUID.randomUUID()
        coEvery { projections.applyIfNewer(any()) } returns true

        consumer.consume(
            """{"eventType":"loan.stage_changed","loanId":"$loanId","previousStage":"STAGE_1",""" +
                """"newStage":"STAGE_2","daysPastDue":40,"period":"2026-07","asOf":"2026-07-01"}""",
        )

        coVerify(exactly = 1) {
            projections.applyIfNewer(
                LoanStageProjection(
                    loanId = loanId,
                    stage = "STAGE_2",
                    daysPastDue = 40,
                    eventTimestamp = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
                    updatedAt = OffsetDateTime.now(fixedClock),
                ),
            )
        }
    }

    @Test
    fun `a stale or duplicate event is a no-op that does not throw`(): Unit = runBlocking {
        val loanId = UUID.randomUUID()
        coEvery { projections.applyIfNewer(any()) } returns false

        consumer.consume(
            """{"eventType":"loan.stage_changed","loanId":"$loanId","previousStage":"STAGE_2",""" +
                """"newStage":"STAGE_1","daysPastDue":0,"period":"2026-05","asOf":"2026-05-01"}""",
        )

        coVerify(exactly = 1) { projections.applyIfNewer(any()) }
    }

    @Test
    fun `other lending event types are ignored`(): Unit = runBlocking {
        consumer.consume(
            """{"eventType":"loan.provisioned","loanId":"${UUID.randomUUID()}","stage":"STAGE_1"}""",
        )

        coVerify(exactly = 0) { projections.applyIfNewer(any()) }
    }

    @Test
    fun `malformed payload is acked without applying a projection or throwing`(): Unit = runBlocking {
        consumer.consume("not json")
        consumer.consume("""{"eventType":"loan.stage_changed"}""") // no loanId
        consumer.consume("""{"eventType":"loan.stage_changed","loanId":"${UUID.randomUUID()}"}""") // no newStage

        coVerify(exactly = 0) { projections.applyIfNewer(any()) }
    }

    @Test
    fun `a repository failure is swallowed so the consumer group is not wedged`(): Unit = runBlocking {
        val loanId = UUID.randomUUID()
        coEvery { projections.applyIfNewer(any()) } throws RuntimeException("db down")

        // Must not throw — the message is acked and the lending stream can replay.
        consumer.consume(
            """{"eventType":"loan.stage_changed","loanId":"$loanId","previousStage":"STAGE_1",""" +
                """"newStage":"STAGE_2","daysPastDue":35,"asOf":"2026-07-01"}""",
        )

        coVerify(exactly = 1) { projections.applyIfNewer(any()) }
    }

    @Test
    fun `a missing asOf falls back to the injected clock rather than failing`(): Unit = runBlocking {
        val loanId = UUID.randomUUID()
        coEvery { projections.applyIfNewer(any()) } returns true

        consumer.consume(
            """{"eventType":"loan.stage_changed","loanId":"$loanId","previousStage":"STAGE_1",""" +
                """"newStage":"STAGE_2","daysPastDue":35}""",
        )

        coVerify(exactly = 1) {
            projections.applyIfNewer(
                match { it.loanId == loanId && it.eventTimestamp == OffsetDateTime.now(fixedClock) },
            )
        }
    }
}
