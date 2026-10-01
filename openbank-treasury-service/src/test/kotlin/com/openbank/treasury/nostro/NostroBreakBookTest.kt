// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.nostro

import com.openbank.treasury.domain.model.BreakAlertPolicy
import com.openbank.treasury.domain.model.BreakChanges
import com.openbank.treasury.domain.model.BreakSide
import com.openbank.treasury.domain.model.BusinessDays
import com.openbank.treasury.domain.model.LedgerNostroLine
import com.openbank.treasury.domain.model.NostroBreak
import com.openbank.treasury.domain.model.NostroBreakBook
import com.openbank.treasury.domain.model.NostroMatcher
import com.openbank.treasury.domain.model.Side
import com.openbank.treasury.infrastructure.iso20022.Camt053Parser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class NostroBreakBookTest {

    private val statement = Camt053Parser.parse(NostroFixtures.xml())
    private val stmtUuid = UUID.randomUUID()
    private val day1 = LocalDate.parse("2026-09-25") // a Friday
    private val lines = NostroFixtures.ledgerLines()

    private fun reconcile(ledgerLines: List<LedgerNostroLine>) =
        NostroMatcher.match(statement, "1001", ledgerLines, null, null, "not stated")

    private fun observe(
        existing: List<NostroBreak>,
        ledgerLines: List<LedgerNostroLine>,
        today: LocalDate,
    ): BreakChanges = NostroBreakBook.observe(reconcile(ledgerLines), stmtUuid, existing, today) { UUID.randomUUID() }

    private fun BreakChanges.applyTo(existing: List<NostroBreak>): List<NostroBreak> {
        val changed = (resolved + reopened).associateBy { it.id }
        return existing.map { changed[it.id] ?: it } + opened
    }

    @Test
    fun `each unmatched item on either side opens one break, stated from our side`() {
        val c = observe(emptyList(), lines, day1)

        assertThat(c.resolved).isEmpty()
        assertThat(c.opened).hasSize(2)
        val stmt = c.opened.single { it.side == BreakSide.STATEMENT }
        assertThat(stmt.amount).isEqualByComparingTo("5000.00")
        assertThat(stmt.ourSide).isEqualTo(Side.DEBIT) // correspondent CRDT = money into our nostro
        assertThat(stmt.reference).isEqualTo("SYNTH-SVCR-0003")
        assertThat(stmt.statementSequence).isEqualTo(3)
        assertThat(stmt.firstSeenOn).isEqualTo(day1)
        val ledger = c.opened.single { it.side == BreakSide.LEDGER }
        assertThat(ledger.amount).isEqualByComparingTo("42.00")
        assertThat(ledger.ourSide).isEqualTo(Side.CREDIT)
        assertThat(ledger.ledgerLineId).isEqualTo(lines[2].lineId)
    }

    @Test
    fun `observing the same reconciliation again opens nothing and keeps the first-seen day`() {
        val first = observe(emptyList(), lines, day1).applyTo(emptyList())
        val again = observe(first, lines, day1.plusDays(3))
        assertThat(again.isEmpty).isTrue()
        assertThat(first.map { it.firstSeenOn }).containsOnly(day1)
    }

    @Test
    fun `a break resolves the day a later reconciliation matches it`() {
        val first = observe(emptyList(), lines, day1).applyTo(emptyList())
        // The ledger now books the 5000 the correspondent showed, and the 42 fee is reversed away.
        val late = NostroFixtures.line("5000.00", Side.DEBIT, description = "late booking SYNTH-SVCR-0003")
        val c = observe(first, lines.take(2) + late, day1.plusDays(4))

        assertThat(c.opened).isEmpty()
        assertThat(c.resolved).hasSize(2)
        assertThat(c.resolved.map { it.resolvedOn }).containsOnly(day1.plusDays(4))
        val after = c.applyTo(first)
        assertThat(after.filter { it.open }).isEmpty()
        assertThat(after.first().ageBusinessDays(LocalDate.parse("2026-12-31"))).isEqualTo(2) // frozen at resolution
    }

    @Test
    fun `a ledger break outside the statement period is not resolved by this statement`() {
        val elsewhere = NostroBreak(
            id = UUID.randomUUID(),
            breakKey = NostroBreak.ledgerKey(UUID.randomUUID()),
            iban = NostroFixtures.IBAN,
            glCode = "1001",
            currency = "CZK",
            side = BreakSide.LEDGER,
            ourSide = Side.CREDIT,
            amount = BigDecimal.ONE,
            bookingDate = day1.minusDays(10),
            reference = null,
            statementUuid = UUID.randomUUID(),
            statementSequence = null,
            ledgerLineId = UUID.randomUUID(),
            firstSeenOn = day1.minusDays(10),
        )
        val c = observe(listOf(elsewhere), lines, day1)
        assertThat(c.resolved).isEmpty()
    }

    @Test
    fun `a resolved break that is unmatched again reopens with its original first-seen day`() {
        val first = observe(emptyList(), lines, day1).applyTo(emptyList())
        val resolved = first.map { it.copy(resolvedOn = day1.plusDays(1)) }
        val c = observe(resolved, lines, day1.plusDays(5))
        assertThat(c.reopened).hasSize(2)
        assertThat(c.reopened.map { it.firstSeenOn }).containsOnly(day1)
        assertThat(c.opened).isEmpty()
    }

    @Test
    fun `business days skip the weekend and count neither the start day nor a backwards span`() {
        val fri = LocalDate.parse("2026-09-25")
        assertThat(BusinessDays.between(fri, fri)).isZero()
        assertThat(BusinessDays.between(fri, fri.plusDays(1))).isZero() // Saturday
        assertThat(BusinessDays.between(fri, fri.plusDays(3))).isEqualTo(1) // Monday
        assertThat(BusinessDays.between(fri, fri.plusDays(7))).isEqualTo(5)
        assertThat(BusinessDays.between(fri, fri.minusDays(3))).isZero()
    }

    @Test
    fun `the policy alerts at the age threshold, not a day before, and only over the amount`() {
        val b = observe(emptyList(), lines, day1).opened.single { it.side == BreakSide.STATEMENT } // 5000
        val policy = BreakAlertPolicy(3, BigDecimal("1000"))
        val tue = LocalDate.parse("2026-09-29") // 2 business days after Friday
        val wed = LocalDate.parse("2026-09-30") // 3
        assertThat(policy.isAged(b, tue)).isFalse()
        assertThat(policy.isAged(b, wed)).isTrue()
        assertThat(BreakAlertPolicy(3, BigDecimal("5000.01")).isAged(b, wed)).isFalse()
        assertThat(policy.isAged(b.copy(resolvedOn = tue), wed)).isFalse()
    }

    @Test
    fun `a nonsensical policy is refused at construction`() {
        assertThatThrownBy { BreakAlertPolicy(0, BigDecimal.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { BreakAlertPolicy(3, BigDecimal("-1")) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
