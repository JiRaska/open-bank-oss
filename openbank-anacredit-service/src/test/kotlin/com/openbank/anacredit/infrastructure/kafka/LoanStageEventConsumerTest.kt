// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.anacredit.application.port.out.LoanStageProjectionRepository
import com.openbank.anacredit.domain.model.LoanStageProjection
import com.openbank.anacredit.infrastructure.observability.AnaCreditMetricsAdapter
import com.openbank.libs.messaging.EventRetry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A named transient failure, so the tests below never `throw RuntimeException` (detekt). */
private class ProjectionUnavailable(message: String) : RuntimeException(message)

class LoanStageEventConsumerTest {

    private val projections = mockk<LoanStageProjectionRepository>()
    private val fixedClock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC)

    // The REAL metrics adapter over a SimpleMeterRegistry, not a mock: the outcome assertions below
    // then fail if the consumer stops reporting an outcome on any of its acked-and-dropped branches.
    private val registry = SimpleMeterRegistry()
    private val consumer = LoanStageEventConsumer().also {
        it.projections = projections
        it.objectMapper = ObjectMapper()
        it.clock = fixedClock
        it.metrics = AnaCreditMetricsAdapter(registry)
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

    /** Unchanged by the #5745 fix, and deliberately so: a malformed event is the genuine poison pill. */
    @Test
    fun `malformed payload is acked without applying a projection or throwing`(): Unit = runBlocking {
        consumer.consume("not json")
        consumer.consume("""{"eventType":"loan.stage_changed"}""") // no loanId
        consumer.consume("""{"eventType":"loan.stage_changed","loanId":"${UUID.randomUUID()}"}""") // no newStage

        coVerify(exactly = 0) { projections.applyIfNewer(any()) }
    }

    /**
     * The assertion this file used to get backwards (#5698/#5745).
     *
     * It previously asserted the OPPOSITE — "a repository failure is swallowed so the consumer group
     * is not wedged" — and passed, which is how the defect shipped with a green test certifying it as
     * intended behaviour. A projection write that fails and acks freezes the loan's IFRS 9 stage and
     * DPD at their previous values, and AnaCredit exposure is a regulatory return, so nothing
     * downstream distinguishes "no transition happened" from "the transition was lost".
     */
    @Test
    fun `a persistent repository failure is RETHROWN after the bounded attempts`(): Unit = runBlocking {
        val loanId = UUID.randomUUID()
        coEvery { projections.applyIfNewer(any()) } throws ProjectionUnavailable("db down")

        assertThrows<ProjectionUnavailable> {
            runBlocking {
                consumer.consume(
                    """{"eventType":"loan.stage_changed","loanId":"$loanId","previousStage":"STAGE_1",""" +
                        """"newStage":"STAGE_2","daysPastDue":35,"asOf":"2026-07-01"}""",
                )
            }
        }

        // Bounded, not unbounded: the partition must move on rather than block through a long outage.
        coVerify(exactly = EventRetry.DEFAULT_MAX_ATTEMPTS) { projections.applyIfNewer(any()) }
        assertThat(outcome("apply_error")).isEqualTo(1.0)
    }

    @Test
    fun `a transient repository failure is retried and then succeeds without throwing`(): Unit = runBlocking {
        val loanId = UUID.randomUUID()
        var calls = 0
        coEvery { projections.applyIfNewer(any()) } answers {
            calls++
            if (calls == 1) throw ProjectionUnavailable("connection refused")
            true
        }

        consumer.consume(
            """{"eventType":"loan.stage_changed","loanId":"$loanId","previousStage":"STAGE_1",""" +
                """"newStage":"STAGE_2","daysPastDue":35,"asOf":"2026-07-01"}""",
        )

        assertThat(calls).isEqualTo(2)
        assertThat(outcome("applied")).isEqualTo(1.0)
        // A recovered retry is a success, not a failure: no apply_error counter is even registered.
        assertThat(registry.find("openbank.anacredit.loan_stage.events").tag("outcome", "apply_error").counter())
            .isNull()
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

    @Test
    fun `every terminal branch reports its outcome to the metrics port`(): Unit = runBlocking {
        val loanId = UUID.randomUUID()
        // After the two seeded answers every later call throws, which is what the retry then exhausts.
        coEvery { projections.applyIfNewer(any()) } returns true andThen
            false andThenThrows ProjectionUnavailable("db down")
        val applied = """{"eventType":"loan.stage_changed","loanId":"$loanId","newStage":"STAGE_2",""" +
            """"daysPastDue":40,"asOf":"2026-07-01"}"""

        consumer.consume(applied) // -> applied
        consumer.consume(applied) // -> stale (applyIfNewer answers false)
        // apply_error now RETHROWS after the bounded retries (see above), so the outcome has to be
        // collected from a throwing call rather than a silently-swallowed one.
        assertThrows<ProjectionUnavailable> { runBlocking { consumer.consume(applied) } }
        consumer.consume("""{"eventType":"loan.provisioned","loanId":"$loanId"}""") // -> ignored
        consumer.consume("not json") // -> parse_error
        consumer.consume("""{"eventType":"loan.stage_changed"}""") // -> malformed (no loanId)
        consumer.consume("""{"eventType":"loan.stage_changed","loanId":"$loanId"}""") // -> malformed (no newStage)

        assertThat(outcome("applied")).isEqualTo(1.0)
        assertThat(outcome("stale")).isEqualTo(1.0)
        assertThat(outcome("apply_error")).isEqualTo(1.0)
        assertThat(outcome("ignored")).isEqualTo(1.0)
        assertThat(outcome("parse_error")).isEqualTo(1.0)
        assertThat(outcome("malformed")).isEqualTo(2.0)
    }

    private fun outcome(value: String): Double = registry.get("openbank.anacredit.loan_stage.events")
        .tag("service", "anacredit")
        .tag("outcome", value)
        .counter().count()
}
