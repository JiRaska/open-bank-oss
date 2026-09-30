// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.nostro

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.treasury.application.port.out.NostroAccountNotFoundException
import com.openbank.treasury.application.port.out.NostroBreakRepository
import com.openbank.treasury.application.usecase.NostroBreakService
import com.openbank.treasury.application.usecase.NostroReconciliationService
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.BreakAlertPolicy
import com.openbank.treasury.domain.model.BreakChanges
import com.openbank.treasury.domain.model.NostroBreak
import com.openbank.treasury.domain.model.NostroBreakAged
import com.openbank.treasury.domain.model.Side
import com.openbank.treasury.infrastructure.iso20022.Camt053Parser
import com.openbank.treasury.integration.FakeLedgerRead
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class NostroBreakServiceTest {

    /** In memory; `markAlerted` is conditional exactly like the SQL UPDATE it stands for. */
    private class InMemoryBreaks : NostroBreakRepository {
        val rows = mutableListOf<NostroBreak>()
        val outbox = mutableListOf<Pair<String, String>>()

        override suspend fun inScope(glCode: String, statementUuid: UUID, from: LocalDate, to: LocalDate) =
            rows.filter {
                it.glCode == glCode &&
                    (it.statementUuid == statementUuid || (it.ledgerLineId != null && it.bookingDate in from..to))
            }

        override suspend fun apply(changes: BreakChanges) {
            val changed = (changes.resolved + changes.reopened).associateBy { it.id }
            rows.replaceAll { changed[it.id] ?: it }
            rows += changes.opened
        }

        override suspend fun byIban(iban: String, includeResolved: Boolean) =
            rows.filter { it.iban == iban && (includeResolved || it.open) }

        override suspend fun open() = rows.filter { it.open }

        override suspend fun markAlerted(
            breakId: UUID,
            alertedAt: Instant,
            eventType: String,
            payload: String,
        ): Boolean {
            val i = rows.indexOfFirst { it.id == breakId && it.alertedAt == null }
            if (i < 0) return false
            rows[i] = rows[i].copy(alertedAt = alertedAt)
            outbox += eventType to payload
            return true
        }
    }

    private val statements = NostroReconciliationServiceTest.InMemoryStatements()
    private val ledger = FakeLedgerRead()
    private val breakRepo = InMemoryBreaks()
    private val accounts = mapOf(NostroFixtures.IBAN to "1001")
    private var today = LocalDate.parse("2026-09-25")
    private val clock get() = Clock.fixed(today.atTime(10, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private val mapper = ObjectMapper().registerModule(JavaTimeModule())

    private fun reconciliation() = NostroReconciliationService(statements, ledger, accounts, clock)
    private fun service() = NostroBreakService(
        statements,
        reconciliation(),
        breakRepo,
        accounts,
        BreakAlertPolicy(3, BigDecimal("1000")),
        mapper,
        clock,
    )

    private fun upload() = runBlocking {
        reconciliation().upload(
            Camt053Parser.parse(NostroFixtures.xml()),
            "sha",
            "k-${UUID.randomUUID()}",
            Actor("anna.approver", ActorType.HUMAN),
        )
    }

    @Test
    fun `sweep records breaks, ages them, and alerts once per break over both thresholds`() = runBlocking<Unit> {
        NostroFixtures.ledgerLines().forEach { ledger.lines += "1001" to it }
        upload()

        val first = service().sweep(today)
        assertThat(first.observed).isEqualTo(1)
        assertThat(first.open).isEqualTo(2)
        assertThat(first.aged).isZero()
        assertThat(breakRepo.outbox).isEmpty()

        today = LocalDate.parse("2026-09-30") // 3 business days after Friday
        val later = service().sweep(today)
        // The 5000 statement break is aged; the 42 fee is below the 1000 amount threshold.
        assertThat(later.aged).isEqualTo(1)
        assertThat(later.alerted).isEqualTo(1)
        assertThat(breakRepo.outbox.single().first).isEqualTo(NostroBreakAged.EVENT_TYPE)
        val payload = mapper.readTree(breakRepo.outbox.single().second)
        assertThat(payload["amount"].decimalValue()).isEqualByComparingTo("5000.00")
        assertThat(payload["ageBusinessDays"].asInt()).isEqualTo(3)
        assertThat(payload["side"].asText()).isEqualTo("STATEMENT")
        assertThat(payload.has("reference")).isFalse() // remittance text never leaves on the topic

        val again = service().sweep(today.plusDays(1))
        assertThat(again.aged).isEqualTo(1)
        assertThat(again.alerted).isZero()
        assertThat(breakRepo.outbox).hasSize(1)
    }

    @Test
    fun `a late ledger booking resolves the break on the next sweep`() = runBlocking<Unit> {
        NostroFixtures.ledgerLines().forEach { ledger.lines += "1001" to it }
        upload()
        service().sweep(today)
        ledger.lines += "1001" to NostroFixtures.line("5000.00", Side.DEBIT, description = "SYNTH-SVCR-0003")

        today = today.plusDays(1)
        val r = service().sweep(today)
        assertThat(r.open).isEqualTo(1)
        assertThat(service().breaks(NostroFixtures.IBAN, includeResolved = true).map { it.brk.resolvedOn })
            .containsExactlyInAnyOrder(null, today)
    }

    @Test
    fun `one statement the ledger cannot answer for does not stop the sweep`() = runBlocking<Unit> {
        upload()
        ledger.unavailableAccounts += "1001"
        val r = service().sweep(today)
        assertThat(r.failures).hasSize(1)
        assertThat(r.open).isZero()
    }

    @Test
    fun `listing an unconfigured account is a not-found, a configured one lists oldest first`() = runBlocking<Unit> {
        assertThatThrownBy { runBlocking { service().breaks("CZ0000000000000000000000", false) } }
            .isInstanceOf(NostroAccountNotFoundException::class.java)
        assertThat(service().breaks("cz12 9999 0000 0000 0000 1001", false)).isEmpty()
    }
}
