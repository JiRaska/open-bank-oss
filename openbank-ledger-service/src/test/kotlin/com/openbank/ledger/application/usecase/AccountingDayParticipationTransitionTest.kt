// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.ledger.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.ledger.application.port.`in`.TransitionAccountingDayCommand
import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.CloseParticipantsPendingException
import com.openbank.ledger.domain.model.ParticipationOutcome
import com.openbank.ledger.infrastructure.rest.CloseParticipantsPendingExceptionMapper
import com.openbank.libs.domain.calendar.AccountingClock
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Pure service-policy proof. The fixture models the repository's null-skip/required-set contract, not PostgreSQL. */
class AccountingDayParticipationTransitionTest {
    private val date = LocalDate.of(2026, 10, 17)
    private val clock = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC)
    private var persisted = 0
    private var requiredAtRepository: Set<String>? = null

    private fun service(
        mode: String,
        outcome: ParticipationOutcome?,
        status: AccountingDayStatus = AccountingDayStatus.CUTOFF,
    ): AccountingDayService {
        val repository = mockk<AccountingDayRepository>()
        val config = mockk<CloseParticipantsConfig>()
        every { config.participantsMode() } returns mode
        every { config.participants() } returns mapOf("interest" to mockk())
        var current = AccountingDayRecord.open(date, clock.instant().minusSeconds(120), "operator")
        while (current.status != status) {
            current = current.transitionTo(current.status.next!!, "operator", clock.instant().minusSeconds(60))
        }
        coEvery { repository.findByDate(date) } returns current
        coEvery { repository.saveTransition(any(), any(), any(), any()) } coAnswers {
            // Faithfully model requireParticipantsComplete: null skips, missing or REFUSED holds.
            val required = arg<Set<String>?>(3)
            requiredAtRepository = required
            if (required != null &&
                "interest" in required &&
                (outcome == null || outcome == ParticipationOutcome.REFUSED)
            ) {
                throw CloseParticipantsPendingException(setOf("interest"))
            }
            persisted++
            firstArg()
        }
        val mapper = mockk<ObjectMapper>()
        every { mapper.writeValueAsString(any()) } returns "{}"
        return AccountingDayService(repository, mapper, AccountingClock.bank(clock), clock, config)
    }

    private fun transition(service: AccountingDayService, to: AccountingDayStatus = AccountingDayStatus.TIED_OUT) =
        runBlocking { service.transition(TransitionAccountingDayCommand(date, to, "operator-sub")) }

    @Test
    fun `enforce rejects manual tie-out with a missing participant`() {
        val service = service("enforce", null)
        assertThatThrownBy { transition(service) }.isInstanceOf(CloseParticipantsPendingException::class.java)
        assertThat(requiredAtRepository).containsExactly("interest")
        assertThat(persisted).isZero()
    }

    @Test
    fun `enforce rejects manual tie-out with REFUSED`() {
        val service = service("enforce", ParticipationOutcome.REFUSED)
        assertThatThrownBy { transition(service) }.isInstanceOf(CloseParticipantsPendingException::class.java)
        assertThat(persisted).isZero()
    }

    @Test
    fun `enforce permits RAN`() = assertAllowed("enforce", ParticipationOutcome.RAN)

    @Test
    fun `enforce permits an explicitly recorded WAIVED outcome`() =
        assertAllowed("enforce", ParticipationOutcome.WAIVED)

    @Test
    fun `enforce permits SKIPPED`() = assertAllowed("enforce", ParticipationOutcome.SKIPPED)

    @Test
    fun `shadow preserves manual tie-out without participants`() = assertAllowed("shadow", null)

    @Test
    fun `off preserves manual tie-out without participants`() = assertAllowed("off", null)

    @Test
    fun `enforce does not require participation for OPEN to CUTOFF`() {
        val service = service("enforce", null, AccountingDayStatus.OPEN)
        assertThat(transition(service, AccountingDayStatus.CUTOFF).status).isEqualTo(AccountingDayStatus.CUTOFF)
        assertThat(requiredAtRepository).isNull()
        assertThat(persisted).isEqualTo(1)
    }

    @Test
    fun `pending participants are a typed HTTP conflict with the existing error body`() {
        val exception = CloseParticipantsPendingException(setOf("interest"))
        CloseParticipantsPendingExceptionMapper().toResponse(exception).use { response ->
            assertThat(response.status).isEqualTo(409)
            assertThat(response.entity).isEqualTo(mapOf("error" to exception.message))
            assertThat(response.mediaType.toString()).isEqualTo("application/json")
        }
    }

    private fun assertAllowed(mode: String, outcome: ParticipationOutcome?) {
        assertThat(transition(service(mode, outcome)).status).isEqualTo(AccountingDayStatus.TIED_OUT)
        assertThat(persisted).isEqualTo(1)
        if (mode == "enforce") {
            assertThat(requiredAtRepository).containsExactly("interest")
        } else {
            assertThat(requiredAtRepository).isNull()
        }
    }
}
