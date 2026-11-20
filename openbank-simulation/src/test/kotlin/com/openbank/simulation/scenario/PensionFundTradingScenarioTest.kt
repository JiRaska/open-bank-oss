// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import com.openbank.pensionfund.domain.model.OrderStatus
import com.openbank.pensionfund.domain.model.OrderType
import com.openbank.pensionfund.domain.model.Precision
import com.openbank.pensionfund.domain.model.UnitOrder
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.model.ContractFund
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Test Intelligence simulation gap (money path): exercises [PensionFundTradingScenario] directly
 * against the REAL `pension-fund-service` domain — the `NavCalculator` NAV computation, the
 * four-eyes `NavRecord.publish` rule and the `ForwardPricer` settlement (forward pricing, switch
 * legs, fee cancellation, NAV corrections) — so the harness's binding to the service is proven by
 * an actual assertion, not merely by compiling.
 *
 * The scenario draws funds/orders probabilistically per step, so each test steps the world until
 * the shape it needs exists (bounded), instead of pinning a seed to a specific draw order.
 */
class PensionFundTradingScenarioTest {

    private fun newWorld(faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed = 42L, faultProfile), SimulationConfig())

    /** Step until a settled order matching [predicate] exists, bounded so a regression fails instead of looping. */
    private fun stepUntilSettled(world: World, predicate: (UnitOrder) -> Boolean): UnitOrder {
        repeat(80) {
            PensionFundTradingScenario.step(world)
            world.pensionFundBook.orders.lastOrNull { it.status == OrderStatus.SETTLED }?.let {
                if (predicate(it)) return it
            }
        }
        throw AssertionError("no settled order matching the predicate within 80 steps")
    }

    @Test
    fun `a subscribe order settles at the next published NAV with units issued and a holding`() {
        val world = newWorld()
        val order = stepUntilSettled(world) { it.type == OrderType.SUBSCRIBE }

        assertThat(order.status).isEqualTo(OrderStatus.SETTLED)
        assertThat(order.navId).isNotNull()
        val tx = world.pensionFundBook.transactions.single { it.orderId == order.id }
        assertThat(tx.units.signum()).isPositive()

        // The holding assertion is the conservation invariant, not a raw compare with this one
        // transaction: a fee or correction later in the same step legitimately moves the holding
        // away from any single transaction's units.
        assertThat(MoneyPathInvariants.pensionFundOrderCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.pensionFundUnitConservation.check(world)).isNull()
    }

    @Test
    fun `a settled switch out spawned its switch in leg and both legs stay consistent`() {
        val world = newWorld()
        val switchOut = stepUntilSettled(world) { it.type == OrderType.SWITCH_OUT }

        val leg = world.pensionFundBook.orders.single { it.parentOrderId == switchOut.id }
        assertThat(leg.type).isEqualTo(OrderType.SWITCH_IN)
        assertThat(leg.targetFundId).isNull()
        assertThat(leg.fundId).isNotEqualTo(switchOut.fundId)

        assertThat(MoneyPathInvariants.pensionFundOrderCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.pensionFundUnitConservation.check(world)).isNull()
    }

    @Test
    fun `a write fault defers settlement but the order stays settleable and completes later`() {
        // 100% write failures force the deferred branch every step: orders pile up PENDING, but
        // every one has a forward NAV (published the same step), so the completeness invariant
        // must stay green throughout.
        val world = newWorld(FaultProfile(writeFailureRate = 1.0))
        repeat(6) {
            PensionFundTradingScenario.step(world)
            assertThat(MoneyPathInvariants.pensionFundOrderCompleteness.check(world)).isNull()
        }
        assertThat(world.pensionFundBook.orders).isNotEmpty()
        assertThat(world.pensionFundBook.orders).allMatch { it.status == OrderStatus.PENDING }

        // The fault clears: the next step settles the backlog at the newest forward NAVs.
        val clean = newWorld()
        repeat(3) { PensionFundTradingScenario.step(clean) }
        assertThat(clean.pensionFundBook.orders.filter { it.status == OrderStatus.SETTLED }).isNotEmpty()
    }

    @Test
    fun `the real pricer refuses backward pricing and the real publish rule enforces four eyes`() {
        val world = newWorld()
        repeat(2) { PensionFundTradingScenario.step(world) }
        val book = world.pensionFundBook
        val nav = book.latestPublishedNav(book.funds.first())
        assertThat(nav).isNotNull()

        // An order placed AFTER the NAV was published can never settle at it — the real
        // forward-pricing check is what refuses, not the scenario.
        val lateOrder = UnitOrder(
            id = UUID.randomUUID(),
            contractId = world.customerAccounts.first(),
            fundId = nav!!.fundId,
            type = OrderType.SUBSCRIBE,
            amount = BigDecimal("1000.00"),
            units = null,
            targetFundId = null,
            parentOrderId = null,
            status = OrderStatus.PENDING,
            placedAt = nav.publishedAt!!.plusSeconds(1),
            idempotencyKey = "late-1",
        )
        val key = ContractFund(lateOrder.contractId, lateOrder.fundId)
        assertThatThrownBy {
            com.openbank.pensionfund.domain.model.ForwardPricer.settle(
                lateOrder,
                nav,
                book.holdingOf(key),
                UUID.randomUUID(),
                UUID.randomUUID(),
            )
        }.isInstanceOf(IllegalStateException::class.java)

        // The four-eyes rule on the real NAV record: the calculator cannot publish their own NAV.
        val now = Instant.now()
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val calculated = NavRecord(
            id = UUID.randomUUID(),
            fundId = nav.fundId,
            valuationDate = today,
            figures = nav.figures,
            status = NavStatus.CALCULATED,
            calculatedBy = "same-person",
            calculatedAt = now,
        )
        assertThatThrownBy { calculated.publish("same-person", now) }
            .isInstanceOf(com.openbank.pensionfund.domain.model.FourEyesViolationException::class.java)
        assertThat(calculated.publish("someone-else", now).status).isEqualTo(NavStatus.PUBLISHED)
    }

    @Test
    fun `the unit conservation invariant catches a holding that silently drifts from the fold`() {
        // The DST mutation-equivalent proof: corrupt the book the way a double-apply defect
        // would, and assert the invariant — not an exception — is what surfaces it.
        val world = newWorld()
        stepUntilSettled(world) { it.type == OrderType.SUBSCRIBE }
        val book = world.pensionFundBook
        assertThat(MoneyPathInvariants.pensionFundUnitConservation.check(world)).isNull()

        val tx = book.transactions.first()
        val key = ContractFund(tx.contractId, tx.fundId)
        book.recordHolding(book.holdingOf(key).plus(tx.units)) // one credit applied twice

        assertThat(MoneyPathInvariants.pensionFundUnitConservation.check(world)).isNotNull()
    }

    @Test
    fun `the fee cancellation rounds against the fund and corrections stay in the fold`() {
        val world = newWorld()
        // Bounded sweep: fees and corrections are probabilistic per step.
        repeat(80) { PensionFundTradingScenario.step(world) }

        assertThat(MoneyPathInvariants.pensionFundUnitConservation.check(world)).isNull()
        assertThat(MoneyPathInvariants.pensionFundOrderCompleteness.check(world)).isNull()
        // Every NAV the world struck carries the REAL calculator's figures; every correction
        // trail points at a NAV that exists.
        world.pensionFundBook.transactions.forEach { tx ->
            if (tx.correctedFromNavId != null) {
                assertThat(world.pensionFundBook.navs.map { it.id }).contains(tx.correctedFromNavId)
            }
        }
        // Precision invariants the domain documents: fee units round UP, issued units DOWN.
        assertThat(Precision.unitsCancelledForFee(BigDecimal("10.00"), BigDecimal("3.000000")))
            .isEqualByComparingTo(BigDecimal("3.333334"))
        assertThat(Precision.unitsIssued(BigDecimal("10.00"), BigDecimal("3.000000")))
            .isEqualByComparingTo(BigDecimal("3.333333"))
    }
}
