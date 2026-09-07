// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.ledger.application.port.`in`.JournalLineRequest
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.application.port.out.GlAccountRepository
import com.openbank.ledger.application.port.out.JournalRepository
import com.openbank.ledger.application.port.out.YearCloseRepository
import com.openbank.ledger.domain.model.ControlAccountTieOut
import com.openbank.ledger.domain.model.GlAccount
import com.openbank.ledger.domain.model.GlAccountType
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.LedgerScope
import com.openbank.ledger.domain.model.SubLedgerBalance
import com.openbank.ledger.domain.model.TrialBalanceLine
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Idempotency property test (ADR-0011, issue #469 item 5): applying the same command N times
 * must be observably equivalent to applying it once. [LedgerServiceTest]'s "replays original
 * entry on idempotency hit" pins one hand-picked case with a canned mock return; this suite
 * exercises the REAL sequential replay path ([LedgerService.postJournal]'s
 * `findByIdempotencyKey` fast-path) end-to-end against a stateful in-memory fake, across many
 * random amounts and repeat counts.
 *
 * Honest scope: this is the sequential ("apply, then replay") guarantee only. The concurrent-race
 * path — two submissions racing before either commits, recovered in production via the
 * `ledger_idempotency` unique-constraint catch in [LedgerService.recoverConcurrentReplay] — is
 * infrastructure-layer (Postgres) behavior and isn't reachable from a pure/fake-backed test; it's
 * covered by the service's Testcontainers-backed integration tests instead.
 */
class LedgerServiceIdempotencyPropertyTest {

    private val debitAccountId = UUID.fromString("a0000000-0000-0000-0000-000000000001")
    private val creditAccountId = UUID.fromString("a0000000-0000-0000-0000-000000000002")

    private val amountArb = Arb.long(1L, 999_999_999L).map { BigDecimal(it).movePointLeft(2) }
    private val repeatCountArb = Arb.int(2, 5)

    private class InMemoryJournalRepository : JournalRepository {
        private val byIdempotencyKey = mutableMapOf<String, JournalEntry>()
        private var counter = 0L
        var saveCount: Int = 0
            private set

        override suspend fun findById(id: UUID): JournalEntry? = null
        override suspend fun findByTransactionId(transactionId: UUID): List<JournalEntry> = emptyList()

        override suspend fun findByDateRange(
            from: LocalDate,
            to: LocalDate,
            limit: Int,
            afterId: UUID?,
        ): List<JournalEntry> = emptyList()

        override suspend fun findByIdempotencyKey(idempotencyKey: String): JournalEntry? =
            byIdempotencyKey[idempotencyKey]

        override suspend fun nextEntryNumber(): Long = ++counter

        override suspend fun save(
            entry: JournalEntry,
            idempotencyKey: String?,
            outbox: List<OutboxMessage>,
        ): JournalEntry {
            saveCount++
            if (idempotencyKey != null) byIdempotencyKey[idempotencyKey] = entry
            return entry
        }

        override suspend fun saveReversal(
            reversal: JournalEntry,
            originalId: UUID,
            originalEntryDate: LocalDate,
            outbox: List<OutboxMessage>,
        ): JournalEntry = error("not exercised by postJournal")

        override suspend fun appendOutbox(messages: List<OutboxMessage>): Int = error("not exercised by postJournal")

        override suspend fun trialBalance(asOf: LocalDate, scope: LedgerScope): List<TrialBalanceLine> =
            error("not exercised")

        override suspend fun trialBalanceForPeriod(
            from: LocalDate,
            to: LocalDate,
            scope: LedgerScope,
        ): List<TrialBalanceLine> = error("not exercised")

        override suspend fun subLedgerBalances(asOf: LocalDate, subAccountId: UUID?): List<SubLedgerBalance> =
            error("not exercised")

        override suspend fun controlAccountTieOut(controlAccountId: UUID, asOf: LocalDate): List<ControlAccountTieOut> =
            error("not exercised")
    }

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

    private fun postCommand(idempotencyKey: String, amount: BigDecimal) = PostJournalCommand(
        idempotencyKey = idempotencyKey,
        transactionId = UUID.randomUUID(),
        entryDate = LocalDate.of(2026, 1, 15),
        valueDate = LocalDate.of(2026, 1, 15),
        description = "Property test posting",
        lines = listOf(
            JournalLineRequest(
                glAccountId = debitAccountId,
                side = JournalSide.DEBIT,
                amount = amount,
                currencyCode = "CZK",
                fxRate = null,
                baseAmount = amount,
                baseCurrencyCode = "CZK",
            ),
            JournalLineRequest(
                glAccountId = creditAccountId,
                side = JournalSide.CREDIT,
                amount = amount,
                currencyCode = "CZK",
                fxRate = null,
                baseAmount = amount,
                baseCurrencyCode = "CZK",
            ),
        ),
        postedBy = UUID.randomUUID(),
    )

    @Test
    fun `posting the same idempotency key N times is equivalent to posting it once`(): Unit = runBlocking {
        checkAll(amountArb, repeatCountArb) { amount, repeats ->
            val journalRepository = InMemoryJournalRepository()
            val glAccountRepository = mockk<GlAccountRepository>()
            coEvery { glAccountRepository.findById(debitAccountId) } returns
                glAccount(debitAccountId, "1100", GlAccountType.ASSET, "CZK")
            coEvery { glAccountRepository.findById(creditAccountId) } returns
                glAccount(creditAccountId, "2100", GlAccountType.LIABILITY, "CZK")
            val yearCloseRepository = mockk<YearCloseRepository>()
            coEvery { yearCloseRepository.isFiscalYearAttested(any()) } returns false
            val metrics = mockk<DomainMetrics>(relaxed = true)
            val jsonMapper = ObjectMapper()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            val service = LedgerService(
                journalRepository,
                glAccountRepository,
                jsonMapper,
                metrics,
                yearCloseRepository,
                mockk<AccountingDayLock>(relaxed = true),
                mockk<PeriodFreezeLock>(relaxed = true),
                java.time.Clock.fixed(java.time.Instant.parse("2026-07-31T09:00:00Z"), java.time.ZoneOffset.UTC),
            )

            val idempotencyKey = UUID.randomUUID().toString()
            val command = postCommand(idempotencyKey, amount)

            val results = (1..repeats).map { service.postJournal(command) }

            // Every replay returns the exact same entry — same identity, same content.
            assertThat(results.map { it.id }.distinct()).hasSize(1)
            results.forEach { assertThat(it).isEqualTo(results.first()) }

            // N applications have the identical observable persistence effect as one.
            assertThat(journalRepository.saveCount).isEqualTo(1)
        }
    }
}
