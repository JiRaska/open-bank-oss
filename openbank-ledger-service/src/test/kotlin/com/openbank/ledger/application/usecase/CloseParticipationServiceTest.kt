// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.`in`.ReportParticipationCommand
import com.openbank.ledger.application.port.`in`.WaiveParticipationCommand
import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.application.port.out.AppendGuard
import com.openbank.ledger.application.port.out.AppendVerdict
import com.openbank.ledger.application.port.out.CloseParticipationRepository
import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.CloseParticipationRecord
import com.openbank.ledger.domain.model.LedgerConflictException
import com.openbank.ledger.domain.model.LedgerValidationException
import com.openbank.ledger.domain.model.ParticipationOutcome
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class CloseParticipationServiceTest {

    private val day = LocalDate.of(2026, 10, 16)
    private val repository = mockk<CloseParticipationRepository>()
    private val days = mockk<AccountingDayRepository>()

    private val config = object : CloseParticipantsConfig {
        override fun participantsMode() = CloseParticipantsConfig.MODE_ENFORCE
        override fun participants(): Map<String, CloseParticipantsConfig.Participant> = mapOf(
            "interest" to object : CloseParticipantsConfig.Participant {
                override fun principal() = "service-account-openbank-interest"
            },
        )
    }

    private val service = CloseParticipationService(
        config,
        repository,
        days,
        SimpleMeterRegistry(),
        Clock.fixed(Instant.parse("2026-10-17T01:30:00Z"), ZoneOffset.UTC),
    )

    init {
        coEvery { days.findByDate(any()) } returns null
        coEvery { repository.findForDate(any()) } returns emptyList()
        // An honest stand-in for the repository's atomic append: it reads the participant's latest row
        // and the day status exactly as the real one does, then applies the service's own guard.
        coEvery { repository.appendGuarded(any(), any()) } coAnswers {
            val record = firstArg<CloseParticipationRecord>()
            val guard = secondArg<AppendGuard>()
            val latest = repository.findForDate(record.businessDate)
                .filter { it.participant == record.participant }
                .maxByOrNull { it.reportedAt }
            when (val verdict = guard.check(latest, days.findByDate(record.businessDate)?.status)) {
                AppendVerdict.Append -> record
                AppendVerdict.KeepExisting -> latest!!
                is AppendVerdict.Reject -> throw verdict.error
            }
        }
    }

    private fun command(
        principal: String?,
        outcome: ParticipationOutcome = ParticipationOutcome.RAN,
        reason: String? = null,
    ) = ReportParticipationCommand(day, "interest", outcome, 2, 5, reason, principal)

    @Test
    fun `the declared principal may report its participant`(): Unit = runBlocking {
        val saved = service.report(command("service-account-openbank-interest"))
        assertThat(saved.outcome).isEqualTo(ParticipationOutcome.RAN)
        assertThat(saved.late).isFalse()
    }

    @Test
    fun `another service-account, or a token that is not one, is refused before anything is stored`(): Unit =
        runBlocking {
            assertThatThrownBy { runBlocking { service.report(command("service-account-openbank-billing")) } }
                .isInstanceOf(CloseParticipantForbiddenException::class.java)
            assertThatThrownBy { runBlocking { service.report(command(null)) } }
                .isInstanceOf(CloseParticipantForbiddenException::class.java)
            coVerify(exactly = 0) { repository.appendGuarded(any(), any()) }
        }

    @Test
    fun `an undeclared participant is not found and a participant cannot waive itself`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { service.report(command("x").copy(participant = "lending")) }
        }.isInstanceOf(CloseParticipantNotDeclaredException::class.java)
        assertThatThrownBy {
            runBlocking {
                service.report(command("service-account-openbank-interest", ParticipationOutcome.WAIVED, "x"))
            }
        }.isInstanceOf(LedgerValidationException::class.java)
    }

    @Test
    fun `a report into a tied-out day is stored as late`(): Unit = runBlocking {
        coEvery { days.findByDate(day) } returns
            AccountingDayRecord.open(
                day,
                Instant.parse("2026-10-15T22:00:00Z"),
                "s",
            ).copy(status = AccountingDayStatus.TIED_OUT)
        val saved = service.report(command("service-account-openbank-interest"))
        assertThat(saved.late).isTrue()
    }

    @Test
    fun `completeness takes the latest report per participant`(): Unit = runBlocking {
        fun row(outcome: ParticipationOutcome, at: String) = CloseParticipationRecord(
            day, "interest", outcome, 0, 0,
            if (outcome ==
                ParticipationOutcome.RAN
            ) {
                null
            } else {
                "r"
            },
            "p", Instant.parse(at), false,
        )
        coEvery { repository.findForDate(day) } returns listOf(
            row(ParticipationOutcome.REFUSED, "2026-10-17T01:00:00Z"),
            row(ParticipationOutcome.RAN, "2026-10-17T02:00:00Z"),
        )
        val c = service.completeness(day)
        assertThat(c.complete).isTrue()
        assertThat(c.reported.getValue("interest").outcome).isEqualTo(ParticipationOutcome.RAN)
    }

    private fun row(outcome: ParticipationOutcome, at: String) = CloseParticipationRecord(
        day, "interest", outcome, 0, 0, if (outcome == ParticipationOutcome.RAN) null else "r", "p",
        Instant.parse(at), false,
    )

    @Test
    fun `a REFUSED report does not make the day complete - interest was not posted`(): Unit = runBlocking {
        coEvery { repository.findForDate(day) } returns
            listOf(row(ParticipationOutcome.REFUSED, "2026-10-17T01:00:00Z"))
        val c = service.completeness(day)
        assertThat(c.missing).isEmpty()
        assertThat(c.refused).containsExactly("interest")
        assertThat(c.complete).isFalse()
    }

    @Test
    fun `RAN, SKIPPED and an operator WAIVED all release the day`(): Unit = runBlocking {
        for (outcome in listOf(ParticipationOutcome.RAN, ParticipationOutcome.SKIPPED, ParticipationOutcome.WAIVED)) {
            coEvery { repository.findForDate(day) } returns listOf(
                row(ParticipationOutcome.REFUSED, "2026-10-17T01:00:00Z"),
                row(outcome, "2026-10-17T02:00:00Z"),
            )
            assertThat(service.completeness(day).complete).describedAs(outcome.name).isTrue()
        }
    }

    private fun waive() = WaiveParticipationCommand(day, "interest", "operator accepts the gap", "op-1")

    private fun cutoffDay() = AccountingDayRecord.open(day, Instant.parse("2026-10-15T22:00:00Z"), "s")
        .copy(status = AccountingDayStatus.CUTOFF)

    @Test
    fun `a waiver on a CUTOFF day with no RAN report is stored, and may supersede a REFUSED one`(): Unit = runBlocking {
        coEvery { days.findByDate(day) } returns cutoffDay()
        coEvery { repository.findForDate(day) } returns
            listOf(row(ParticipationOutcome.REFUSED, "2026-10-17T01:00:00Z"))
        val saved = service.waive(waive())
        assertThat(saved.outcome).isEqualTo(ParticipationOutcome.WAIVED)
        coVerify(exactly = 1) { repository.appendGuarded(match { it.outcome == ParticipationOutcome.WAIVED }, any()) }
    }

    @Test
    fun `a waiver is refused for an unknown day, a future day, and any day not in CUTOFF`(): Unit = runBlocking {
        coEvery { repository.findForDate(day) } returns emptyList()
        // Unknown (never opened) - which is also every future day.
        coEvery { days.findByDate(day) } returns null
        assertThatThrownBy { runBlocking { service.waive(waive()) } }
            .isInstanceOf(AccountingDayNotFoundException::class.java)
        for (status in listOf(AccountingDayStatus.OPEN, AccountingDayStatus.TIED_OUT, AccountingDayStatus.LOCKED)) {
            coEvery { days.findByDate(day) } returns cutoffDay().copy(status = status)
            assertThatThrownBy { runBlocking { service.waive(waive()) } }
                .describedAs(status.name)
                .isInstanceOf(LedgerConflictException::class.java)
        }
    }

    @Test
    fun `a participant that already RAN cannot be waived, and nothing is appended`(): Unit = runBlocking {
        coEvery { days.findByDate(day) } returns cutoffDay()
        coEvery { repository.findForDate(day) } returns listOf(row(ParticipationOutcome.RAN, "2026-10-17T01:00:00Z"))
        assertThatThrownBy { runBlocking { service.waive(waive()) } }
            .isInstanceOf(LedgerConflictException::class.java)
            .hasMessageContaining("RAN")
    }

    @Test
    fun `a participant's non-RAN report does not supersede an operator WAIVED row`(): Unit = runBlocking {
        val waived = row(ParticipationOutcome.WAIVED, "2026-10-17T01:00:00Z")
        coEvery { repository.findForDate(day) } returns listOf(waived)
        for (outcome in listOf(ParticipationOutcome.REFUSED, ParticipationOutcome.SKIPPED)) {
            val answer = service.report(
                command("service-account-openbank-interest", outcome, "resent after restart").copy(journalsPosted = 0),
            )
            assertThat(answer).describedAs(outcome.name).isEqualTo(waived)
        }
        coVerify(exactly = 2) { repository.appendGuarded(any(), any()) }
        // ...and nothing new was written: the guard returned the existing row, the stub never persisted.
    }

    @Test
    fun `a RAN report after a waiver does supersede it - the work was really done`(): Unit = runBlocking {
        coEvery { repository.findForDate(day) } returns listOf(row(ParticipationOutcome.WAIVED, "2026-10-17T01:00:00Z"))
        val saved = service.report(command("service-account-openbank-interest", ParticipationOutcome.RAN))
        assertThat(saved.outcome).isEqualTo(ParticipationOutcome.RAN)
    }

    @Test
    fun `a waiver is judged on the LATEST row - RAN then a later REFUSED is waivable`(): Unit = runBlocking {
        coEvery { days.findByDate(day) } returns cutoffDay()
        coEvery { repository.findForDate(day) } returns listOf(
            row(ParticipationOutcome.RAN, "2026-10-17T01:00:00Z"),
            row(ParticipationOutcome.REFUSED, "2026-10-17T02:00:00Z"),
        )
        assertThat(service.waive(waive()).outcome).isEqualTo(ParticipationOutcome.WAIVED)
    }

    @Test
    fun `participants-mode accepts exactly off, shadow and enforce and fails the boot on anything else`() {
        for (ok in listOf("off", "shadow", "enforce")) {
            assertThat(CloseParticipantsConfig.requireKnownMode(ok)).isEqualTo(ok)
        }
        for (bad in listOf("enforced", "Enforce", "", " shadow", "on")) {
            assertThatThrownBy { CloseParticipantsConfig.requireKnownMode(bad) }
                .describedAs("'$bad'")
                .isInstanceOf(IllegalStateException::class.java)
        }
        val badConfig = object : CloseParticipantsConfig {
            override fun participantsMode() = "enforced"
            override fun participants(): Map<String, CloseParticipantsConfig.Participant> = emptyMap()
        }
        assertThatThrownBy { CloseParticipantsModeValidator(badConfig).onStart(StartupEvent()) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
