// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.nostro

import com.openbank.treasury.application.port.out.DuplicateStatementException
import com.openbank.treasury.application.port.out.NostroStatementRepository
import com.openbank.treasury.application.port.out.StoredStatement
import com.openbank.treasury.application.usecase.NostroReconciliationService
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.infrastructure.iso20022.Camt053Parser
import com.openbank.treasury.integration.FakeLedgerRead
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

class NostroReconciliationServiceTest {

    /** In memory; [raceWinner] is committed "concurrently" the moment save is attempted. */
    internal class InMemoryStatements : NostroStatementRepository {
        val rows = mutableListOf<StoredStatement>()
        var raceWinner: StoredStatement? = null

        override suspend fun findById(id: UUID) = rows.firstOrNull { it.id == id }
        override suspend fun findByIdempotencyKey(key: String) = rows.firstOrNull { it.idempotencyKey == key }
        override suspend fun findByAccountAndStatementId(iban: String, statementId: String) =
            rows.firstOrNull { it.statement.iban == iban && it.statement.statementId == statementId }

        override suspend fun save(stored: StoredStatement): StoredStatement {
            raceWinner?.let {
                rows += it
                raceWinner = null
                throw DuplicateStatementException(IllegalStateException("23505"))
            }
            rows += stored
            return stored
        }

        override suspend fun statementIdsSince(since: LocalDate) =
            rows.filter { !it.statement.statementDate.isBefore(since) }.map { it.id }
    }

    private val statements = InMemoryStatements()
    private val ledger = FakeLedgerRead()
    private val service = NostroReconciliationService(
        statements,
        ledger,
        mapOf(NostroFixtures.IBAN to "1001", NostroFixtures.EUR_IBAN to "1002"),
        Clock.systemUTC(),
    )
    private val actor = Actor("anna.approver", ActorType.HUMAN)

    private fun stored(xml: ByteArray, key: String = "k-${UUID.randomUUID()}", sha: String = "sha") =
        runBlocking { service.upload(Camt053Parser.parse(xml), sha, key, actor) }

    @Test
    fun `a EUR nostro is compared on native EUR ledger balances, never the CZK base figure`() = runBlocking<Unit> {
        NostroFixtures.eurLedgerLines().forEach { ledger.lines += "1002" to it }
        ledger.balances[Triple("1002", "EUR", LocalDate.parse("2026-09-23"))] = BigDecimal("50000.00")
        ledger.balances[Triple("1002", "EUR", LocalDate.parse("2026-09-25"))] = BigDecimal("57500.00")
        // The same GL's CZK base-currency figures: what the trial balance would have answered.
        // Reading these for a EUR statement is the defect this test exists to catch.
        ledger.balances[Triple("1002", "CZK", LocalDate.parse("2026-09-23"))] = BigDecimal("1257500.00")
        ledger.balances[Triple("1002", "CZK", LocalDate.parse("2026-09-25"))] = BigDecimal("1445937.50")
        val r = service.reconcile(stored(NostroFixtures.eurXml()).id)

        assertThat(ledger.queries).containsExactly(
            "lines:1002:2026-09-24..2026-09-25",
            "balance:1002:EUR:2026-09-23",
            "balance:1002:EUR:2026-09-25",
        )
        assertThat(r.matches).hasSize(2)
        assertThat(r.ledgerOpeningBalance).isEqualByComparingTo("50000.00")
        assertThat(r.ledgerClosingBalance).isEqualByComparingTo("57500.00")
        assertThat(r.openingDifference).isEqualByComparingTo("0")
        assertThat(r.closingDifference).isEqualByComparingTo("0")
        assertThat(r.balanceNotStated).isNull()
        assertThat(r.reconciled).isTrue()
    }

    @Test
    fun `a EUR balance difference is a break in EUR`() = runBlocking<Unit> {
        NostroFixtures.eurLedgerLines().forEach { ledger.lines += "1002" to it }
        ledger.balances[Triple("1002", "EUR", LocalDate.parse("2026-09-23"))] = BigDecimal("50000.00")
        ledger.balances[Triple("1002", "EUR", LocalDate.parse("2026-09-25"))] = BigDecimal("57400.00")
        val r = service.reconcile(stored(NostroFixtures.eurXml()).id)

        assertThat(r.closingDifference).isEqualByComparingTo("100.00")
        assertThat(r.reconciled).isFalse()
    }

    @Test
    fun `a CZK nostro reads its balances through the same native path`() = runBlocking<Unit> {
        NostroFixtures.ledgerLines().forEach { ledger.lines += "1001" to it }
        ledger.balances[Triple("1001", "CZK", NostroFixtures.DATE.minusDays(1))] = BigDecimal("1000000.00")
        ledger.balances[Triple("1001", "CZK", NostroFixtures.DATE)] = BigDecimal("1149958.00")
        val r = service.reconcile(stored(NostroFixtures.xml()).id)

        assertThat(ledger.queries).containsExactly(
            "lines:1001:2026-09-25..2026-09-25",
            "balance:1001:CZK:2026-09-24",
            "balance:1001:CZK:2026-09-25",
        )
        assertThat(r.balanceNotStated).isNull()
        assertThat(r.ledgerOpeningBalance).isEqualByComparingTo("1000000.00")
        assertThat(r.closingDifference).isEqualByComparingTo("5042.00")
        assertThat(r.reconciled).isFalse()
    }

    @Test
    fun `balances are not stated only when the ledger does not hold the GL - and it says so`() = runBlocking<Unit> {
        NostroFixtures.eurLedgerLines().forEach { ledger.lines += "1002" to it }
        ledger.unknownAccounts += "1002"
        val r = service.reconcile(stored(NostroFixtures.eurXml()).id)

        assertThat(r.matches).hasSize(2)
        assertThat(r.ledgerOpeningBalance).isNull()
        assertThat(r.ledgerClosingBalance).isNull()
        assertThat(r.balanceNotStated).isEqualTo("ledger does not hold GL account 1002")
        assertThat(r.reconciled).isNull()
    }

    @Test
    fun `a multi-day statement with quiet first days opens at its OPBD date - no ledger-only item is swallowed`() =
        runBlocking<Unit> {
            // OPBD dated 2026-09-20, every entry on the 25th: the 21st..24th are quiet days on the
            // statement. A ledger line booked on the 22nd that the correspondent never saw must be
            // listed as unmatched — read from the day before the first ENTRY, it would vanish into
            // the opening balance and the statement would falsely reconcile.
            val xml = String(
                NostroFixtures.xml(),
            ).replace("<Dt><Dt>2026-09-24</Dt></Dt>", "<Dt><Dt>2026-09-20</Dt></Dt>")
                .toByteArray()
            NostroFixtures.ledgerLines().take(2).forEach { ledger.lines += "1001" to it }
            val stray = NostroFixtures.line(
                "777.00",
                com.openbank.treasury.domain.model.Side.CREDIT,
                description = "ledger-only on a quiet day",
                date = LocalDate.parse("2026-09-22"),
            )
            ledger.lines += "1001" to stray
            val r = service.reconcile(stored(xml).id)

            assertThat(ledger.queries).containsExactly(
                "lines:1001:2026-09-21..2026-09-25",
                "balance:1001:CZK:2026-09-20",
                "balance:1001:CZK:2026-09-25",
            )
            assertThat(r.unmatchedLedgerLines).containsExactly(stray)
            assertThat(r.reconciled).isFalse()
        }

    @Test
    fun `a ledger entry after the closing balance date is in neither the lines nor the closing balance`() =
        runBlocking<Unit> {
            NostroFixtures.ledgerLines().take(2).forEach { ledger.lines += "1001" to it }
            ledger.lines +=
                "1001" to
                NostroFixtures.line(
                    "5000.00",
                    com.openbank.treasury.domain.model.Side.DEBIT,
                    date = NostroFixtures.DATE.plusDays(1),
                )
            val r = service.reconcile(stored(NostroFixtures.xml()).id)

            assertThat(ledger.queries).containsExactly(
                "lines:1001:2026-09-25..2026-09-25",
                "balance:1001:CZK:2026-09-24",
                "balance:1001:CZK:2026-09-25",
            )
            assertThat(r.unmatchedLedgerLines).isEmpty()
        }

    @Test
    fun `a lost insert race answers as the pre-check would - replay the same bytes, 409 otherwise`() {
        val winner = stored(NostroFixtures.xml(), key = "k1", sha = "same")
        statements.rows.clear()

        statements.raceWinner = winner
        val replay = stored(NostroFixtures.xml(), key = "k1", sha = "same")
        assertThat(replay.id).isEqualTo(winner.id)

        statements.rows.clear()
        statements.raceWinner = winner
        assertThatThrownBy { stored(NostroFixtures.xml(), key = "k2", sha = "other") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("already uploaded")
    }
}
