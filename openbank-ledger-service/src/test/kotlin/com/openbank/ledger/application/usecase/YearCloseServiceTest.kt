// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.ledger.application.port.`in`.AttestYearCloseCommand
import com.openbank.ledger.application.port.`in`.CreateYearCloseDraftCommand
import com.openbank.ledger.application.port.`in`.GetFiscalYearTrialBalanceQuery
import com.openbank.ledger.application.port.`in`.GetYearCloseQuery
import com.openbank.ledger.application.port.`in`.VerifyYearCloseQuery
import com.openbank.ledger.application.port.out.JournalRepository
import com.openbank.ledger.application.port.out.YearCloseRepository
import com.openbank.ledger.domain.model.FiscalYearTrialBalance
import com.openbank.ledger.domain.model.GlAccountType
import com.openbank.ledger.domain.model.LedgerValidationException
import com.openbank.ledger.domain.model.TrialBalanceLine
import com.openbank.ledger.domain.model.YearCloseRecord
import com.openbank.ledger.domain.model.YearCloseStatus
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class YearCloseServiceTest {

    private lateinit var journalRepository: JournalRepository
    private lateinit var yearCloseRepository: YearCloseRepository
    private lateinit var service: YearCloseService

    // Fixed bank time: 2026-03-01 in Europe/Prague — fiscal year 2025 has ended, 2026 has not.
    private val clock: Clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneId.of("Europe/Prague"))

    private fun line(code: String, type: GlAccountType, debit: String, credit: String) = TrialBalanceLine(
        glAccountId = UUID.nameUUIDFromBytes(code.toByteArray()),
        code = code,
        name = "Account $code",
        type = type,
        currency = "CZK",
        totalDebit = BigDecimal(debit),
        totalCredit = BigDecimal(credit),
    )

    private fun balancedLines() = listOf(
        line("1100", GlAccountType.ASSET, "1000.00", "0.00"),
        line("2100", GlAccountType.LIABILITY, "0.00", "1000.00"),
    )

    private fun unbalancedLines() = listOf(
        line("1100", GlAccountType.ASSET, "1000.00", "0.00"),
        line("2100", GlAccountType.LIABILITY, "0.00", "900.00"),
    )

    private fun draftFor(
        lines: List<TrialBalanceLine>,
        fiscalYear: Int = 2025,
        draftedBy: String? = "maker-sub",
    ): YearCloseRecord = YearCloseRecord.draftOf(
        FiscalYearTrialBalance(fiscalYear, lines),
        Instant.parse("2026-01-05T10:00:00Z"),
        draftedBy = draftedBy,
    )

    @BeforeEach
    fun setup() {
        journalRepository = mockk()
        yearCloseRepository = mockk()
        service = YearCloseService(
            journalRepository,
            yearCloseRepository,
            ObjectMapper().registerModule(JavaTimeModule()),
            clock,
            // ADR-0207: the accounting date is no longer derived from `clock` inside the service.
            AccountingClock.bank(clock),
        )
        coEvery { yearCloseRepository.saveDraft(any()) } answers { firstArg() }
        coEvery { yearCloseRepository.saveAttested(any(), any()) } answers { firstArg() }
    }

    private fun stubPeriodLines(fiscalYear: Int, lines: List<TrialBalanceLine>) {
        coEvery {
            journalRepository.trialBalanceForPeriod(
                LocalDate.of(fiscalYear, 1, 1),
                LocalDate.of(fiscalYear, 12, 31),
            )
        } returns lines
    }

    @Nested
    inner class TrialBalanceQuery {

        @Test
        fun `computes the trial balance bounded to the fiscal year`(): Unit = runBlocking {
            stubPeriodLines(2025, balancedLines())
            val tb = service.getTrialBalance(GetFiscalYearTrialBalanceQuery(2025))
            assertThat(tb.fiscalYear).isEqualTo(2025)
            assertThat(tb.isBalanced).isTrue()
            assertThat(tb.accountCount).isEqualTo(2)
            coVerify(exactly = 1) {
                journalRepository.trialBalanceForPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))
            }
        }

        @Test
        fun `rejects a fiscal year outside the supported range`(): Unit = runBlocking {
            assertThatThrownBy { runBlocking { service.getTrialBalance(GetFiscalYearTrialBalanceQuery(42)) } }
                .isInstanceOf(LedgerValidationException::class.java)
            Unit
        }
    }

    @Nested
    inner class DraftCreation {

        @Test
        fun `creates a fresh DRAFT snapshotting the current trial balance`(): Unit = runBlocking {
            stubPeriodLines(2025, balancedLines())
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns null

            val draft = service.createDraft(CreateYearCloseDraftCommand(2025, draftedBy = "maker-a"))

            assertThat(draft.status).isEqualTo(YearCloseStatus.DRAFT)
            assertThat(draft.fiscalYear).isEqualTo(2025)
            assertThat(draft.contentHash).isEqualTo(FiscalYearTrialBalance(2025, balancedLines()).contentHash())
            coVerify(exactly = 1) { yearCloseRepository.saveDraft(any()) }
        }

        @Test
        fun `records the maker (draftedBy) from the command on a fresh draft`(): Unit = runBlocking {
            stubPeriodLines(2025, balancedLines())
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns null

            val draft = service.createDraft(CreateYearCloseDraftCommand(2025, draftedBy = "maker-a"))

            assertThat(draft.draftedBy).isEqualTo("maker-a")
        }

        @Test
        fun `refreshing an existing DRAFT keeps the record id stable (idempotent per year)`(): Unit = runBlocking {
            val existing = draftFor(balancedLines())
            stubPeriodLines(2025, balancedLines())
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns existing

            val refreshed = service.createDraft(CreateYearCloseDraftCommand(2025, draftedBy = "maker-a"))

            assertThat(refreshed.id).isEqualTo(existing.id)
            assertThat(refreshed.status).isEqualTo(YearCloseStatus.DRAFT)
        }

        @Test
        fun `refreshing a DRAFT updates the maker to the current actor`(): Unit = runBlocking {
            val existing = draftFor(balancedLines(), draftedBy = "original-maker")
            stubPeriodLines(2025, balancedLines())
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns existing

            val refreshed = service.createDraft(CreateYearCloseDraftCommand(2025, draftedBy = "new-maker"))

            assertThat(refreshed.id).isEqualTo(existing.id)
            assertThat(refreshed.draftedBy).isEqualTo("new-maker")
        }

        @Test
        fun `an ATTESTED year is immutable - draft refresh conflicts`(): Unit = runBlocking {
            val attested = draftFor(balancedLines()).attest("op", Instant.parse("2026-02-01T08:00:00Z"))
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns attested

            assertThatThrownBy {
                runBlocking { service.createDraft(CreateYearCloseDraftCommand(2025, draftedBy = "maker-a")) }
            }
                .isInstanceOf(YearCloseConflictException::class.java)
                .hasMessageContaining("ATTESTED")
            Unit
        }

        @Test
        fun `an unbalanced GL can never become a DRAFT`(): Unit = runBlocking {
            stubPeriodLines(2025, unbalancedLines())
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns null

            assertThatThrownBy {
                runBlocking { service.createDraft(CreateYearCloseDraftCommand(2025, draftedBy = "maker-a")) }
            }
                .isInstanceOf(YearCloseConflictException::class.java)
                .hasMessageContaining("does not balance")
            Unit
        }
    }

    @Nested
    inner class Attestation {

        @Test
        fun `attests a DRAFT whose hash matches a fresh computation and emits the outbox event`(): Unit = runBlocking {
            val draft = draftFor(balancedLines())
            stubPeriodLines(2025, balancedLines())
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns draft
            val outboxSlot = slot<OutboxMessage>()
            coEvery { yearCloseRepository.saveAttested(any(), capture(outboxSlot)) } answers { firstArg() }

            // Maker (draftedBy default "maker-sub") != checker ("operator-sub") ⇒ four-eyes satisfied.
            val attested = service.attest(AttestYearCloseCommand(2025, attestedBy = "operator-sub"))

            assertThat(attested.status).isEqualTo(YearCloseStatus.ATTESTED)
            assertThat(attested.attestedBy).isEqualTo("operator-sub")
            assertThat(attested.attestedAt).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"))
            assertThat(outboxSlot.captured.eventType).isEqualTo("YearCloseAttested")
            assertThat(outboxSlot.captured.aggregateId).isEqualTo(draft.id)
            assertThat(outboxSlot.captured.payload)
                .contains("\"fiscalYear\":2025")
                .contains(draft.contentHash)
                .contains("operator-sub")
        }

        @Test
        fun `hash drift since the draft fails closed with a conflict`(): Unit = runBlocking {
            val draft = draftFor(balancedLines())
            // A late posting changed the year's activity after the draft was computed.
            stubPeriodLines(
                2025,
                listOf(
                    line("1100", GlAccountType.ASSET, "2000.00", "0.00"),
                    line("2100", GlAccountType.LIABILITY, "0.00", "2000.00"),
                ),
            )
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns draft

            assertThatThrownBy { runBlocking { service.attest(AttestYearCloseCommand(2025, "op")) } }
                .isInstanceOf(YearCloseConflictException::class.java)
                .hasMessageContaining("changed since the draft")
            coVerify(exactly = 0) { yearCloseRepository.saveAttested(any(), any()) }
            Unit
        }

        @Test
        fun `attesting a missing year close is not found`(): Unit = runBlocking {
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns null
            assertThatThrownBy { runBlocking { service.attest(AttestYearCloseCommand(2025, "op")) } }
                .isInstanceOf(YearCloseNotFoundException::class.java)
            Unit
        }

        @Test
        fun `re-attesting an ATTESTED year conflicts`(): Unit = runBlocking {
            val attested = draftFor(balancedLines()).attest("op", Instant.parse("2026-02-01T08:00:00Z"))
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns attested
            assertThatThrownBy { runBlocking { service.attest(AttestYearCloseCommand(2025, "op")) } }
                .isInstanceOf(YearCloseConflictException::class.java)
                .hasMessageContaining("not DRAFT")
            Unit
        }

        @Test
        fun `an open fiscal year cannot be attested`(): Unit = runBlocking {
            val draft = draftFor(balancedLines(), fiscalYear = 2026)
            coEvery { yearCloseRepository.findByFiscalYear(2026) } returns draft
            assertThatThrownBy { runBlocking { service.attest(AttestYearCloseCommand(2026, "op")) } }
                .isInstanceOf(YearCloseConflictException::class.java)
                .hasMessageContaining("not ended")
            coVerify(exactly = 0) { yearCloseRepository.saveAttested(any(), any()) }
            Unit
        }

        @Test
        fun `four-eyes - a different principal than the maker attests successfully`(): Unit = runBlocking {
            val draft = draftFor(balancedLines(), draftedBy = "maker-sub")
            stubPeriodLines(2025, balancedLines())
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns draft
            val outboxSlot = slot<OutboxMessage>()
            coEvery { yearCloseRepository.saveAttested(any(), capture(outboxSlot)) } answers { firstArg() }

            val attested = service.attest(AttestYearCloseCommand(2025, attestedBy = "checker-sub"))

            assertThat(attested.status).isEqualTo(YearCloseStatus.ATTESTED)
            assertThat(attested.attestedBy).isEqualTo("checker-sub")
            assertThat(outboxSlot.captured.eventType).isEqualTo("YearCloseAttested")
            coVerify(exactly = 1) { yearCloseRepository.saveAttested(any(), any()) }
        }

        @Test
        fun `four-eyes - the maker cannot self-attest (conflict, never persisted)`(): Unit = runBlocking {
            val draft = draftFor(balancedLines(), draftedBy = "maker-sub")
            stubPeriodLines(2025, balancedLines())
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns draft

            assertThatThrownBy {
                runBlocking { service.attest(AttestYearCloseCommand(2025, attestedBy = "maker-sub")) }
            }
                .isInstanceOf(YearCloseConflictException::class.java)
                .hasMessageContaining("Four-eyes")
                .hasMessageContaining("differ from the draft author maker-sub")
            // The control must never let a self-attest reach persistence.
            coVerify(exactly = 0) { yearCloseRepository.saveAttested(any(), any()) }
            Unit
        }

        @Test
        fun `four-eyes - a draft with a null maker fails closed (cannot attest)`(): Unit = runBlocking {
            // A draft created before four-eyes tracking has no recorded author.
            val draft = draftFor(balancedLines(), draftedBy = null)
            stubPeriodLines(2025, balancedLines())
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns draft

            assertThatThrownBy {
                runBlocking { service.attest(AttestYearCloseCommand(2025, attestedBy = "checker-sub")) }
            }
                .isInstanceOf(YearCloseConflictException::class.java)
                .hasMessageContaining("predates four-eyes")
            // Fail-closed: a null author can NEVER silently bypass the control.
            coVerify(exactly = 0) { yearCloseRepository.saveAttested(any(), any()) }
            Unit
        }
    }

    @Nested
    inner class Lookup {

        @Test
        fun `returns the stored record`(): Unit = runBlocking {
            val record = draftFor(balancedLines())
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns record
            assertThat(service.getYearClose(GetYearCloseQuery(2025))).isEqualTo(record)
        }

        @Test
        fun `missing record is not found`(): Unit = runBlocking {
            coEvery { yearCloseRepository.findByFiscalYear(2031) } returns null
            assertThatThrownBy { runBlocking { service.getYearClose(GetYearCloseQuery(2031)) } }
                .isInstanceOf(YearCloseNotFoundException::class.java)
            Unit
        }
    }

    @Nested
    inner class Verification {

        @Test
        fun `reports a match for an attested year whose ledger is unchanged`(): Unit = runBlocking {
            val attested = draftFor(balancedLines()).attest("op", Instant.parse("2026-02-01T08:00:00Z"))
            stubPeriodLines(2025, balancedLines())
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns attested

            val result = service.verify(VerifyYearCloseQuery(2025))

            assertThat(result.fiscalYear).isEqualTo(2025)
            assertThat(result.status).isEqualTo(YearCloseStatus.ATTESTED)
            assertThat(result.matches).isTrue()
            assertThat(result.balanced).isTrue()
            assertThat(result.recordedHash).isEqualTo(result.recomputedHash)
            assertThat(result.recomputedAt).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"))
            // Read-only: verify never flips state or writes.
            coVerify(exactly = 0) { yearCloseRepository.saveAttested(any(), any()) }
            coVerify(exactly = 0) { yearCloseRepository.saveDraft(any()) }
        }

        @Test
        fun `reports drift when the fresh trial balance changed since the record`(): Unit = runBlocking {
            val draft = draftFor(balancedLines())
            // A late posting changed the year's activity after the record's hash was anchored.
            stubPeriodLines(
                2025,
                listOf(
                    line("1100", GlAccountType.ASSET, "2000.00", "0.00"),
                    line("2100", GlAccountType.LIABILITY, "0.00", "2000.00"),
                ),
            )
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns draft

            val result = service.verify(VerifyYearCloseQuery(2025))

            assertThat(result.matches).isFalse()
            assertThat(result.balanced).isTrue()
            assertThat(result.recordedHash).isNotEqualTo(result.recomputedHash)
        }

        @Test
        fun `reports imbalance without throwing`(): Unit = runBlocking {
            val draft = draftFor(balancedLines())
            stubPeriodLines(2025, unbalancedLines())
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns draft

            val result = service.verify(VerifyYearCloseQuery(2025))

            assertThat(result.balanced).isFalse()
            assertThat(result.matches).isFalse()
        }

        @Test
        fun `verifying a missing year close is not found`(): Unit = runBlocking {
            coEvery { yearCloseRepository.findByFiscalYear(2025) } returns null
            assertThatThrownBy { runBlocking { service.verify(VerifyYearCloseQuery(2025)) } }
                .isInstanceOf(YearCloseNotFoundException::class.java)
            Unit
        }
    }
}
