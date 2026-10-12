// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.domain

import com.openbank.pensionfund.domain.model.ForwardPricer
import com.openbank.pensionfund.domain.model.NavFigures
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import com.openbank.pensionfund.domain.model.OrderStatus
import com.openbank.pensionfund.domain.model.OrderType
import com.openbank.pensionfund.domain.model.UnitHolding
import com.openbank.pensionfund.domain.model.UnitOrder
import com.openbank.pensionfund.domain.model.UnitTransactionType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ForwardPricerTest {

    private val contract = UUID.randomUUID()
    private val fund = UUID.randomUUID()
    private val target = UUID.randomUUID()
    private val placed = Instant.parse("2026-10-09T09:00:00Z")

    private fun nav(price: String, publishedAt: Instant = Instant.parse("2026-10-09T17:00:00Z"), fundId: UUID = fund) =
        NavRecord(
            id = UUID.randomUUID(),
            fundId = fundId,
            valuationDate = LocalDate.parse("2026-10-09"),
            figures = NavFigures(
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal(price),
            ),
            status = NavStatus.PUBLISHED,
            calculatedBy = "maker",
            calculatedAt = publishedAt.minusSeconds(60),
            approvedBy = "checker",
            publishedAt = publishedAt,
        )

    private fun order(type: OrderType, amount: String? = null, units: String? = null, targetFundId: UUID? = null) =
        UnitOrder(
            id = UUID.randomUUID(),
            contractId = contract,
            fundId = fund,
            type = type,
            amount = amount?.let(::BigDecimal),
            units = units?.let(::BigDecimal),
            targetFundId = targetFundId,
            parentOrderId = null,
            status = OrderStatus.PENDING,
            placedAt = placed,
        )

    private fun holding(units: String) = UnitHolding(contract, fund, BigDecimal(units))

    @Test
    fun `a subscription buys units at the next nav, rounded down so the fund never over-issues`() {
        val s = ForwardPricer.settle(
            order(OrderType.SUBSCRIBE, amount = "1000.00"),
            nav("1.234567"),
            holding("0"),
            UUID.randomUUID(),
            UUID.randomUUID(),
        )
        // 1000 / 1.234567 = 810.0005913... -> 810.000591 (DOWN)
        assertThat(s.transaction.units).isEqualTo(BigDecimal("810.000591"))
        assertThat(s.transaction.amount).isEqualTo(BigDecimal("1000.00"))
        assertThat(s.holding.units).isEqualTo(BigDecimal("810.000591"))
        assertThat(s.order.status).isEqualTo(OrderStatus.SETTLED)
        assertThat(s.followUp).isNull()
    }

    @Test
    fun `a nav already published when the order was placed is backward pricing and refused`() {
        val stale = nav("1.10", publishedAt = placed.minusSeconds(1))
        assertThatThrownBy {
            ForwardPricer.settle(
                order(OrderType.SUBSCRIBE, amount = "100"),
                stale,
                holding("0"),
                UUID.randomUUID(),
                UUID.randomUUID(),
            )
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("was known")
    }

    @Test
    fun `a redemption sells units for proceeds rounded half-even to money`() {
        val s = ForwardPricer.settle(
            order(OrderType.REDEEM, units = "100.5"),
            nav("1.234567"),
            holding("200"),
            UUID.randomUUID(),
            UUID.randomUUID(),
        )
        // 100.5 * 1.234567 = 124.0739835 -> 124.07
        assertThat(s.transaction.amount).isEqualTo(BigDecimal("124.07"))
        assertThat(s.holding.units).isEqualByComparingTo("99.5")
    }

    @Test
    fun `a redemption cannot sell more units than the contract holds`() {
        assertThatThrownBy {
            ForwardPricer.settle(
                order(OrderType.REDEEM, units = "10"),
                nav("1"),
                holding("5"),
                UUID.randomUUID(),
                UUID.randomUUID(),
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a switch sells at the source nav and queues the buy for the target's next nav`() {
        val s = ForwardPricer.settle(
            order(OrderType.SWITCH_OUT, units = "50", targetFundId = target),
            nav("2.000000"),
            holding("50"),
            UUID.randomUUID(),
            UUID.randomUUID(),
        )
        assertThat(s.transaction.type).isEqualTo(UnitTransactionType.SWITCH_OUT)
        assertThat(s.transaction.amount).isEqualTo(BigDecimal("100.00"))
        assertThat(s.holding.units).isEqualByComparingTo("0")
        val leg = s.followUp!!
        assertThat(leg.type).isEqualTo(OrderType.SWITCH_IN)
        assertThat(leg.fundId).isEqualTo(target)
        assertThat(leg.amount).isEqualTo(BigDecimal("100.00"))
        assertThat(leg.status).isEqualTo(OrderStatus.PENDING)
        assertThat(leg.parentOrderId).isEqualTo(s.order.id)

        // The leg is not priced at a target NAV published at the same instant — only a later one.
        val targetHolding = UnitHolding(contract, target, BigDecimal.ZERO)
        assertThatThrownBy {
            ForwardPricer.settle(
                leg,
                nav("4", publishedAt = leg.placedAt, fundId = target),
                targetHolding,
                UUID.randomUUID(),
                UUID.randomUUID(),
            )
        }.isInstanceOf(IllegalStateException::class.java)
        val bought = ForwardPricer.settle(
            leg,
            nav("4", publishedAt = leg.placedAt.plusSeconds(3600), fundId = target),
            targetHolding,
            UUID.randomUUID(),
            UUID.randomUUID(),
        )
        assertThat(bought.transaction.units).isEqualTo(BigDecimal("25.000000"))
    }

    @Test
    fun `a switch must target another fund and orders carry the right quantity kind`() {
        assertThatThrownBy {
            order(OrderType.SWITCH_OUT, units = "1", targetFundId = fund)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            order(OrderType.SUBSCRIBE, units = "1")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { order(OrderType.REDEEM, amount = "1") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a fee cancels units rounded up`() {
        val (tx, after) = ForwardPricer.chargeFee(
            holding("100"),
            BigDecimal("10.00"),
            nav("3.000000"),
            UUID.randomUUID(),
        )
        assertThat(tx.type).isEqualTo(UnitTransactionType.FEE)
        assertThat(tx.units).isEqualTo(BigDecimal("3.333334"))
        assertThat(after.units).isEqualByComparingTo("96.666666")
    }

    @Test
    fun `a nav correction re-prices units for money in and proceeds for money out`() {
        val wrong = nav("2.000000")
        val sub = ForwardPricer.settle(
            order(OrderType.SUBSCRIBE, amount = "100.00"),
            wrong,
            holding("0"),
            UUID.randomUUID(),
            UUID.randomUUID(),
        )
        val red = ForwardPricer.settle(
            order(OrderType.REDEEM, units = "10"),
            wrong,
            holding("10"),
            UUID.randomUUID(),
            UUID.randomUUID(),
        )
        val right = nav("2.500000")

        val subFix = ForwardPricer.reprice(sub.transaction, right)
        assertThat(subFix.transaction.units).isEqualTo(BigDecimal("40.000000"))
        assertThat(subFix.unitsDelta).isEqualByComparingTo("-10")
        assertThat(subFix.transaction.correctedFromNavId).isEqualTo(wrong.id)
        assertThat(subFix.transaction.navId).isEqualTo(right.id)

        val redFix = ForwardPricer.reprice(red.transaction, right)
        assertThat(redFix.transaction.amount).isEqualTo(BigDecimal("25.00"))
        assertThat(redFix.amountDelta).isEqualByComparingTo("5.00")
        assertThat(redFix.unitsDelta).isEqualByComparingTo("0")
    }
}
