// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.interest.infrastructure.scheduler

import com.openbank.interest.application.port.`in`.AccrualRunOutcome
import com.openbank.interest.application.port.`in`.AccrueInterestUseCase
import com.openbank.interest.application.port.`in`.CapitalizationRunOutcome
import com.openbank.interest.application.port.`in`.CapitalizeInterestUseCase
import com.openbank.interest.application.port.out.AccountDirectoryPort
import com.openbank.interest.application.usecase.InterestService
import com.openbank.libs.close.CloseOutcome
import com.openbank.libs.close.CloseParticipationAssembler
import com.openbank.libs.close.CloseParticipationReport
import com.openbank.libs.observability.DomainMetrics
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class InterestCloseExportPrivacyTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.of(2026, 10, 1)
    private val hostile = "customer=SYNTHETIC_CUSTOMER account=SYNTHETIC_ACCOUNT raw=%s%25\r\nSYNTHETIC_EXCEPTION"

    @Test
    fun `new accrual evidence and warnings never export automatic failure details`() {
        val outcomes = listOf(
            AccrualRunOutcome(4, 1, 0, hostile),
            AccrualRunOutcome(4, 0, 2, hostile),
            AccrualRunOutcome(0, 0, 0, hostile, listingFailed = true),
        )
        for (outcome in outcomes) {
            val useCase = mockk<AccrueInterestUseCase>()
            every { useCase.accrueAllWithOutcome(day) } returns Uni.createFrom().item(outcome)
            val runs = RecordingCloseJobRuns()
            CapturedLogs(InterestAccrualScheduler::class.java.name).use { logs ->
                InterestAccrualScheduler(useCase, clock, mockk<DomainMetrics>(), runs)
                    .runDailyAccrual().await().indefinitely()
                val run = runs.runs.getValue(day to InterestCloseJobs.ACCRUAL)
                assertThat(run.itemsProcessed).isEqualTo(outcome.accrued)
                assertThat(run.refused).isEqualTo(
                    outcome.failed + outcome.unavailable + if (outcome.listingFailed) 1 else 0,
                )
                assertPrivate(run.refusalReason)
                assertPrivate(report(runs, InterestCloseJobs.ACCRUAL).reason)
                assertThat(logs.messages).isNotEmpty()
                assertPrivate(logs.messages.joinToString(" | "))
            }
        }
    }

    @Test
    fun `new capitalization evidence and warnings omit closed-day and other raw reasons`() {
        val useCase = mockk<CapitalizeInterestUseCase>()
        every { useCase.capitalizeAllWithOutcome(day) } returns Uni.createFrom().item(
            CapitalizationRunOutcome(4, 2, hostile, failed = 3, failureReason = hostile),
        )
        val runs = RecordingCloseJobRuns()
        CapturedLogs(InterestCapitalizationScheduler::class.java.name).use { logs ->
            InterestCapitalizationScheduler(useCase, clock, mockk<DomainMetrics>(), runs)
                .runMonthlyCapitalization().await().indefinitely()
            val run = runs.runs.getValue(day to InterestCloseJobs.CAPITALIZATION)
            assertThat(run.itemsProcessed).isEqualTo(4)
            assertThat(run.journalsPosted).isEqualTo(4)
            assertThat(run.refused).isEqualTo(5)
            assertPrivate(run.refusalReason)
            assertPrivate(report(runs, InterestCloseJobs.CAPITALIZATION).reason)
            assertThat(logs.messages).hasSize(2)
            assertPrivate(logs.messages.joinToString(" | "))
        }
    }

    @Test
    fun `the new account-listing failure log omits the raw throwable but preserves failure outcome`() {
        val directory = mockk<AccountDirectoryPort>()
        every { directory.listActiveAccounts(null, any()) } returns
            Uni.createFrom().failure(IllegalStateException(hostile))
        val service =
            InterestService(mockk(), mockk(), mockk(), mockk(), mockk(), directory, "ACT_365", "SAVINGS", clock)
        CapturedLogs(InterestService::class.java.name).use { logs ->
            val outcome = service.accrueAllWithOutcome(day).await().indefinitely()
            assertThat(outcome.listingFailed).isTrue()
            assertThat(outcome.complete).isFalse()
            assertThat(outcome.accrued).isZero()
            assertThat(outcome.failed).isZero()
            assertThat(outcome.unavailable).isZero()
            assertThat(logs.messages).hasSize(1)
            assertPrivate(logs.messages.single())
        }
    }

    private fun report(runs: RecordingCloseJobRuns, job: String): CloseParticipationReport {
        val report = checkNotNull(CloseParticipationAssembler.assemble(day, setOf(job), runs.runs.values))
        assertThat(report.outcome).isEqualTo(CloseOutcome.REFUSED)
        return report
    }

    private fun assertPrivate(value: String?) {
        assertThat(
            value,
        ).isNotNull().doesNotContain(
            "SYNTHETIC_CUSTOMER",
            "SYNTHETIC_ACCOUNT",
            "SYNTHETIC_EXCEPTION",
            "%s%25",
            "\r",
            "\n",
        )
    }

    /** Capture actual logging arguments and throwable causes, not only the message format string. */
    private class CapturedLogs(name: String) : AutoCloseable {
        val messages = CopyOnWriteArrayList<String>()
        private val logger = Logger.getLogger(name)
        private val oldLevel = logger.level
        private val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level.intValue() < Level.WARNING.intValue()) return
                messages += record.message + " " + (record.parameters?.joinToString(" ") ?: "") + " " +
                    generateSequence(record.thrown) { it.cause }.joinToString(" ") { it.message ?: "" }
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        init {
            logger.level = Level.ALL
            logger.addHandler(handler)
        }
        override fun close() {
            logger.removeHandler(handler)
            logger.level = oldLevel
        }
    }
}
