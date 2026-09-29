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
    private class InMemoryStatements : NostroStatementRepository {
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
    fun `a EUR nostro never asks the ledger for a balance and says why none is shown`() = runBlocking<Unit> {
        NostroFixtures.eurLedgerLines().forEach { ledger.lines += "1002" to it }
        val r = service.reconcile(stored(NostroFixtures.eurXml()).id)

        assertThat(ledger.queries).containsExactly("lines:1002:2026-09-24..2026-09-25")
        assertThat(r.matches).hasSize(2)
        assertThat(r.ledgerOpeningBalance).isNull()
        assertThat(r.ledgerClosingBalance).isNull()
        assertThat(r.balanceNotStated).isEqualTo(NostroReconciliationService.BALANCE_NOT_STATED)
        assertThat(r.reconciled).isNull()
    }

    @Test
    fun `a CZK nostro is still compared - both balances read`() = runBlocking<Unit> {
        NostroFixtures.ledgerLines().forEach { ledger.lines += "1001" to it }
        ledger.balances["1001" to NostroFixtures.DATE.minusDays(1)] = BigDecimal("1000000.00")
        ledger.balances["1001" to NostroFixtures.DATE] = BigDecimal("1149958.00")
        val r = service.reconcile(stored(NostroFixtures.xml()).id)

        assertThat(r.balanceNotStated).isNull()
        assertThat(r.closingDifference).isEqualByComparingTo("5042.00")
        assertThat(r.reconciled).isFalse()
    }

    @Test
    fun `a multi-day statement reads the ledger over its whole span, opening the day before the first`() =
        runBlocking<Unit> {
            val day1 = LocalDate.parse("2026-09-23")
            val xml = String(NostroFixtures.xml())
                .replaceFirst("<BookgDt><Dt>2026-09-25</Dt>", "<BookgDt><Dt>2026-09-23</Dt>")
                .toByteArray()
            ledger.lines += "1001" to NostroFixtures.line(
                "250000.00",
                com.openbank.treasury.domain.model.Side.DEBIT,
                tx = NostroFixtures.INBOUND_TX,
                date = day1,
            )
            val r = service.reconcile(stored(xml).id)

            assertThat(ledger.queries).containsExactly(
                "lines:1001:2026-09-23..2026-09-25",
                "balance:1001:2026-09-22",
                "balance:1001:2026-09-25",
            )
            assertThat(r.matches.map { it.entry.bookingDate }).contains(day1)
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
