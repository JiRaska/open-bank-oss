// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.domain

import com.openbank.pensionfund.domain.model.Fund
import com.openbank.pensionfund.domain.model.FundStatus
import com.openbank.pensionfund.domain.model.NavCorrectionBlocker
import com.openbank.pensionfund.domain.model.NavCorrectionPlanner
import com.openbank.pensionfund.domain.model.NavFigures
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import com.openbank.pensionfund.domain.model.OrderStatus
import com.openbank.pensionfund.domain.model.OrderType
import com.openbank.pensionfund.domain.model.UnitHolding
import com.openbank.pensionfund.domain.model.UnitOrder
import com.openbank.pensionfund.domain.model.UnitTransaction
import com.openbank.pensionfund.domain.model.UnitTransactionType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class NavCorrectionPlannerTest {
    private val contract = UUID.randomUUID()
    private val sourceFund = UUID.randomUUID()
    private val targetFund = UUID.randomUUID()
    private val at = Instant.parse("2026-10-10T17:00:00Z")

    private fun fund(id: UUID, currency: String) = Fund(
        id = id,
        name = "Test fund",
        isin = "CZ0000000001",
        lei = "12345678901234567890",
        depositaryReference = "test-depositary",
        custodyAccountReference = "test-custody",
        currency = currency,
        riskClass = 3,
        mandatoryConservative = false,
        managementFeeRate = BigDecimal("0.01"),
        launchNavPerUnit = BigDecimal.ONE,
        status = FundStatus.ACTIVE,
        createdAt = at,
        updatedAt = at,
    )

    private fun nav(fundId: UUID, price: String, original: NavRecord? = null): NavRecord = NavRecord(
        id = UUID.randomUUID(),
        fundId = fundId,
        valuationDate = LocalDate.parse("2026-10-10"),
        figures = NavFigures(
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal(price),
        ),
        status = if (original == null) NavStatus.PUBLISHED else NavStatus.CALCULATED,
        calculatedBy = "maker",
        calculatedAt = at.minusSeconds(60),
        correctsNavId = original?.id,
        approvedBy = if (original == null) "checker" else null,
        publishedAt = if (original == null) at else null,
    )

    private fun transaction(
        nav: NavRecord,
        type: UnitTransactionType,
        units: String,
        amount: String,
        orderId: UUID? = UUID.randomUUID(),
    ) = UnitTransaction(
        id = UUID.randomUUID(),
        orderId = orderId,
        contractId = contract,
        fundId = nav.fundId,
        type = type,
        units = BigDecimal(units),
        amount = BigDecimal(amount),
        navId = nav.id,
        navPerUnit = nav.navPerUnit,
        pricedAt = at,
    )

    private fun order(
        id: UUID,
        fundId: UUID,
        type: OrderType,
        amount: String? = null,
        units: String? = null,
        parent: UUID? = null,
        target: UUID? = null,
    ) = UnitOrder(
        id = id,
        contractId = contract,
        fundId = fundId,
        type = type,
        amount = amount?.let(::BigDecimal),
        units = units?.let(::BigDecimal),
        targetFundId = target,
        parentOrderId = parent,
        status = OrderStatus.SETTLED,
        placedAt = at.minusSeconds(3600),
    )

    @Test
    fun `depleted subscription plans units but marks recovery unresolved without changing input`() {
        val original = nav(sourceFund, "2.000000")
        val replacement = nav(sourceFund, "1.000000", original)
        val tx = transaction(original, UnitTransactionType.SUBSCRIBE, "50.000000", "100.00")
        val subscribe = order(checkNotNull(tx.orderId), sourceFund, OrderType.SUBSCRIBE, amount = "100.00")
        val held = UnitHolding(contract, sourceFund, BigDecimal("10.000000"))
        val snapshot = tx.copy()

        val plan = NavCorrectionPlanner.calculate(
            original,
            replacement,
            fund(sourceFund, "CZK"),
            listOf(tx),
            listOf(subscribe),
            emptyList(),
            emptyList(),
            listOf(held),
        )

        assertThat(plan.originalNavId).isEqualTo(original.id)
        assertThat(plan.replacementNavId).isEqualTo(replacement.id)
        assertThat(plan.fundCurrency).isEqualTo("CZK")
        assertThat(plan.lines.single().currency).isEqualTo("CZK")
        assertThat(plan.lines.single().originalTransactionId).isEqualTo(tx.id)
        assertThat(plan.lines.single().unitsDelta).isEqualByComparingTo("50.000000")
        assertThat(plan.unresolved).isEmpty()
        assertThat(tx).isEqualTo(snapshot)
        assertThat(held.units).isEqualByComparingTo("10")

        val downward = nav(sourceFund, "4.000000", original)
        val recovery = NavCorrectionPlanner.calculate(
            original,
            downward,
            fund(sourceFund, "CZK"),
            listOf(tx),
            listOf(subscribe),
            emptyList(),
            emptyList(),
            listOf(held),
        )
        assertThat(recovery.lines.single().unitsDelta).isEqualByComparingTo("-25.000000")
        assertThat(recovery.unresolved.map { it.blocker }).containsExactly(NavCorrectionBlocker.INSUFFICIENT_HELD_UNITS)
    }

    @Test
    fun `source switch preserves both leg identities and flags target repricing and rounding`() {
        val original = nav(sourceFund, "2.000000")
        val replacement = nav(sourceFund, "2.333333", original)
        val outOrder = order(UUID.randomUUID(), sourceFund, OrderType.SWITCH_OUT, units = "3", target = targetFund)
        val inOrder = order(UUID.randomUUID(), targetFund, OrderType.SWITCH_IN, amount = "6.00", parent = outOrder.id)
        val outTx = transaction(original, UnitTransactionType.SWITCH_OUT, "3.000000", "6.00", outOrder.id)
        val targetNav = nav(targetFund, "3.000000")
        val inTx = transaction(targetNav, UnitTransactionType.SWITCH_IN, "2.000000", "6.00", inOrder.id)

        val plan = NavCorrectionPlanner.calculate(
            original,
            replacement,
            fund(sourceFund, "CZK"),
            listOf(outTx),
            listOf(outOrder, inOrder),
            listOf(inTx),
            listOf(fund(targetFund, "CZK")),
            listOf(UnitHolding(contract, sourceFund, BigDecimal.ZERO)),
        )

        val line = plan.lines.single()
        assertThat(line.originalOrderId).isEqualTo(outOrder.id)
        assertThat(line.linkedSwitchOrderId).isEqualTo(inOrder.id)
        assertThat(line.linkedSwitchTransactionId).isEqualTo(inTx.id)
        assertThat(line.cashDelta).isEqualByComparingTo("1.00")
        assertThat(line.roundingResidual).isEqualByComparingTo("-0.000001")
        assertThat(plan.unresolved.map { it.blocker }).containsExactlyInAnyOrder(
            NavCorrectionBlocker.ROUNDING_RECONCILIATION_REQUIRED,
            NavCorrectionBlocker.SWITCH_COUNTERPART_REPRICE_REQUIRED,
        )
        assertThat(inTx.amount).isEqualByComparingTo("6.00")
    }

    @Test
    fun `target switch links its source and refuses incomplete correction identity`() {
        val original = nav(targetFund, "3.000000")
        val replacement = nav(targetFund, "4.000000", original)
        val outOrder = order(UUID.randomUUID(), sourceFund, OrderType.SWITCH_OUT, units = "3", target = targetFund)
        val inOrder = order(UUID.randomUUID(), targetFund, OrderType.SWITCH_IN, amount = "6.00", parent = outOrder.id)
        val inTx = transaction(original, UnitTransactionType.SWITCH_IN, "2.000000", "6.00", inOrder.id)
        val sourceNav = nav(sourceFund, "2.000000")
        val outTx = transaction(sourceNav, UnitTransactionType.SWITCH_OUT, "3.000000", "6.00", outOrder.id)

        val plan = NavCorrectionPlanner.calculate(
            original,
            replacement,
            fund(targetFund, "EUR"),
            listOf(inTx),
            listOf(outOrder, inOrder),
            listOf(outTx),
            listOf(fund(sourceFund, "EUR")),
            listOf(UnitHolding(contract, targetFund, BigDecimal("2.000000"))),
        )
        assertThat(plan.lines.single().linkedSwitchOrderId).isEqualTo(outOrder.id)
        assertThat(plan.lines.single().linkedSwitchTransactionId).isEqualTo(outTx.id)
        assertThat(plan.lines.single().currency).isEqualTo("EUR")
        assertThat(plan.lines.single().unitsDelta).isEqualByComparingTo("-0.500000")
        assertThat(
            plan.unresolved.map {
                it.blocker
            },
        ).containsExactly(NavCorrectionBlocker.SWITCH_COUNTERPART_REPRICE_REQUIRED)

        assertThatThrownBy {
            NavCorrectionPlanner.calculate(
                original,
                sourceNav,
                fund(targetFund, "EUR"),
                listOf(inTx),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `mixed contract switch cannot borrow unrelated target leg`() {
        val original = nav(sourceFund, "2.000000")
        val replacement = nav(sourceFund, "3.000000", original)
        val outOrder = order(UUID.randomUUID(), sourceFund, OrderType.SWITCH_OUT, units = "2", target = targetFund)
        val foreignContract = UUID.randomUUID()
        val inOrder = order(UUID.randomUUID(), targetFund, OrderType.SWITCH_IN, amount = "4.00", parent = outOrder.id)
            .copy(contractId = foreignContract)
        val outTx = transaction(original, UnitTransactionType.SWITCH_OUT, "2.000000", "4.00", outOrder.id)
        val targetNav = nav(targetFund, "2.000000")
        val foreignInTx = transaction(targetNav, UnitTransactionType.SWITCH_IN, "2.000000", "4.00", inOrder.id)
            .copy(contractId = foreignContract)

        val plan = NavCorrectionPlanner.calculate(
            original,
            replacement,
            fund(sourceFund, "CZK"),
            listOf(outTx),
            listOf(outOrder, inOrder),
            listOf(foreignInTx),
            listOf(fund(targetFund, "CZK")),
            listOf(UnitHolding(contract, sourceFund, BigDecimal.ZERO)),
        )
        assertThat(plan.lines.single().linkedSwitchOrderId).isNull()
        assertThat(plan.lines.single().linkedSwitchTransactionId).isNull()
        assertThat(plan.unresolved.map { it.blocker }).contains(NavCorrectionBlocker.SWITCH_COUNTERPART_MISSING)
    }

    @Test
    fun `ambiguous records are rejected and counterpart currency remains explicit`() {
        val original = nav(sourceFund, "2.000000")
        val replacement = nav(sourceFund, "3.000000", original)
        val outOrder = order(UUID.randomUUID(), sourceFund, OrderType.SWITCH_OUT, units = "2", target = targetFund)
        val inOrder = order(UUID.randomUUID(), targetFund, OrderType.SWITCH_IN, amount = "4.00", parent = outOrder.id)
        val outTx = transaction(original, UnitTransactionType.SWITCH_OUT, "2.000000", "4.00", outOrder.id)
        val held = UnitHolding(contract, sourceFund, BigDecimal.ZERO)

        fun plan(
            orders: List<UnitOrder> = listOf(outOrder, inOrder),
            related: List<UnitTransaction> = emptyList(),
            funds: List<Fund> = emptyList(),
            holdings: List<UnitHolding> = listOf(held),
        ) = NavCorrectionPlanner.calculate(
            original,
            replacement,
            fund(sourceFund, "CZK"),
            listOf(outTx),
            orders,
            related,
            funds,
            holdings,
        )

        assertThatThrownBy { plan(orders = listOf(outOrder, inOrder, inOrder)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("duplicate related order")
        assertThatThrownBy { plan(holdings = listOf(held, held)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("duplicate holding")
        val targetNav = nav(targetFund, "2.000000")
        val inTx = transaction(targetNav, UnitTransactionType.SWITCH_IN, "2.000000", "4.00", inOrder.id)
        assertThatThrownBy { plan(related = listOf(inTx, inTx.copy(id = UUID.randomUUID()))) }
            .isInstanceOf(
                IllegalArgumentException::class.java,
            ).hasMessageContaining("duplicate or missing related transaction order")
        assertThatThrownBy {
            NavCorrectionPlanner.calculate(
                original,
                replacement,
                fund(targetFund, "CZK"),
                listOf(outTx),
                listOf(outOrder, inOrder),
                emptyList(),
                emptyList(),
                listOf(held),
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("source fund must match")

        val unknownCurrency = plan()
        assertThat(unknownCurrency.lines.single().linkedSwitchCurrency).isNull()
        assertThat(unknownCurrency.unresolved.map { it.blocker })
            .contains(NavCorrectionBlocker.SWITCH_COUNTERPART_CURRENCY_UNVERIFIED)
        val otherCurrency = plan(funds = listOf(fund(targetFund, "EUR")))
        assertThat(otherCurrency.lines.single().linkedSwitchCurrency).isEqualTo("EUR")
        assertThat(otherCurrency.unresolved.map { it.blocker })
            .contains(NavCorrectionBlocker.CROSS_CURRENCY_SWITCH_POLICY_REQUIRED)
    }
}
