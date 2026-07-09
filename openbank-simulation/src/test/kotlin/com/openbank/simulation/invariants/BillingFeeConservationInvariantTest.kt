// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.invariants

import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.model.BillingFeeKey
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Unit coverage for [MoneyPathInvariants.billingFeeConservation] (ADR-0143 phase 2d), in
 * isolation from the seeded runner — building a [World] by hand and poking
 * `recordAssessed`/`recordPosted` directly proves the invariant's own comparison logic is
 * correct (per-key, not netted across fees) independent of how [World.billingFees] gets
 * populated. `com.openbank.simulation.scenario.FeeBillingScenario` (wired into
 * `SimulationRunner.runSeed`) is what feeds real assess/post/reverse activity into
 * [World.billingFees] during an actual seeded run — see `FeeBillingScenarioTest` /
 * `DstSimulationTest` for the integrated, non-vacuous proof that a broken posting leg is
 * actually caught end-to-end.
 */
class BillingFeeConservationInvariantTest {

    private fun world(): World = World(SimulationContext(seed = 1L, FaultProfile.NONE), SimulationConfig())

    @Test
    fun `holds trivially when nothing has been assessed or posted`() {
        assertThat(MoneyPathInvariants.billingFeeConservation.check(world())).isNull()
    }

    @Test
    fun `holds when every assessed fee's amount was fully posted`() {
        val w = world()
        val key =
            BillingFeeKey(cycleId = "2026-07", accountId = UUID.randomUUID(), feeId = "maintenance", currency = "CZK")
        w.billingFees.recordAssessed(key, BigDecimal("150.00"))
        w.billingFees.recordPosted(key, BigDecimal("150.00"))

        assertThat(MoneyPathInvariants.billingFeeConservation.check(w)).isNull()
    }

    @Test
    fun `holds for a waived fee — assessed zero, posts nothing`() {
        val w = world()
        val key =
            BillingFeeKey(cycleId = "2026-07", accountId = UUID.randomUUID(), feeId = "maintenance", currency = "CZK")
        w.billingFees.recordAssessed(key, BigDecimal.ZERO)

        assertThat(MoneyPathInvariants.billingFeeConservation.check(w)).isNull()
    }

    @Test
    fun `a multi-fee product tracks each fee independently — one posted, one still pending, is a violation`() {
        val w = world()
        val accountId = UUID.randomUUID()
        val maintenance = BillingFeeKey("2026-07", accountId, "maintenance", "CZK")
        val excessWithdrawal = BillingFeeKey("2026-07", accountId, "excess-withdrawal", "CZK")
        w.billingFees.recordAssessed(maintenance, BigDecimal("50"))
        w.billingFees.recordPosted(maintenance, BigDecimal("50"))
        w.billingFees.recordAssessed(excessWithdrawal, BigDecimal("25"))
        // excessWithdrawal never posted (e.g. stuck PENDING) — the invariant must catch it
        // per-fee, not net it against maintenance's balanced leg (the ADR-0143 feeId-dimension
        // reasoning applied to the DST invariant itself).

        val violation = MoneyPathInvariants.billingFeeConservation.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.invariant).isEqualTo("billing-fee-conservation")
        assertThat(violation.detail).contains("excess-withdrawal")
    }

    @Test
    fun `an under-post (posted less than assessed) is caught`() {
        val w = world()
        val key = BillingFeeKey("2026-07", UUID.randomUUID(), "maintenance", "CZK")
        w.billingFees.recordAssessed(key, BigDecimal("150.00"))
        w.billingFees.recordPosted(key, BigDecimal("100.00"))

        val violation = MoneyPathInvariants.billingFeeConservation.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.detail).contains("assessed=150.00").contains("posted=100.00")
    }

    @Test
    fun `an over-post (a replay double-counted) is caught`() {
        val w = world()
        val key = BillingFeeKey("2026-07", UUID.randomUUID(), "maintenance", "CZK")
        w.billingFees.recordAssessed(key, BigDecimal("150.00"))
        w.billingFees.recordPosted(key, BigDecimal("150.00"))
        w.billingFees.recordPosted(key, BigDecimal("150.00")) // simulates a non-deduplicated replay

        val violation = MoneyPathInvariants.billingFeeConservation.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.detail).contains("assessed=150.00").contains("posted=300.00")
    }

    @Test
    fun `is registered in the Layer-3 invariant set`() {
        assertThat(MoneyPathInvariants.ALL.map { it.name }).contains("billing-fee-conservation")
    }
}
