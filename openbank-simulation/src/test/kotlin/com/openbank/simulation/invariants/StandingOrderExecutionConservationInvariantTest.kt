// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.invariants

import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import com.openbank.standingorder.domain.model.Frequency
import com.openbank.standingorder.domain.model.PaymentType
import com.openbank.standingorder.domain.model.StandingOrder
import com.openbank.standingorder.domain.model.StandingOrderStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Unit coverage for [MoneyPathInvariants.standingOrderExecutionConservation], in isolation from
 * the seeded runner — building a [World] by hand and poking `StandingOrderBook` directly proves
 * the invariant's own comparison logic (per-order, not netted across orders; ONCE discipline
 * separate from the posted-amount law) independent of how [World.standingOrders] gets populated.
 * `com.openbank.simulation.scenario.StandingOrderExecutionScenario` (wired into
 * `SimulationRunner.runSeed`) is what feeds real sweep/execution activity into
 * [World.standingOrders] during an actual seeded run — see `StandingOrderExecutionScenarioTest` /
 * `DstSimulationTest` for the integrated, non-vacuous proof.
 */
class StandingOrderExecutionConservationInvariantTest {

    private fun world(): World = World(SimulationContext(seed = 1L, FaultProfile.NONE), SimulationConfig())

    private fun order(id: UUID, frequency: Frequency, amountMinorUnits: Long = 10_000) = StandingOrder(
        id = id,
        idempotencyKey = "test-$id",
        partyId = UUID.randomUUID(),
        debitAccountId = UUID.randomUUID(),
        debtorIban = "CZ6508000000192000145399",
        debtorName = "Test Debtor",
        creditorIban = "CZ6508000000192000145400",
        creditorName = "Test Creditor",
        creditorBic = null,
        amountMinorUnits = amountMinorUnits,
        currency = "CZK",
        frequency = frequency,
        paymentType = PaymentType.SEPA_CREDIT,
        remittanceInfo = null,
        startDate = LocalDate.parse("2026-01-01"),
        endDate = null,
        nextExecutionDate = LocalDate.parse("2026-01-01"),
        lastExecutionDate = null,
        executionCount = 0,
        failureCount = 0,
        status = StandingOrderStatus.ACTIVE,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    @Test
    fun `holds trivially when no orders exist`() {
        assertThat(MoneyPathInvariants.standingOrderExecutionConservation.check(world())).isNull()
    }

    @Test
    fun `holds when every confirmed execution date posted exactly the order amount`() {
        val w = world()
        val id = UUID.randomUUID()
        val order = order(id, Frequency.DAILY, amountMinorUnits = 5_000)
        w.standingOrders.place(order)
        val day1 = LocalDate.parse("2026-01-01")
        val day2 = LocalDate.parse("2026-01-02")
        w.standingOrders.recordAttempt(id, day1)
        w.standingOrders.recordAttempt(id, day2)
        w.standingOrders.recordConfirmed(id, day1)
        w.standingOrders.recordConfirmed(id, day2)
        w.standingOrders.recordPosted(id, BigDecimal("50.00"))
        w.standingOrders.recordPosted(id, BigDecimal("50.00"))

        assertThat(MoneyPathInvariants.standingOrderExecutionConservation.check(w)).isNull()
    }

    @Test
    fun `a failure that moved money anyway is a violation — posted exceeds confirmed executions`() {
        val w = world()
        val id = UUID.randomUUID()
        w.standingOrders.place(order(id, Frequency.DAILY, amountMinorUnits = 5_000))
        val day1 = LocalDate.parse("2026-01-01")
        w.standingOrders.recordAttempt(id, day1)
        // No confirmation for day1, yet 50.00 posted — the replay/duplication defect class.
        w.standingOrders.recordPosted(id, BigDecimal("50.00"))

        val violation = MoneyPathInvariants.standingOrderExecutionConservation.check(w)
        assertThat(violation).isNotNull()
        assertThat(violation?.invariant).isEqualTo("standing-order-execution-conservation")
    }

    @Test
    fun `a confirmed execution that posted less than the order amount is a violation`() {
        val w = world()
        val id = UUID.randomUUID()
        w.standingOrders.place(order(id, Frequency.DAILY, amountMinorUnits = 5_000))
        val day1 = LocalDate.parse("2026-01-01")
        w.standingOrders.recordAttempt(id, day1)
        w.standingOrders.recordConfirmed(id, day1)
        w.standingOrders.recordPosted(id, BigDecimal("49.99"))

        assertThat(MoneyPathInvariants.standingOrderExecutionConservation.check(w)).isNotNull()
    }

    @Test
    fun `a ONCE order attempted on two distinct dates is a violation even when money conserves`() {
        val w = world()
        val id = UUID.randomUUID()
        w.standingOrders.place(order(id, Frequency.ONCE, amountMinorUnits = 5_000))
        val day1 = LocalDate.parse("2026-01-01")
        val day2 = LocalDate.parse("2026-01-02")
        w.standingOrders.recordAttempt(id, day1)
        w.standingOrders.recordAttempt(id, day2)
        w.standingOrders.recordConfirmed(id, day1)
        w.standingOrders.recordConfirmed(id, day2)
        w.standingOrders.recordPosted(id, BigDecimal("50.00"))
        w.standingOrders.recordPosted(id, BigDecimal("50.00"))

        assertThat(MoneyPathInvariants.standingOrderExecutionConservation.check(w))
            .isNotNull()
        assertThat(MoneyPathInvariants.standingOrderExecutionConservation.check(w)?.detail)
            .contains("ONCE")
    }

    @Test
    fun `a confirmed ONCE order still ACTIVE is the issue-12839 violation`() {
        val w = world()
        val id = UUID.randomUUID()
        w.standingOrders.place(order(id, Frequency.ONCE, amountMinorUnits = 5_000))
        val day1 = LocalDate.parse("2026-01-01")
        w.standingOrders.recordAttempt(id, day1)
        w.standingOrders.recordConfirmed(id, day1)
        w.standingOrders.recordPosted(id, BigDecimal("50.00"))
        // The defect shape from issue #12839: the payment went through (confirmed, posted) but
        // the order never completed — it stays ACTIVE and would be re-due on the next sweep.
        w.standingOrders.replace(w.standingOrders.view(id).order!!.copy(status = StandingOrderStatus.ACTIVE))

        assertThat(MoneyPathInvariants.standingOrderExecutionConservation.check(w))
            .isNotNull()
        assertThat(MoneyPathInvariants.standingOrderExecutionConservation.check(w)?.detail)
            .contains("COMPLETED")
    }

    @Test
    fun `a failed-then-retried ONCE order on the same date stays within the law`() {
        val w = world()
        val id = UUID.randomUUID()
        w.standingOrders.place(order(id, Frequency.ONCE, amountMinorUnits = 5_000))
        val day1 = LocalDate.parse("2026-01-01")
        w.standingOrders.recordAttempt(id, day1)
        // First attempt declined, retry replays the SAME date (idempotent), then succeeds.
        w.standingOrders.recordConfirmed(id, day1)
        w.standingOrders.recordPosted(id, BigDecimal("50.00"))
        w.standingOrders.replace(
            w.standingOrders.view(id).order!!.copy(status = StandingOrderStatus.COMPLETED),
        )

        assertThat(MoneyPathInvariants.standingOrderExecutionConservation.check(w)).isNull()
    }
}
