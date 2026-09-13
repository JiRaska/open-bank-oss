// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.ledger.application.port.`in`.GetJournalQuery
import com.openbank.ledger.application.port.`in`.GetJournalsByTransactionQuery
import com.openbank.ledger.application.port.`in`.GetSubLedgerBalancesQuery
import com.openbank.ledger.application.port.`in`.JournalLineRequest
import com.openbank.ledger.application.port.`in`.ListJournalsQuery
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.application.port.`in`.ReverseJournalCommand
import com.openbank.ledger.application.port.out.GlAccountRepository
import com.openbank.ledger.application.port.out.JournalRepository
import com.openbank.ledger.application.port.out.YearCloseRepository
import com.openbank.ledger.domain.model.DayLockDecision
import com.openbank.ledger.domain.model.GlAccount
import com.openbank.ledger.domain.model.GlAccountType
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.ledger.domain.model.LedgerValidationException
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
import java.time.ZoneOffset
import java.util.UUID

class LedgerServiceTest {

    private lateinit var journalRepository: JournalRepository
    private lateinit var glAccountRepository: GlAccountRepository
    private lateinit var metrics: DomainMetrics
    private lateinit var yearCloseRepository: YearCloseRepository
    private lateinit var accountingDayLock: AccountingDayLock
    private lateinit var periodFreezeLock: PeriodFreezeLock
    private lateinit var service: LedgerService

    // A fixed clock so every emitted timestamp is deterministic (ADR-0100 Layer 1). Since ADR-0207
    // this is the ONLY clock LedgerService has: it no longer builds Clock.system(Europe/Prague)
    // internally, so a test can no longer be green against a service that disagrees with itself
    // about what day it is.
    private val clock = Clock.fixed(Instant.parse("2026-07-31T09:00:00Z"), ZoneOffset.UTC)

    private val debitAccountId = UUID.fromString("a0000000-0000-0000-0000-000000000001")
    private val creditAccountId = UUID.fromString("a0000000-0000-0000-0000-000000000002")

    // Mirrors Quarkus's managed ObjectMapper (JavaTimeModule + dates-as-ISO-strings, not arrays) so the
    // serialized payloads match what the service emits in production and what balance-service's consumer
    // parses. Used both for the service under test and to read back captured outbox payloads — pinning
    // the AccountBookedChanged JSON field names (ADR-0039 contract).
    private val jsonMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private fun glAccount(id: UUID, code: String, type: GlAccountType, currency: String) = GlAccount(
        id = id,
        code = code,
        name = "Account $code",
        type = type,
        currency = CurrencyCode.of(currency),
        parentId = null,
        isLeaf = true,
        isEnabled = true,
        createdAt = Instant.now(),
    )

    @BeforeEach
    fun setup() {
        journalRepository = mockk()
        glAccountRepository = mockk()
        metrics = mockk(relaxed = true)
        yearCloseRepository = mockk()
        accountingDayLock = mockk(relaxed = true)
        periodFreezeLock = mockk(relaxed = true)
        service = LedgerService(
            journalRepository,
            glAccountRepository,
            jsonMapper,
            metrics,
            yearCloseRepository,
            accountingDayLock,
            periodFreezeLock,
            clock,
        )

        // Default: idempotency miss + persistence echoes the entry back; the fiscal period is open.
        coEvery { journalRepository.findByIdempotencyKey(any()) } returns null
        coEvery { journalRepository.save(any(), any(), any()) } answers { firstArg() }
        coEvery { yearCloseRepository.isFiscalYearAttested(any()) } returns false

        // Day lock neutral by default (ADR-0207): shadow mode, no day rows — these tests are about
        // the posting/reversal behaviour, and AccountingDayLockTest owns the lock's own semantics.
        coEvery { accountingDayLock.requireOpen(any(), any()) } returns Unit
        coEvery { accountingDayLock.evaluate(any(), any()) } answers { DayLockDecision.unknownDay(firstArg()) }
        every { accountingDayLock.enforcing } returns false

        // Period lock neutral by default (ADR-0096): no frozen period covers any test date.
        // PeriodFreezeLockTest owns the lock's own semantics.
        coEvery { periodFreezeLock.requireOpen(any(), any()) } returns Unit
        coEvery { periodFreezeLock.evaluate(any(), any()) } returns null
        every { periodFreezeLock.enforcing } returns false
    }

    private fun mockGlAccounts(currency: String = "CZK") {
        coEvery { glAccountRepository.findById(debitAccountId) } returns
            glAccount(debitAccountId, "1100", GlAccountType.ASSET, currency)
        coEvery { glAccountRepository.findById(creditAccountId) } returns
            glAccount(creditAccountId, "2100", GlAccountType.LIABILITY, currency)
    }

    private fun postCommand(amount: String = "1000.00", currency: String = "CZK") = PostJournalCommand(
        idempotencyKey = UUID.randomUUID().toString(),
        transactionId = UUID.randomUUID(),
        entryDate = LocalDate.of(2026, 1, 15),
        valueDate = LocalDate.of(2026, 1, 15),
        description = "Test posting",
        lines = listOf(
            JournalLineRequest(
                glAccountId = debitAccountId,
                side = JournalSide.DEBIT,
                amount = BigDecimal(amount),
                currencyCode = currency,
                fxRate = null,
                baseAmount = BigDecimal(amount),
                baseCurrencyCode = currency,
            ),
            JournalLineRequest(
                glAccountId = creditAccountId,
                side = JournalSide.CREDIT,
                amount = BigDecimal(amount),
                currencyCode = currency,
                fxRate = null,
                baseAmount = BigDecimal(amount),
                baseCurrencyCode = currency,
            ),
        ),
        postedBy = UUID.randomUUID(),
    )

    private fun postCommandWithSubAccount(
        legAccountId: UUID,
        subAccountId: UUID,
        amount: String = "1000.00",
        currency: String = "CZK",
    ) = postCommand(amount, currency).let { base ->
        base.copy(
            lines = base.lines.map { line ->
                if (line.glAccountId == legAccountId) line.copy(subAccountId = subAccountId) else line
            },
        )
    }

    @Nested
    inner class PostJournal {

        @Test
        fun `posts balanced journal and writes outbox message`(): Unit = runBlocking {
            mockGlAccounts()
            coEvery { journalRepository.nextEntryNumber() } returns 1L

            val command = postCommand()
            val result = service.postJournal(command)

            assertThat(result.status).isEqualTo(JournalStatus.POSTED)
            assertThat(result.entryNumber).isEqualTo(1L)
            assertThat(result.transactionId).isEqualTo(command.transactionId)
            assertThat(result.lines).hasSize(2)
            assertThat(result.description).isEqualTo("Test posting")

            coVerify {
                journalRepository.save(
                    match { it.status == JournalStatus.POSTED },
                    eq(command.idempotencyKey),
                    // No deposit-control leg here, so the outbox carries only JournalPosted.
                    match {
                        it.size == 1 &&
                            it.single().eventType == "JournalPosted" &&
                            it.single().aggregateId == result.id
                    },
                )
            }
        }

        @Test
        fun `persists trusted synthetic taint on posting outbox messages`(): Unit = runBlocking {
            mockGlAccounts()
            coEvery { journalRepository.nextEntryNumber() } returns 1L
            val messages = slot<List<OutboxMessage>>()
            coEvery { journalRepository.save(any(), any(), capture(messages)) } answers { firstArg() }

            service.postJournal(postCommand().copy(synthetic = true))

            assertThat(messages.captured).isNotEmpty().allSatisfy { message ->
                assertThat(message.synthetic).isTrue()
            }
        }

        @Test
        fun `replays original entry on idempotency hit without re-posting`(): Unit = runBlocking {
            val command = postCommand()
            val existing = postedEntry(command.transactionId)
            coEvery { journalRepository.findByIdempotencyKey(command.idempotencyKey) } returns existing

            val result = service.postJournal(command)

            assertThat(result).isEqualTo(existing)
            coVerify(exactly = 0) { journalRepository.save(any(), any(), any()) }
            coVerify(exactly = 0) { journalRepository.nextEntryNumber() }
        }

        @Test
        fun `assigns sequential entry numbers`(): Unit = runBlocking {
            mockGlAccounts()
            coEvery { journalRepository.nextEntryNumber() } returnsMany listOf(1L, 2L, 3L)

            val r1 = service.postJournal(postCommand())
            val r2 = service.postJournal(postCommand())
            val r3 = service.postJournal(postCommand())

            assertThat(r1.entryNumber).isEqualTo(1L)
            assertThat(r2.entryNumber).isEqualTo(2L)
            assertThat(r3.entryNumber).isEqualTo(3L)
        }

        @Test
        fun `creates lines with correct Money values`(): Unit = runBlocking {
            mockGlAccounts("EUR")
            coEvery { journalRepository.nextEntryNumber() } returns 1L

            val result = service.postJournal(postCommand("5000.50", "EUR"))

            val debit = result.lines.first { it.side == JournalSide.DEBIT }
            assertThat(debit.amount.amount).isEqualByComparingTo(BigDecimal("5000.50"))
            assertThat(debit.amount.currency.code).isEqualTo("EUR")
        }

        @Test
        fun `rejects unbalanced lines`(): Unit = runBlocking {
            mockGlAccounts()
            coEvery { journalRepository.nextEntryNumber() } returns 1L

            val command = PostJournalCommand(
                idempotencyKey = UUID.randomUUID().toString(),
                transactionId = UUID.randomUUID(),
                entryDate = LocalDate.now(),
                valueDate = LocalDate.now(),
                description = null,
                lines = listOf(
                    JournalLineRequest(
                        glAccountId = debitAccountId,
                        side = JournalSide.DEBIT,
                        amount = BigDecimal("1000.00"),
                        currencyCode = "CZK",
                        fxRate = null,
                        baseAmount = BigDecimal("1000.00"),
                        baseCurrencyCode = "CZK",
                    ),
                    JournalLineRequest(
                        glAccountId = creditAccountId,
                        side = JournalSide.CREDIT,
                        amount = BigDecimal("500.00"),
                        currencyCode = "CZK",
                        fxRate = null,
                        baseAmount = BigDecimal("500.00"),
                        baseCurrencyCode = "CZK",
                    ),
                ),
                postedBy = UUID.randomUUID(),
            )

            assertThatThrownBy { runBlocking { service.postJournal(command) } }
                .isInstanceOf(LedgerValidationException::class.java)
        }

        @Test
        fun `rejects posting to an unknown GL account`(): Unit = runBlocking {
            coEvery { glAccountRepository.findById(any()) } returns null

            assertThatThrownBy { runBlocking { service.postJournal(postCommand()) } }
                .isInstanceOf(GlAccountValidationException::class.java)
        }

        @Test
        fun `rejects posting to a disabled GL account`(): Unit = runBlocking {
            coEvery { glAccountRepository.findById(debitAccountId) } returns
                glAccount(debitAccountId, "1100", GlAccountType.ASSET, "CZK").copy(isEnabled = false)
            coEvery { glAccountRepository.findById(creditAccountId) } returns
                glAccount(creditAccountId, "2100", GlAccountType.LIABILITY, "CZK")

            assertThatThrownBy { runBlocking { service.postJournal(postCommand()) } }
                .isInstanceOf(GlAccountValidationException::class.java)
        }

        @Test
        fun `rejects posting to a non-leaf GL account`(): Unit = runBlocking {
            coEvery { glAccountRepository.findById(debitAccountId) } returns
                glAccount(debitAccountId, "1100", GlAccountType.ASSET, "CZK").copy(isLeaf = false)
            coEvery { glAccountRepository.findById(creditAccountId) } returns
                glAccount(creditAccountId, "2100", GlAccountType.LIABILITY, "CZK")

            assertThatThrownBy { runBlocking { service.postJournal(postCommand()) } }
                .isInstanceOf(GlAccountValidationException::class.java)
        }

        @Test
        fun `rejects line whose base currency differs from the GL account currency`(): Unit = runBlocking {
            // GL accounts are CZK but the command posts EUR base amounts.
            mockGlAccounts("CZK")
            coEvery { journalRepository.nextEntryNumber() } returns 1L

            assertThatThrownBy { runBlocking { service.postJournal(postCommand("1000.00", "EUR")) } }
                .isInstanceOf(GlAccountValidationException::class.java)
        }

        @Test
        fun `accepts subAccountId on a deposit-control leg`(): Unit = runBlocking {
            mockGlAccounts() // creditAccountId is code 2100 (deposit control)
            coEvery { journalRepository.nextEntryNumber() } returns 1L
            val subAccount = UUID.randomUUID()

            val result = service.postJournal(postCommandWithSubAccount(creditAccountId, subAccount))

            val controlLeg = result.lines.first { it.glAccountId == creditAccountId }
            assertThat(controlLeg.subAccountId).isEqualTo(subAccount)
            // The non-deposit-control leg carries no sub-ledger dimension.
            assertThat(result.lines.first { it.glAccountId == debitAccountId }.subAccountId).isNull()
        }

        @Test
        fun `rejects subAccountId on a non-deposit-control leg`(): Unit = runBlocking {
            mockGlAccounts() // debitAccountId is code 1100 (ASSET, not deposit control)
            coEvery { journalRepository.nextEntryNumber() } returns 1L

            assertThatThrownBy {
                runBlocking { service.postJournal(postCommandWithSubAccount(debitAccountId, UUID.randomUUID())) }
            }.isInstanceOf(GlAccountValidationException::class.java)
        }

        @Test
        fun `emits a JournalPosted and a matching AccountBookedChanged for a deposit-control leg`(): Unit =
            runBlocking {
                mockGlAccounts() // creditAccountId is code 2100 (deposit control, credit-normal)
                coEvery { journalRepository.nextEntryNumber() } returns 1L
                val subAccount = UUID.randomUUID()
                val outbox = slot<List<OutboxMessage>>()
                coEvery { journalRepository.save(any(), any(), capture(outbox)) } answers { firstArg() }

                val command = postCommandWithSubAccount(creditAccountId, subAccount) // credit leg = +delta
                val result = service.postJournal(command)

                val messages = outbox.captured
                assertThat(messages.map { it.eventType })
                    .containsExactlyInAnyOrder("JournalPosted", "AccountBookedChanged")

                val booked = messages.single { it.eventType == "AccountBookedChanged" }
                assertThat(booked.aggregateId).isEqualTo(subAccount)
                // The serialized payload must carry EXACTLY the field names balance-service reads.
                val node = jsonMapper.readTree(booked.payload)
                assertThat(node["eventType"].asText()).isEqualTo("AccountBookedChanged")
                assertThat(node["aggregateType"].asText()).isEqualTo("Account")
                assertThat(node["aggregateId"].asText()).isEqualTo(subAccount.toString())
                assertThat(node["currency"].asText()).isEqualTo("CZK")
                assertThat(BigDecimal(node["delta"].asText())).isEqualByComparingTo("1000.00")
                assertThat(node["journalEntryId"].asText()).isEqualTo(result.id.toString())
                assertThat(node["transactionId"].asText()).isEqualTo(command.transactionId.toString())
                assertThat(node["entryDate"].asText()).isEqualTo("2026-01-15")
                assertThat(node["version"].asLong()).isEqualTo(0L)
            }
    }

    @Nested
    inner class SubLedgerBalances {

        @Test
        fun `delegates to repository and returns sub-ledger balances`(): Unit = runBlocking {
            val asOf = LocalDate.of(2026, 1, 31)
            val subAccount = UUID.randomUUID()
            val balances = listOf(
                com.openbank.ledger.domain.model.SubLedgerBalance(
                    subAccountId = subAccount,
                    currency = "CZK",
                    totalDebit = BigDecimal("40.00"),
                    totalCredit = BigDecimal("100.00"),
                ),
            )
            coEvery { journalRepository.subLedgerBalances(asOf, subAccount) } returns balances

            val result = service.getSubLedgerBalances(GetSubLedgerBalancesQuery(asOf, subAccount))

            assertThat(result).hasSize(1)
            // Deposit control is credit-normal: customer-owed net = credit − debit = 60.00.
            assertThat(result.single().net).isEqualByComparingTo(BigDecimal("60.00"))
            coVerify { journalRepository.subLedgerBalances(asOf, subAccount) }
        }
    }

    @Nested
    inner class ReverseJournal {

        @Test
        fun `reverses posted journal and writes reversal outbox message`(): Unit = runBlocking {
            val original = postedEntry()
            coEvery { journalRepository.findById(original.id) } returns original
            coEvery { journalRepository.nextEntryNumber() } returns 999L
            coEvery { journalRepository.saveReversal(any(), any(), any(), any()) } answers { firstArg() }

            val command = ReverseJournalCommand(
                journalId = original.id,
                reason = "Error correction",
                reversedBy = UUID.randomUUID(),
            )

            val result = service.reverseJournal(command)

            assertThat(result.status).isEqualTo(JournalStatus.REVERSED)
            assertThat(result.id).isEqualTo(original.id)
            assertThat(result.transactionId).isEqualTo(original.transactionId)

            coVerify {
                journalRepository.saveReversal(
                    match { it.reversalOf == original.id && it.status == JournalStatus.POSTED },
                    eq(original.id),
                    eq(original.entryDate),
                    // Original had no deposit-control leg, so the outbox carries only JournalReversed.
                    match { it.size == 1 && it.single().eventType == "JournalReversed" },
                )
            }
        }

        @Test
        fun `throws when journal not found`(): Unit = runBlocking {
            val missingId = UUID.randomUUID()
            coEvery { journalRepository.findById(missingId) } returns null

            val command = ReverseJournalCommand(
                journalId = missingId,
                reason = "Test",
                reversedBy = UUID.randomUUID(),
            )

            assertThatThrownBy { runBlocking { service.reverseJournal(command) } }
                .isInstanceOf(JournalNotFoundException::class.java)
        }

        @Test
        fun `JournalReversed outbox payload references the ORIGINAL journal id and carries the given reason`(): Unit =
            runBlocking {
                // originalJournalId must point at the entry being corrected, not the new reversal
                // entry's own id — a mutant swapping reversal.id in for original.id here would be
                // invisible to downstream consumers only by accident (both are valid UUIDs), and no
                // existing test decodes this field. Same for `reason`: it is operator-supplied
                // free text with no domain validation, so only an explicit payload assertion pins it.
                val original = postedEntry()
                coEvery { journalRepository.findById(original.id) } returns original
                coEvery { journalRepository.nextEntryNumber() } returns 999L
                val outbox = slot<List<OutboxMessage>>()
                coEvery { journalRepository.saveReversal(any(), any(), any(), capture(outbox)) } answers { firstArg() }

                service.reverseJournal(
                    ReverseJournalCommand(
                        journalId = original.id,
                        reason = "Duplicate charge correction",
                        reversedBy = UUID.randomUUID(),
                    ),
                )

                val reversedEvent = outbox.captured.single { it.eventType == "JournalReversed" }
                val node = jsonMapper.readTree(reversedEvent.payload)
                assertThat(node["originalJournalId"].asText()).isEqualTo(original.id.toString())
                assertThat(node["originalJournalId"].asText()).isNotEqualTo(reversedEvent.aggregateId.toString())
                assertThat(node["reason"].asText()).isEqualTo("Duplicate charge correction")
                assertThat(node["transactionId"].asText()).isEqualTo(original.transactionId.toString())
            }

        @Test
        fun `reversal saved to the repository carries its own entry number, not the original's`(): Unit = runBlocking {
            // reverse() itself leaves entryNumber null (the domain has no sequence access); the
            // use case must stamp the value it drew from nextEntryNumber() onto the entry it
            // actually persists — a duplicate/null entryNumber would violate
            // uq_journal_entry_number at the DB. A mutant that dropped the
            // `.copy(entryNumber = ...)` override (or fed back original.entryNumber instead of the
            // freshly drawn one) would still pass every other assertion in this class, since no
            // other test inspects the entryNumber actually handed to saveReversal.
            val original = postedEntry(entryNumber = 42L)
            coEvery { journalRepository.findById(original.id) } returns original
            coEvery { journalRepository.nextEntryNumber() } returns 777L
            val savedReversal = slot<JournalEntry>()
            coEvery { journalRepository.saveReversal(capture(savedReversal), any(), any(), any()) } answers
                { firstArg() }

            service.reverseJournal(
                ReverseJournalCommand(journalId = original.id, reason = "x", reversedBy = UUID.randomUUID()),
            )

            assertThat(savedReversal.captured.entryNumber).isEqualTo(777L)
            assertThat(savedReversal.captured.entryNumber).isNotEqualTo(original.entryNumber)
        }

        @Test
        fun `reversal saved to the repository is stamped with a fresh createdAt, not the original's`(): Unit =
            runBlocking {
                // JournalEntry.reverse() inherits createdAt from the original as a domain-layer
                // placeholder (the domain has zero clock access, ADR-0100 Layer 1); the application
                // layer MUST override it with the injected clock before persisting, or every reversal
                // would carry its original posting's timestamp forever. A mutant that removed the
                // `.copy(createdAt = clock.instant())` override would silently do exactly that, and no
                // other test here would catch it.
                // The original is stamped strictly BEFORE the injected clock, deterministically.
                // This test used to build the original with Instant.now() and Thread.sleep(5) to let a
                // real clock tick past it — which worked only because LedgerService secretly built its
                // own Clock.system(Europe/Prague) instead of taking the injected one. ADR-0207 removed
                // that constructor, so the service now honours the fixed test clock and the sleep-based
                // version would compare a fixed 2026-07-31 stamp against a real "today". Asserting
                // equality with the injected clock is also strictly stronger than isAfter(): it proves
                // the stamp CAME FROM that clock, where isAfter() is satisfied by any later value.
                val original = postedEntry().copy(createdAt = clock.instant().minusSeconds(3600))
                coEvery { journalRepository.findById(original.id) } returns original
                coEvery { journalRepository.nextEntryNumber() } returns 5L
                val savedReversal = slot<JournalEntry>()
                coEvery { journalRepository.saveReversal(capture(savedReversal), any(), any(), any()) } answers
                    { firstArg() }

                service.reverseJournal(
                    ReverseJournalCommand(journalId = original.id, reason = "x", reversedBy = UUID.randomUUID()),
                )

                assertThat(savedReversal.captured.createdAt).isEqualTo(clock.instant())
                assertThat(savedReversal.captured.createdAt).isAfter(original.createdAt)
            }

        @Test
        fun `reversal saved to the repository records the reversing operator as createdBy`(): Unit = runBlocking {
            // reverse(reversalId, reversedBy) sets createdBy = reversedBy in the domain; this pins
            // that the use case actually forwards command.reversedBy (not, say, original.createdBy)
            // through to the entity that gets persisted.
            val original = postedEntry()
            coEvery { journalRepository.findById(original.id) } returns original
            coEvery { journalRepository.nextEntryNumber() } returns 5L
            val reverser = UUID.randomUUID()
            val savedReversal = slot<JournalEntry>()
            coEvery { journalRepository.saveReversal(capture(savedReversal), any(), any(), any()) } answers
                { firstArg() }

            service.reverseJournal(
                ReverseJournalCommand(journalId = original.id, reason = "x", reversedBy = reverser),
            )

            assertThat(savedReversal.captured.createdBy).isEqualTo(reverser)
            assertThat(savedReversal.captured.createdBy).isNotEqualTo(original.createdBy)
        }

        @Test
        fun `throws a reversal conflict when the journal is already REVERSED`(): Unit = runBlocking {
            // Deterministic 409 (#465): a dedicated conflict type, not IllegalStateException —
            // that type has two competing mappers (libs 422 vs service 409, ADR-0049 D4).
            val original = postedEntry().copy(status = JournalStatus.REVERSED)
            coEvery { journalRepository.findById(original.id) } returns original

            val command = ReverseJournalCommand(
                journalId = original.id,
                reason = "Repeat",
                reversedBy = UUID.randomUUID(),
            )

            assertThatThrownBy { runBlocking { service.reverseJournal(command) } }
                .isInstanceOf(JournalReversalConflictException::class.java)
            coVerify(exactly = 0) { journalRepository.saveReversal(any(), any(), any(), any()) }
        }

        @Test
        fun `reversal of a deposit-control posting emits a negated AccountBookedChanged`(): Unit = runBlocking {
            val subAccount = UUID.randomUUID()
            val original = postedEntryWithDepositControlLeg(subAccount) // credit on subAccount = +1000
            coEvery { journalRepository.findById(original.id) } returns original
            coEvery { journalRepository.nextEntryNumber() } returns 999L
            val outbox = slot<List<OutboxMessage>>()
            coEvery { journalRepository.saveReversal(any(), any(), any(), capture(outbox)) } answers { firstArg() }

            service.reverseJournal(
                ReverseJournalCommand(
                    journalId = original.id,
                    reason = "Error correction",
                    reversedBy = UUID.randomUUID(),
                ),
            )

            val messages = outbox.captured
            assertThat(messages.map { it.eventType })
                .containsExactlyInAnyOrder("JournalReversed", "AccountBookedChanged")

            val booked = messages.single { it.eventType == "AccountBookedChanged" }
            val node = jsonMapper.readTree(booked.payload)
            assertThat(node["aggregateId"].asText()).isEqualTo(subAccount.toString())
            assertThat(BigDecimal(node["delta"].asText())).isEqualByComparingTo("-1000.00")
            assertThat(node["transactionId"].asText()).isEqualTo(original.transactionId.toString())
            // The reversal is its own journal entry, so its booked movement dedups under a new id.
            assertThat(node["journalEntryId"].asText()).isNotEqualTo(original.id.toString())
        }
    }

    @Nested
    inner class PeriodLock {

        @Test
        fun `posting into an attested fiscal year is rejected with a closed-period conflict`(): Unit = runBlocking {
            mockGlAccounts()
            // postCommand() is dated 2026-01-15; mark fiscal year 2026 as closed.
            coEvery { yearCloseRepository.isFiscalYearAttested(2026) } returns true

            assertThatThrownBy { runBlocking { service.postJournal(postCommand()) } }
                .isInstanceOf(ClosedFiscalPeriodException::class.java)
                .hasMessageContaining("2026")

            coVerify(exactly = 0) { journalRepository.save(any(), any(), any()) }
        }

        @Test
        fun `reversing an entry whose date lands in an attested year is rejected`(): Unit = runBlocking {
            // The reversal inherits the original's entryDate (2026), so it would post into a locked year.
            val original = postedEntry()
            coEvery { journalRepository.findById(original.id) } returns original
            coEvery { yearCloseRepository.isFiscalYearAttested(2026) } returns true

            assertThatThrownBy {
                runBlocking {
                    service.reverseJournal(
                        ReverseJournalCommand(journalId = original.id, reason = "x", reversedBy = UUID.randomUUID()),
                    )
                }
            }.isInstanceOf(ClosedFiscalPeriodException::class.java).hasMessageContaining("2026")

            coVerify(exactly = 0) { journalRepository.saveReversal(any(), any(), any(), any()) }
        }

        @Test
        fun `idempotent replay returns the original entry even after its year is attested`(): Unit = runBlocking {
            // Replay must stay idempotent: the entry was booked while the year was open; the period
            // check runs only for genuinely new work, so the idempotency hit returns before it.
            val command = postCommand()
            val existing = postedEntry()
            coEvery { journalRepository.findByIdempotencyKey(command.idempotencyKey) } returns existing
            coEvery { yearCloseRepository.isFiscalYearAttested(any()) } returns true

            val result = service.postJournal(command)

            assertThat(result).isEqualTo(existing)
            coVerify(exactly = 0) { journalRepository.save(any(), any(), any()) }
        }
    }

    @Nested
    inner class Metrics {

        @Test
        fun `counts one ledger posting per base currency on a successful post`(): Unit = runBlocking {
            mockGlAccounts("EUR")
            coEvery { journalRepository.nextEntryNumber() } returns 1L

            service.postJournal(postCommand("1000.00", "EUR"))

            // A single-currency entry is one balanced posting pair → exactly one count, type=posting.
            verify(exactly = 1) { metrics.ledgerPosting("EUR", "posting") }
        }

        @Test
        fun `counts one posting per distinct base currency on a multi-currency entry`(): Unit = runBlocking {
            // An FX-style entry that balances independently in EUR and in CZK (ADR-0025): two
            // self-contained posting pairs ⇒ exactly one count per currency.
            val eurDr = UUID.randomUUID()
            val eurCr = UUID.randomUUID()
            val czkDr = UUID.randomUUID()
            val czkCr = UUID.randomUUID()
            coEvery { glAccountRepository.findById(eurDr) } returns glAccount(eurDr, "1100", GlAccountType.ASSET, "EUR")
            coEvery { glAccountRepository.findById(eurCr) } returns glAccount(eurCr, "1190", GlAccountType.ASSET, "EUR")
            coEvery { glAccountRepository.findById(czkDr) } returns glAccount(czkDr, "1200", GlAccountType.ASSET, "CZK")
            coEvery { glAccountRepository.findById(czkCr) } returns glAccount(czkCr, "1290", GlAccountType.ASSET, "CZK")
            coEvery { journalRepository.nextEntryNumber() } returns 1L

            fun line(acc: UUID, side: JournalSide, amount: String, ccy: String) = JournalLineRequest(
                glAccountId = acc,
                side = side,
                amount = BigDecimal(amount),
                currencyCode = ccy,
                fxRate = null,
                baseAmount = BigDecimal(amount),
                baseCurrencyCode = ccy,
            )
            val command = postCommand().copy(
                lines = listOf(
                    line(eurDr, JournalSide.DEBIT, "100.00", "EUR"),
                    line(eurCr, JournalSide.CREDIT, "100.00", "EUR"),
                    line(czkDr, JournalSide.DEBIT, "2500.00", "CZK"),
                    line(czkCr, JournalSide.CREDIT, "2500.00", "CZK"),
                ),
            )

            service.postJournal(command)

            verify(exactly = 1) { metrics.ledgerPosting("EUR", "posting") }
            verify(exactly = 1) { metrics.ledgerPosting("CZK", "posting") }
        }

        @Test
        fun `does not count a posting on idempotent replay`(): Unit = runBlocking {
            val command = postCommand()
            coEvery { journalRepository.findByIdempotencyKey(command.idempotencyKey) } returns
                postedEntry(command.transactionId)

            service.postJournal(command)

            verify(exactly = 0) { metrics.ledgerPosting(any(), any()) }
        }

        @Test
        fun `counts a reversal with the reversal type`(): Unit = runBlocking {
            val original = postedEntry()
            coEvery { journalRepository.findById(original.id) } returns original
            coEvery { journalRepository.nextEntryNumber() } returns 999L
            coEvery { journalRepository.saveReversal(any(), any(), any(), any()) } answers { firstArg() }

            service.reverseJournal(
                ReverseJournalCommand(
                    journalId = original.id,
                    reason = "Error correction",
                    reversedBy = UUID.randomUUID(),
                ),
            )

            verify(exactly = 1) { metrics.ledgerPosting("CZK", "reversal") }
        }
    }

    @Nested
    inner class GetJournal {

        @Test
        fun `returns journal by id`(): Unit = runBlocking {
            val entry = postedEntry()
            coEvery { journalRepository.findById(entry.id) } returns entry

            val result = service.getJournal(GetJournalQuery(entry.id))

            assertThat(result.id).isEqualTo(entry.id)
        }

        @Test
        fun `throws when journal not found`(): Unit = runBlocking {
            val id = UUID.randomUUID()
            coEvery { journalRepository.findById(id) } returns null

            assertThatThrownBy { runBlocking { service.getJournal(GetJournalQuery(id)) } }
                .isInstanceOf(JournalNotFoundException::class.java)
        }

        @Test
        fun `returns journals by transaction id`(): Unit = runBlocking {
            val txId = UUID.randomUUID()
            val entries = listOf(postedEntry(txId), postedEntry(txId))
            coEvery { journalRepository.findByTransactionId(txId) } returns entries

            val result = service.getJournalsByTransaction(GetJournalsByTransactionQuery(txId))

            assertThat(result).hasSize(2)
        }
    }

    @Nested
    inner class ListJournals {

        @Test
        fun `returns paginated results with cursor`(): Unit = runBlocking {
            val entries = (1..3).map { postedEntry(entryNumber = it.toLong(), day = it) }

            // Return 3 entries for limit=2 → hasNextPage=true
            coEvery {
                journalRepository.findByDateRange(any(), any(), eq(3), isNull())
            } returns entries

            val query = ListJournalsQuery(
                fromDate = LocalDate.of(2026, 1, 1),
                toDate = LocalDate.of(2026, 1, 31),
                limit = 2,
            )

            val page = service.listJournals(query)

            assertThat(page.data).hasSize(2)
            assertThat(page.pagination.hasNextPage).isTrue()
            assertThat(page.pagination.nextCursor).isNotNull()
            assertThat(page.pagination.limit).isEqualTo(2)
        }

        @Test
        fun `returns last page without next cursor`(): Unit = runBlocking {
            coEvery {
                journalRepository.findByDateRange(any(), any(), eq(51), isNull())
            } returns listOf(postedEntry())

            val page = service.listJournals(
                ListJournalsQuery(
                    fromDate = LocalDate.of(2026, 1, 1),
                    toDate = LocalDate.of(2026, 12, 31),
                ),
            )

            assertThat(page.data).hasSize(1)
            assertThat(page.pagination.hasNextPage).isFalse()
            assertThat(page.pagination.nextCursor).isNull()
        }
    }

    private fun postedEntry(
        transactionId: UUID = UUID.randomUUID(),
        entryNumber: Long = 1L,
        day: Int = 15,
    ): JournalEntry {
        val journalId = UUID.randomUUID()
        return JournalEntry(
            id = journalId,
            entryNumber = entryNumber,
            transactionId = transactionId,
            entryDate = LocalDate.of(2026, 1, day),
            valueDate = LocalDate.of(2026, 1, day),
            description = "Posted entry",
            status = JournalStatus.POSTED,
            lines = listOf(
                com.openbank.ledger.domain.model.JournalLine(
                    id = UUID.randomUUID(),
                    journalId = journalId,
                    glAccountId = debitAccountId,
                    side = JournalSide.DEBIT,
                    amount = com.openbank.libs.domain.money.Money.of(BigDecimal("100.00"), "CZK"),
                    fxRate = null,
                    baseAmount = com.openbank.libs.domain.money.Money.of(BigDecimal("100.00"), "CZK"),
                    sequence = 1,
                ),
                com.openbank.ledger.domain.model.JournalLine(
                    id = UUID.randomUUID(),
                    journalId = journalId,
                    glAccountId = creditAccountId,
                    side = JournalSide.CREDIT,
                    amount = com.openbank.libs.domain.money.Money.of(BigDecimal("100.00"), "CZK"),
                    fxRate = null,
                    baseAmount = com.openbank.libs.domain.money.Money.of(BigDecimal("100.00"), "CZK"),
                    sequence = 2,
                ),
            ),
            createdAt = Instant.now(),
            createdBy = UUID.randomUUID(),
            version = 0L,
        )
    }

    // A POSTED entry whose credit (deposit-control) leg carries a customer sub-account, so reversing
    // it exercises the AccountBookedChanged negation path.
    private fun postedEntryWithDepositControlLeg(subAccountId: UUID): JournalEntry {
        val journalId = UUID.randomUUID()
        return JournalEntry(
            id = journalId,
            entryNumber = 1L,
            transactionId = UUID.randomUUID(),
            entryDate = LocalDate.of(2026, 1, 15),
            valueDate = LocalDate.of(2026, 1, 15),
            description = "Deposit posting",
            status = JournalStatus.POSTED,
            lines = listOf(
                JournalLine(
                    id = UUID.randomUUID(),
                    journalId = journalId,
                    glAccountId = debitAccountId,
                    side = JournalSide.DEBIT,
                    amount = Money.of(BigDecimal("1000.00"), "CZK"),
                    fxRate = null,
                    baseAmount = Money.of(BigDecimal("1000.00"), "CZK"),
                    sequence = 1,
                ),
                JournalLine(
                    id = UUID.randomUUID(), journalId = journalId,
                    glAccountId = creditAccountId, side = JournalSide.CREDIT,
                    amount = Money.of(BigDecimal("1000.00"), "CZK"),
                    fxRate = null,
                    baseAmount = Money.of(BigDecimal("1000.00"), "CZK"),
                    sequence = 2,
                    subAccountId = subAccountId,
                ),
            ),
            createdAt = Instant.now(),
            createdBy = UUID.randomUUID(),
            version = 0L,
        )
    }
}
