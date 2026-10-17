// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.`in`.ReportParticipationCommand
import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.application.port.out.CloseParticipationRepository
import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.CloseParticipationRecord
import com.openbank.ledger.domain.model.LedgerValidationException
import com.openbank.ledger.domain.model.ParticipationOutcome
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
        coEvery { repository.append(any()) } answers { firstArg() }
        coEvery { days.findByDate(any()) } returns null
    }

    private fun command(principal: String?, outcome: ParticipationOutcome = ParticipationOutcome.RAN, reason: String? = null) =
        ReportParticipationCommand(day, "interest", outcome, 2, 5, reason, principal)

    @Test
    fun `the declared principal may report its participant`(): Unit = runBlocking {
        val saved = service.report(command("service-account-openbank-interest"))
        assertThat(saved.outcome).isEqualTo(ParticipationOutcome.RAN)
        assertThat(saved.late).isFalse()
    }

    @Test
    fun `another service-account, or a token that is not one, is refused before anything is stored`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.report(command("service-account-openbank-billing")) } }
            .isInstanceOf(CloseParticipantForbiddenException::class.java)
        assertThatThrownBy { runBlocking { service.report(command(null)) } }
            .isInstanceOf(CloseParticipantForbiddenException::class.java)
        coVerify(exactly = 0) { repository.append(any()) }
    }

    @Test
    fun `an undeclared participant is not found and a participant cannot waive itself`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { service.report(command("x").copy(participant = "lending")) }
        }.isInstanceOf(CloseParticipantNotDeclaredException::class.java)
        assertThatThrownBy {
            runBlocking { service.report(command("service-account-openbank-interest", ParticipationOutcome.WAIVED, "x")) }
        }.isInstanceOf(LedgerValidationException::class.java)
    }

    @Test
    fun `a report into a tied-out day is stored as late`(): Unit = runBlocking {
        coEvery { days.findByDate(day) } returns
            AccountingDayRecord.open(day, Instant.parse("2026-10-15T22:00:00Z"), "s").copy(status = AccountingDayStatus.TIED_OUT)
        val saved = service.report(command("service-account-openbank-interest"))
        assertThat(saved.late).isTrue()
    }

    @Test
    fun `completeness takes the latest report per participant`(): Unit = runBlocking {
        fun row(outcome: ParticipationOutcome, at: String) = CloseParticipationRecord(
            day, "interest", outcome, 0, 0, if (outcome == ParticipationOutcome.RAN) null else "r", "p", Instant.parse(at), false,
        )
        coEvery { repository.findForDate(day) } returns listOf(
            row(ParticipationOutcome.REFUSED, "2026-10-17T01:00:00Z"),
            row(ParticipationOutcome.RAN, "2026-10-17T02:00:00Z"),
        )
        val c = service.completeness(day)
        assertThat(c.complete).isTrue()
        assertThat(c.reported.getValue("interest").outcome).isEqualTo(ParticipationOutcome.RAN)
    }
}
