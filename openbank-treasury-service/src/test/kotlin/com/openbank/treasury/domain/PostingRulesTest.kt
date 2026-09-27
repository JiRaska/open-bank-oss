// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

import com.openbank.treasury.domain.DealFixtures.MONDAY
import com.openbank.treasury.domain.DealFixtures.placement
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.PostingLine
import com.openbank.treasury.domain.model.PostingRules
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.Side
import com.openbank.treasury.domain.model.TreasuryChart
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** The posting table, one assertion per (product, event) row — the runbook's table is this one. */
class PostingRulesTest {

    private fun JournalSpec.render() = lines.map {
        "${it.side.name.first()} ${it.glCode} ${it.amount.toPlainString()} ${it.currency}"
    }

    private val p30 = placement(principal = "100000.00", rate = "4.25", maturity = MONDAY.plusDays(30))

    @Test
    fun `placement settles Dr placements Cr nostro`() {
        assertThat(
            PostingRules.settlement(p30).render(),
        ).containsExactly("D 1500 100000.00 CZK", "C 1001 100000.00 CZK")
    }

    @Test
    fun `placement matures Dr nostro P+I, Cr placement P, Cr MM interest income I`() {
        assertThat(PostingRules.maturity(p30).render()).containsExactly(
            "D 1001 100354.17 CZK",
            "C 1500 100000.00 CZK",
            "C 4200 354.17 CZK",
        )
    }

    @Test
    fun `EUR placement uses the EUR accounts`() {
        val eur = placement(currency = "EUR", principal = "10000.00", maturity = MONDAY.plusDays(90), rate = "3.00")
        assertThat(PostingRules.settlement(eur).render()).containsExactly("D 1501 10000.00 EUR", "C 1002 10000.00 EUR")
        assertThat(PostingRules.maturity(eur).render()).containsExactly(
            "D 1002 10075.00 EUR",
            "C 1501 10000.00 EUR",
            "C 4201 75.00 EUR",
        )
    }

    @Test
    fun `borrowing settles Dr nostro Cr borrowings and matures with interest expense`() {
        val b =
            placement(
                product = ProductType.MM_BORROWING,
                principal = "200000.00",
                rate = "3.60",
                maturity = MONDAY.plusDays(10),
            )
        assertThat(PostingRules.settlement(b).render()).containsExactly("D 1001 200000.00 CZK", "C 2300 200000.00 CZK")
        assertThat(PostingRules.maturity(b).render()).containsExactly(
            "D 2300 200000.00 CZK",
            "D 5200 200.00 CZK",
            "C 1001 200200.00 CZK",
        )
    }

    @Test
    fun `ČNB deposit facility settles to 1510 and matures overnight`() {
        val c =
            placement(
                product = ProductType.CNB_DEPOSIT_FACILITY,
                counterparty = "CNB",
                principal = "36000000.00",
                rate = "3.25",
                maturity = null,
            )
        assertThat(
            PostingRules.settlement(c).render(),
        ).containsExactly("D 1510 36000000.00 CZK", "C 1001 36000000.00 CZK")
        // 36 000 000 × 3.25 % × 1 / 360 = 3 250.00
        assertThat(PostingRules.maturity(c).render()).containsExactly(
            "D 1001 36003250.00 CZK",
            "C 1510 36000000.00 CZK",
            "C 4200 3250.00 CZK",
        )
    }

    @Test
    fun `a zero-rate deal posts no interest line`() {
        val z = placement(rate = "0")
        assertThat(PostingRules.maturity(z).lines).hasSize(2)
    }

    @Test
    fun `reversal from SETTLED flips the settlement journal, from BOOKED posts nothing`() {
        assertThat(PostingRules.reversal(p30, DealState.SETTLED)!!.render())
            .containsExactly("C 1500 100000.00 CZK", "D 1001 100000.00 CZK")
        assertThat(PostingRules.reversal(p30, DealState.BOOKED)).isNull()
    }

    @Test
    fun `idempotency keys are treasury-dealId-event and stable`() {
        assertThat(PostingRules.settlement(p30).idempotencyKey).isEqualTo("treasury:${p30.id}:settled")
        assertThat(PostingRules.maturity(p30).idempotencyKey).isEqualTo("treasury:${p30.id}:matured")
        assertThat(
            PostingRules.reversal(p30, DealState.SETTLED)!!.idempotencyKey,
        ).isEqualTo("treasury:${p30.id}:reversed")
        assertThat(PostingRules.settlement(p30).idempotencyKey).isEqualTo(PostingRules.settlement(p30).idempotencyKey)
    }

    @Test
    fun `ČNB lombard settles Dr nostro Cr 2320 and matures Friday to Monday with three days of expense`() {
        // 10 000 000 × 5.75 % × 3 / 360 = 4 791.666… -> 4 791.67
        val l = placement(
            product = ProductType.CNB_LOMBARD,
            counterparty = "CNB",
            principal = "10000000.00",
            rate = "5.75",
            maturity = null,
            valueDate = DealFixtures.FRIDAY,
        )
        assertThat(PostingRules.settlement(l).render())
            .containsExactly("D 1001 10000000.00 CZK", "C 2320 10000000.00 CZK")
        assertThat(PostingRules.maturity(l).render()).containsExactly(
            "D 2320 10000000.00 CZK",
            "D 5200 4791.67 CZK",
            "C 1001 10004791.67 CZK",
        )
        val accrued = (1L..l.days).mapNotNull { PostingRules.accrual(l, l.valueDate.plusDays(it)) }
        assertThat(accrued.first().render().map { it.substring(0, 6) }).containsExactly("D 5200", "C 2310")
        assertThat(PostingRules.reversal(l, DealState.SETTLED)!!.render())
            .containsExactly("C 1001 10000000.00 CZK", "D 2320 10000000.00 CZK")
    }

    @Test
    fun `an unbalanced journal cannot be constructed`() {
        assertThatThrownBy {
            JournalSpec(
                UUID.randomUUID(),
                PostingEvent.SETTLED,
                listOf(
                    PostingLine("1500", Side.DEBIT, BigDecimal("10"), "CZK"),
                    PostingLine("1001", Side.CREDIT, BigDecimal("9.99"), "CZK"),
                ),
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `every posted code has a fixed ledger id in the seeded range`() {
        assertThat(TreasuryChart.postedCodes).containsExactlyInAnyOrder(
            "1001", "1002", "1500", "1501", "1510", "2300", "2301", "4200", "4201", "5200", "5201",
            "1520", "1521", "2310", "2311", "2320",
        )
        assertThat(TreasuryChart.glAccountId("1510").toString()).isEqualTo("a0000000-0000-0000-0000-000000001510")
    }

    // --- ADR-0315 D5: daily accrual ------------------------------------------------------------

    private fun dailies(deal: com.openbank.treasury.domain.model.Deal) =
        (1..deal.days).mapNotNull { PostingRules.accrual(deal, deal.valueDate.plusDays(it)) }

    @Test
    fun `the dailies of a placement sum to exactly its interest, Dr accrued receivable Cr income`() {
        assertThat(p30.interest).isEqualByComparingTo("354.17")
        val days = dailies(p30)
        assertThat(days.sumOf { it.lines.first().amount }).isEqualByComparingTo(p30.interest)
        assertThat(days.first().render()).containsExactly("D 1520 11.81 CZK", "C 4200 11.81 CZK")
        assertThat(days.first().idempotencyKey).isEqualTo("treasury:${p30.id}:accrued:${MONDAY.plusDays(1)}")
    }

    @Test
    fun `no accrual before the value date or after maturity`() {
        assertThat(PostingRules.accrual(p30, MONDAY)).isNull()
        assertThat(PostingRules.accrual(p30, MONDAY.plusDays(31))).isNull()
        assertThat(PostingRules.accruedThrough(p30, MONDAY.plusDays(400))).isEqualByComparingTo(p30.interest)
    }

    @Test
    fun `a fully accrued placement matures through the accrued account and books nothing more to income`() {
        assertThat(PostingRules.maturity(p30, p30.interest).render()).containsExactly(
            "D 1001 100354.17 CZK",
            "C 1500 100000.00 CZK",
            "C 1520 354.17 CZK",
        )
    }

    @Test
    fun `a part-accrued placement splits the interest between the accrued account and income`() {
        val accrued = PostingRules.accruedThrough(p30, MONDAY.plusDays(10))
        assertThat(PostingRules.maturity(p30, accrued).render()).containsExactly(
            "D 1001 100354.17 CZK",
            "C 1500 100000.00 CZK",
            "C 1520 ${accrued.toPlainString()} CZK",
            "C 4200 ${(p30.interest - accrued).toPlainString()} CZK",
        )
    }

    @Test
    fun `a borrowing accrues Dr expense Cr accrued payable and matures through it`() {
        val b =
            placement(
                principal = "100000.00",
                rate = "4.25",
                maturity = MONDAY.plusDays(30),
                product = ProductType.MM_BORROWING,
            )
        assertThat(dailies(b).first().render()).containsExactly("D 5200 11.81 CZK", "C 2310 11.81 CZK")
        assertThat(PostingRules.maturity(b, b.interest).render()).containsExactly(
            "D 2300 100000.00 CZK",
            "D 2310 354.17 CZK",
            "C 1001 100354.17 CZK",
        )
    }

    @Test
    fun `reversing a settled, part-accrued deal also unwinds the accrual out of income`() {
        val accrued = PostingRules.accruedThrough(p30, MONDAY.plusDays(5))
        assertThat(PostingRules.reversal(p30, DealState.SETTLED, accrued)!!.render()).containsExactly(
            "C 1500 100000.00 CZK",
            "D 1001 100000.00 CZK",
            "D 4200 ${accrued.toPlainString()} CZK",
            "C 1520 ${accrued.toPlainString()} CZK",
        )
    }

    @Test
    fun `accrued outside 0 to interest is refused`() {
        assertThatThrownBy { PostingRules.maturity(p30, p30.interest + BigDecimal("0.01")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
