// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.close

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class CloseParticipationTest {

    private val day = LocalDate.of(2026, 10, 1)
    private val at = Instant.parse("2026-10-02T01:30:00Z")

    private fun run(job: String, items: Int, journals: Int, refused: Int = 0, reason: String? = null) =
        CloseJobRun(day, job, items, journals, refused, reason, at)

    @Test
    fun `a due job with no run means the day is NOT reported`() {
        val report = CloseParticipationAssembler.assemble(
            day,
            expectedJobs = setOf("accrual", "capitalization"),
            runs = listOf(run("accrual", items = 10, journals = 0)),
        )
        assertThat(report).isNull()
    }

    @Test
    fun `every due job ran - RAN with summed counts, a run for another day does not count`() {
        val report = CloseParticipationAssembler.assemble(
            day,
            expectedJobs = setOf("accrual", "capitalization"),
            runs = listOf(
                run("accrual", items = 10, journals = 0),
                run("capitalization", items = 4, journals = 4),
                CloseJobRun(day.plusDays(1), "accrual", 99, 99, 0, null, at),
            ),
        )
        assertThat(
            report,
        ).isEqualTo(CloseParticipationReport(day, CloseOutcome.RAN, journalsPosted = 4, itemsProcessed = 14))
    }

    @Test
    fun `a closed-day refusal makes the day REFUSED with the job and reason`() {
        val report = CloseParticipationAssembler.assemble(
            day,
            expectedJobs = setOf("capitalization"),
            runs = listOf(run("capitalization", items = 1, journals = 1, refused = 3, reason = "day is LOCKED")),
        )!!
        assertThat(report.outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(report.journalsPosted).isZero()
        assertThat(report.reason).contains("capitalization: 3 posting(s) refused").contains("day is LOCKED")
    }

    @Test
    fun `nothing due is SKIPPED with a reason`() {
        val report = CloseParticipationAssembler.assemble(day, emptySet(), emptyList(), skipReason = "crons off")!!
        assertThat(report.outcome).isEqualTo(CloseOutcome.SKIPPED)
        assertThat(report.reason).isEqualTo("crons off")
    }

    @Test
    fun `a non-RAN report without a reason cannot exist`() {
        assertThatThrownBy { CloseParticipationReport(day, CloseOutcome.SKIPPED) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `only a 409 carrying the closed-day code is a closed-day rejection`() {
        assertThat(AccountingDayClosed.isClosedDayRejection(409, """{"code":"ACCOUNTING_DAY_CLOSED"}""")).isTrue()
        assertThat(AccountingDayClosed.isClosedDayRejection(409, """{"error":"duplicate"}""")).isFalse()
        assertThat(AccountingDayClosed.isClosedDayRejection(400, "ACCOUNTING_DAY_CLOSED")).isFalse()
    }

    @Test
    fun `reporter never fails the caller, counts the failure, and does not resend a delivered report`() {
        val calls = AtomicInteger()
        var fail = true
        val registry = SimpleMeterRegistry()
        val reporter = CloseParticipationReporter("interest", { _, _ ->
            calls.incrementAndGet()
            if (fail) Uni.createFrom().failure(IllegalStateException("ledger down")) else Uni.createFrom().item(Unit)
        }, registry)
        val report = CloseParticipationReport(day, CloseOutcome.RAN, 1, 1)

        assertThat(reporter.report(report).await().atMost(Duration.ofSeconds(1))).isFalse()
        fail = false
        assertThat(reporter.report(report).await().atMost(Duration.ofSeconds(1))).isTrue()
        assertThat(reporter.report(report).await().atMost(Duration.ofSeconds(1))).isTrue()

        assertThat(calls.get()).describedAs("the third, identical report is not re-sent").isEqualTo(2)
        assertThat(
            registry.counter(
                CloseParticipationReporter.METRIC,
                "participant",
                "interest",
                "outcome",
                "ran",
                "delivered",
                "false",
            )
                .count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `a ledger 409 (clock skew) is a failed delivery that is retried, never cached`() {
        val calls = AtomicInteger()
        var conflict = true
        val reporter = CloseParticipationReporter("interest", { _, _ ->
            calls.incrementAndGet()
            if (conflict) {
                Uni.createFrom().failure(jakarta.ws.rs.ClientErrorException(409))
            } else {
                Uni.createFrom().item(Unit)
            }
        }, null)
        val report = CloseParticipationReport(day, CloseOutcome.RAN, 1, 1)

        assertThat(reporter.report(report).await().atMost(Duration.ofSeconds(1)))
            .describedAs("not delivered - the tick reports it as not accepted, so liveness is not refreshed")
            .isFalse()
        conflict = false
        assertThat(reporter.report(report).await().atMost(Duration.ofSeconds(1)))
            .describedAs("the identical report is sent again on the next tick")
            .isTrue()
        assertThat(calls.get()).isEqualTo(2)
    }
}
