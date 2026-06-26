// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.ledger.domain.model

import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Money-path correctness for ADR-0039 Phase D: JournalEntry.bookedDeltas() must derive the right
 * signed per-customer booked delta from a journal's deposit-control legs (credit-positive), so the
 * balance projection (once enabled) reconstructs customer balances exactly. Pure domain — no infra.
 */
class JournalEntryBookedDeltasTest {

    private val acctA = UUID.fromString("a0000000-0000-0000-0000-0000000000a1")
    private val acctB = UUID.fromString("a0000000-0000-0000-0000-0000000000b2")
    private val glDeposit = UUID.fromString("c0000000-0000-0000-0000-000000000001") // customer deposit-control
    private val glCash = UUID.fromString("c0000000-0000-0000-0000-000000000002") // cash-clearing (no customer)

    private fun line(
        gl: UUID,
        side: JournalSide,
        amount: String,
        ccy: String = "CZK",
        sub: UUID? = null,
        seq: Int = 0,
    ) = JournalLine(
        id = UUID.randomUUID(), journalId = UUID.randomUUID(), glAccountId = gl, side = side,
        amount = Money.of(BigDecimal(amount), ccy), fxRate = null,
        baseAmount = Money.of(BigDecimal(amount), ccy), sequence = seq, subAccountId = sub,
    )

    private fun entry(lines: List<JournalLine>) = JournalEntry(
        id = UUID.randomUUID(), entryNumber = 1L, transactionId = UUID.randomUUID(),
        entryDate = LocalDate.of(2026, 1, 15), valueDate = LocalDate.of(2026, 1, 15),
        description = "t", status = JournalStatus.POSTED, lines = lines,
        createdAt = Instant.now(), createdBy = UUID.randomUUID(), version = 0L,
    )

    @Test
    fun `credit to a customer account is a positive booked delta`() {
        val e = entry(
            listOf(
                line(glDeposit, JournalSide.CREDIT, "1000.00", sub = acctA, seq = 0),
                line(glCash, JournalSide.DEBIT, "1000.00", seq = 1),
            ),
        )
        assertThat(e.bookedDeltas()).containsExactly(AccountBookedDelta(acctA, "CZK", BigDecimal("1000.00")))
    }

    @Test
    fun `debit from a customer account is a negative booked delta`() {
        val e = entry(
            listOf(
                line(glDeposit, JournalSide.DEBIT, "250.00", sub = acctA, seq = 0),
                line(glCash, JournalSide.CREDIT, "250.00", seq = 1),
            ),
        )
        assertThat(e.bookedDeltas()).containsExactly(AccountBookedDelta(acctA, "CZK", BigDecimal("-250.00")))
    }

    @Test
    fun `multiple legs for one account and currency are summed`() {
        val e = entry(
            listOf(
                line(glDeposit, JournalSide.CREDIT, "100.00", sub = acctA, seq = 0),
                line(glDeposit, JournalSide.CREDIT, "50.00", sub = acctA, seq = 1),
                line(glCash, JournalSide.DEBIT, "150.00", seq = 2),
            ),
        )
        assertThat(e.bookedDeltas()).containsExactly(AccountBookedDelta(acctA, "CZK", BigDecimal("150.00")))
    }

    @Test
    fun `mixed credit and debit legs on one account net into a single signed delta`() {
        // +100 credit and -30 debit on acctA → +70, plus a 70 balancing cash debit.
        val e = entry(
            listOf(
                line(glDeposit, JournalSide.CREDIT, "100.00", sub = acctA, seq = 0),
                line(glDeposit, JournalSide.DEBIT, "30.00", sub = acctA, seq = 1),
                line(glCash, JournalSide.DEBIT, "70.00", seq = 2),
            ),
        )
        assertThat(e.bookedDeltas()).containsExactly(AccountBookedDelta(acctA, "CZK", BigDecimal("70.00")))
    }

    @Test
    fun `a customer-to-customer transfer yields a delta per account`() {
        val e = entry(
            listOf(
                line(glDeposit, JournalSide.DEBIT, "300.00", sub = acctA, seq = 0),
                line(glDeposit, JournalSide.CREDIT, "300.00", sub = acctB, seq = 1),
            ),
        )
        assertThat(e.bookedDeltas()).containsExactlyInAnyOrder(
            AccountBookedDelta(acctA, "CZK", BigDecimal("-300.00")),
            AccountBookedDelta(acctB, "CZK", BigDecimal("300.00")),
        )
    }

    @Test
    fun `legs without a customer dimension produce no deltas`() {
        val e = entry(
            listOf(
                line(glCash, JournalSide.DEBIT, "500.00", seq = 0),
                line(glCash, JournalSide.CREDIT, "500.00", seq = 1),
            ),
        )
        assertThat(e.bookedDeltas()).isEmpty()
    }

    @Test
    fun `a net-zero customer account is dropped`() {
        val e = entry(
            listOf(
                line(glDeposit, JournalSide.CREDIT, "100.00", sub = acctA, seq = 0),
                line(glDeposit, JournalSide.DEBIT, "100.00", sub = acctA, seq = 1),
            ),
        )
        assertThat(e.bookedDeltas()).isEmpty()
    }

    @Test
    fun `deltas are kept separate per currency`() {
        // A CZK deposit and a EUR deposit for the same customer, each self-balanced per currency.
        val e = entry(
            listOf(
                line(glDeposit, JournalSide.CREDIT, "1000.00", ccy = "CZK", sub = acctA, seq = 0),
                line(glCash, JournalSide.DEBIT, "1000.00", ccy = "CZK", seq = 1),
                line(glDeposit, JournalSide.CREDIT, "40.00", ccy = "EUR", sub = acctA, seq = 2),
                line(glCash, JournalSide.DEBIT, "40.00", ccy = "EUR", seq = 3),
            ),
        )
        assertThat(e.bookedDeltas()).containsExactlyInAnyOrder(
            AccountBookedDelta(acctA, "CZK", BigDecimal("1000.00")),
            AccountBookedDelta(acctA, "EUR", BigDecimal("40.00")),
        )
    }

    @Test
    fun `a reversal negates the original deltas`() {
        val e = entry(
            listOf(
                line(glDeposit, JournalSide.CREDIT, "1000.00", sub = acctA, seq = 0),
                line(glCash, JournalSide.DEBIT, "1000.00", seq = 1),
            ),
        )
        val reversal = e.reverse(UUID.randomUUID(), UUID.randomUUID())
        assertThat(reversal.bookedDeltas()).containsExactly(AccountBookedDelta(acctA, "CZK", BigDecimal("-1000.00")))
    }
}
