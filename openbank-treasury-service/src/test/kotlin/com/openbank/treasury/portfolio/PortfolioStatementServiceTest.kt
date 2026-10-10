// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.portfolio

import com.openbank.treasury.application.port.out.DuplicatePortfolioStatementException
import com.openbank.treasury.application.port.out.PortfolioSnapshotMissingException
import com.openbank.treasury.application.port.out.PortfolioStatementRepository
import com.openbank.treasury.application.port.out.StoredPortfolioStatement
import com.openbank.treasury.application.usecase.PortfolioStatementService
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.CfiClassMapping
import com.openbank.treasury.domain.model.CustodyHolding
import com.openbank.treasury.domain.model.CustodyStatement
import com.openbank.treasury.domain.model.Isin
import com.openbank.treasury.infrastructure.iso20022.Semt002Statements
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class PortfolioStatementServiceTest {
    private val date = LocalDate.of(2026, 12, 31)
    private val actor = Actor("anna.approver", ActorType.HUMAN)
    private val classes = CfiClassMapping(
        mapOf("DB" to "DEBT_SECURITY", "ES" to "EQUITY", "CI" to "FUND_UNIT", "CIOJ" to "UCITS_FUND_UNIT"),
    )
    private val repo = InMemoryPortfolioRepository()
    private val clock = Clock.fixed(Instant.parse("2027-01-05T09:00:00Z"), ZoneOffset.UTC)
    private val service = PortfolioStatementService(repo, "pension-co", listOf("PSCO-OWN-0001"), classes, clock)

    private fun fixture(name: String = "pension-co-2026-12-31.xml") =
        requireNotNull(javaClass.getResource("/semt002/$name")).readBytes()

    private fun statement(name: String = "pension-co-2026-12-31.xml") = Semt002Statements.parse(fixture(name))

    @Test
    fun `stores every holding classified by the longest CFI prefix`(): Unit = runBlocking {
        val stored = service.upload(statement(), "sha-a", "k1", actor)

        assertThat(stored.version).isEqualTo(1)
        assertThat(stored.supersedes).isNull()
        assertThat(stored.snapshot.currency).isEqualTo("CZK")
        assertThat(
            stored.snapshot.positions.associate {
                it.isin to it.instrumentClass
            },
        ).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "CZ0001005037" to "DEBT_SECURITY",
                "CZ0001006266" to "DEBT_SECURITY",
                "IE00B4L5Y983" to "UCITS_FUND_UNIT",
                "CZ0008008018" to "EQUITY",
            ),
        )
        assertThat(service.periodEnd(date).id).isEqualTo(stored.id)
        assertThat(stored.uploadedAt).isEqualTo(Instant.parse("2027-01-05T09:00:00Z"))
    }

    @Test
    fun `an unmapped CFI refuses the WHOLE statement, names every gap, and stores nothing`(): Unit = runBlocking {
        val xml = String(fixture()).replace("ESVUFR", "RWSNCA").toByteArray()
        val s = Semt002Statements.parse(xml)
        val unmapped = s.copy(holdings = s.holdings.map { if (it.isin == "CZ0001006266") it.copy(cfi = null) else it })

        assertThatThrownBy { runBlocking { service.upload(unmapped, "sha-u", "ku", actor) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("CZ0008008018 (CFI RWSNCA)")
            .hasMessageContaining("CZ0001006266 (CFI not stated)")
        assertThat(repo.rows).isEmpty()
        assertThatThrownBy { runBlocking { service.periodEnd(date) } }
            .isInstanceOf(PortfolioSnapshotMissingException::class.java)
    }

    @Test
    fun `the unmapped-CFI fixture is refused`() {
        assertThatThrownBy {
            runBlocking { service.upload(statement("pension-co-2026-12-31-unmapped-cfi.xml"), "s", "k", actor) }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("IE00B4L5Y983 (CFI FFICSX)")
    }

    @Test
    fun `no statement for the date is a 409 exception, never an empty snapshot`() {
        assertThatThrownBy { runBlocking { service.periodEnd(date) } }
            .isInstanceOf(PortfolioSnapshotMissingException::class.java)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("2026-12-31")
    }

    @Test
    fun `a statement with no holdings is an EMPTY portfolio, served as such`(): Unit = runBlocking {
        val empty = statement().copy(holdings = emptyList())
        service.upload(empty, "sha-e", "ke", actor)
        assertThat(service.periodEnd(date).snapshot.positions).isEmpty()
    }

    @Test
    fun `re-ingestion is idempotent - same key and same bytes, or same bytes under a new key`(): Unit = runBlocking {
        val first = service.upload(statement(), "sha-a", "k1", actor)
        assertThat(service.upload(statement(), "sha-a", "k1", actor).id).isEqualTo(first.id)
        assertThat(service.upload(statement(), "sha-a", "k2", actor).id).isEqualTo(first.id)
        assertThat(repo.rows).hasSize(1)
    }

    @Test
    fun `the key reused for other bytes is a conflict`() {
        runBlocking { service.upload(statement(), "sha-a", "k1", actor) }
        assertThatThrownBy { runBlocking { service.upload(statement(), "sha-b", "k1", actor) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Idempotency-Key")
    }

    @Test
    fun `a corrected statement supersedes the current version, which is kept`(): Unit = runBlocking {
        val first = service.upload(statement(), "sha-a", "k1", actor)
        val corrected = service.upload(statement("pension-co-2026-12-31-corrected.xml"), "sha-c", "k2", actor)

        assertThat(corrected.version).isEqualTo(2)
        assertThat(corrected.supersedes).isEqualTo(first.id)
        assertThat(service.periodEnd(date).id).isEqualTo(corrected.id)
        val trail = service.versions(date)
        assertThat(trail.map { it.version }).containsExactly(1, 2)
        assertThat(trail.first().supersededBy).isEqualTo(corrected.id)
        assertThat(trail.first().supersededAt).isNotNull()
        assertThat(trail.first().snapshot.positions.first { it.isin == "CZ0001005037" }.valuation)
            .isEqualByComparingTo("9875000.00")
        assertThat(corrected.snapshot.positions.first { it.isin == "CZ0001005037" }.valuation)
            .isEqualByComparingTo("9880000.00")
        // Re-uploading the ORIGINAL bytes now is a correction back, not a replay of version 1.
        assertThat(service.upload(statement(), "sha-a", "k3", actor).version).isEqualTo(3)
    }

    @Test
    fun `a concurrent correction that won the race is a 409, its own bytes a replay`() {
        runBlocking { service.upload(statement(), "sha-a", "k1", actor) }
        repo.failNextSaveWith = DuplicatePortfolioStatementException(RuntimeException("race"))
        assertThatThrownBy {
            runBlocking { service.upload(statement("pension-co-2026-12-31-corrected.xml"), "sha-c", "k2", actor) }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("changed while")
    }

    @Test
    fun `an unconfigured safekeeping account is refused`() {
        val s = statement().copy(safekeepingAccount = "OTHER-1")
        assertThatThrownBy { runBlocking { service.upload(s, "x", "k", actor) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("OTHER-1")
    }

    @Test
    fun `a deployment without an entity refuses uploads and answers 409 to reads`() {
        val bank = PortfolioStatementService(repo, null, emptyList(), classes, clock)
        assertThatThrownBy { runBlocking { bank.upload(statement(), "x", "k", actor) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            runBlocking { bank.periodEnd(date) }
        }.isInstanceOf(PortfolioSnapshotMissingException::class.java)
        assertThat(runBlocking { bank.versions(date) }).isEmpty()
    }

    @Test
    fun `mixed valuation currencies are refused`() {
        val s = statement()
        val mixed = s.copy(
            holdings = s.holdings.mapIndexed { i, h ->
                if (i ==
                    0
                ) {
                    h.copy(valuationCurrency = "EUR")
                } else {
                    h
                }
            },
        )
        assertThatThrownBy { runBlocking { service.upload(mixed, "x", "k", actor) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("one base currency")
    }

    @Test
    fun `a non-positive quantity, a negative valuation or an invalid ISIN is refused`() {
        fun one(h: CustodyHolding) = CustodyStatement("S", "PSCO-OWN-0001", date, listOf(h))
        val ok = CustodyHolding("CZ0001005037", "DBFTFB", BigDecimal.ONE, BigDecimal.TEN, "CZK")
        listOf(
            ok.copy(quantity = BigDecimal.ZERO),
            ok.copy(valuation = BigDecimal("-1")),
            ok.copy(isin = "CZ0001005038"),
        ).forEach { bad ->
            assertThatThrownBy { runBlocking { service.upload(one(bad), "x", "k-${bad.hashCode()}", actor) } }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `ISIN check digits`() {
        assertThat(listOf("CZ0001005037", "IE00B4L5Y983", "US0378331005").all(Isin::isValid)).isTrue()
        assertThat(listOf("CZ0001005038", "cz0001005037", "CZ000100503", "").none(Isin::isValid)).isTrue()
    }

    @Test
    fun `a CFI mapping prefix must be letters`() {
        assertThatThrownBy { CfiClassMapping(mapOf("D1" to "X")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CfiClassMapping(mapOf("DB" to " ")) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a semt_002 without a valuation, or with a figure the store would round, is refused`() {
        val noValue = String(fixture()).replaceFirst(Regex("(?s)<AcctBaseCcyAmts>.*?</AcctBaseCcyAmts>"), "")
        assertThatThrownBy { Semt002Statements.parse(noValue.toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("valuation")
        val tooFine = String(fixture()).replace("9875000.00", "9875000.00001")
        assertThatThrownBy { Semt002Statements.parse(tooFine.toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("decimal places")
    }

    /** Mirrors the store's contract: one CURRENT row per (entity, date), versions kept. */
    private class InMemoryPortfolioRepository : PortfolioStatementRepository {
        val rows = mutableListOf<StoredPortfolioStatement>()
        var failNextSaveWith: Throwable? = null

        override suspend fun findByIdempotencyKey(key: String) = rows.firstOrNull { it.idempotencyKey == key }

        override suspend fun current(entity: String, date: LocalDate) = rows.firstOrNull {
            it.snapshot.entity == entity &&
                it.snapshot.statementDate == date &&
                it.supersededBy == null
        }

        override suspend fun versions(entity: String, date: LocalDate) =
            rows.filter { it.snapshot.entity == entity && it.snapshot.statementDate == date }.sortedBy { it.version }

        override suspend fun save(stored: StoredPortfolioStatement): StoredPortfolioStatement {
            failNextSaveWith?.let {
                failNextSaveWith = null
                throw it
            }
            stored.supersedes?.let { prior ->
                val i = rows.indexOfFirst { it.id == prior && it.supersededBy == null }
                if (i < 0) throw DuplicatePortfolioStatementException(IllegalStateException("stale"))
                rows[i] = rows[i].copy(supersededBy = stored.id, supersededAt = stored.uploadedAt)
            }
            rows += stored
            return stored
        }
    }
}
