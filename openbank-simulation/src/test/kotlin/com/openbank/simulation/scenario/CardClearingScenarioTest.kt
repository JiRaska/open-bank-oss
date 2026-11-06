// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.PresentmentOutcome
import com.openbank.cardprocessing.domain.model.PresentmentRefusal
import com.openbank.cardprocessing.domain.policy.AuthorizationLifecycle
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Issue #14366 (Test Intelligence simulation gap, money path): exercises [CardClearingScenario]
 * directly against the REAL `card-processing` domain — the `CardAuthorization` aggregate and
 * the pure `AuthorizationLifecycle` policy — so the harness's binding to the service is proven
 * by an actual assertion, not merely by compiling.
 *
 * The scenario draws its acquirer action uniformly per step, so each test steps the world until
 * the shape it needs exists (bounded), instead of pinning a seed to a specific draw order.
 */
class CardClearingScenarioTest {

    private fun newWorld(faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed = 42L, faultProfile), SimulationConfig())

    /** Step until [predicate] finds an authorization, bounded so a regression fails instead of looping. */
    private fun stepUntil(world: World, predicate: (CardAuthorization) -> Boolean): CardAuthorization {
        repeat(60) {
            CardClearingScenario.step(world)
            world.cardAuthorizations.lastOrNull()?.let { if (predicate(it)) return it }
        }
        throw AssertionError("no authorization matching the predicate within 60 steps")
    }

    @Test
    fun `a full presentment clears the real authorization and posts exactly once`() {
        val world = newWorld()
        val cleared = stepUntil(world) { it.status == AuthorizationStatus.CLEARED }

        assertThat(cleared.clearedAmountMinorUnits).isEqualTo(cleared.amountMinorUnits)
        assertThat(cleared.heldAmountMinorUnits).isEqualTo(0)
        assertThat(world.cardClearings.postedAmount(cleared.id))
            .isEqualTo(cleared.clearedAmountMinorUnits)

        assertThat(MoneyPathInvariants.cardAuthorizationCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.cardClearingConservation.check(world)).isNull()
    }

    @Test
    fun `a write fault at authorize records a real DECLINED with its reason`() {
        // 100% write-failure rate forces the recorded-decline branch every time.
        val world = newWorld(FaultProfile(writeFailureRate = 1.0))
        CardClearingScenario.step(world)

        val authorization = world.cardAuthorizations.single()
        assertThat(authorization.status).isEqualTo(AuthorizationStatus.DECLINED)
        assertThat(authorization.declineReason).isNotNull()
        assertThat(authorization.clearedAmountMinorUnits).isEqualTo(0)
        // A decline never holds funds and never posts money.
        assertThat(authorization.heldAmountMinorUnits).isEqualTo(0)
        assertThat(world.cardClearings.postedIds()).isEmpty()
        assertThat(MoneyPathInvariants.cardAuthorizationCompleteness.check(world)).isNull()
    }

    @Test
    fun `a partial presentment leaves the hold partially cleared until the reversal releases it`() {
        val world = newWorld()
        // A partial-then-reverse leaves REVERSED with exactly half cleared — strictly less than
        // the authorised amount, which distinguishes it from the full-clear-then-compensation
        // branch (REVERSED with the FULL amount cleared and posted).
        val reversed = stepUntil(world) {
            it.status == AuthorizationStatus.REVERSED && it.clearedAmountMinorUnits < it.amountMinorUnits
        }

        val expectedCleared = reversed.amountMinorUnits / 2
        assertThat(reversed.clearedAmountMinorUnits).isEqualTo(expectedCleared)
        assertThat(reversed.heldAmountMinorUnits).isEqualTo(0)
        assertThat(world.cardClearings.postedAmount(reversed.id)).isEqualTo(expectedCleared)
        assertThat(MoneyPathInvariants.cardClearingConservation.check(world)).isNull()
    }

    @Test
    fun `the real lifecycle policy refuses over-clearing, currency mismatch and early expiry`() {
        // The scenario drives the REAL AuthorizationLifecycle; if a regression loosened a guard
        // (over-clear tolerance, currency check, expiry window), this assertion against the REAL
        // policy is what catches the drift. An APPROVED hold still inside its window exercises
        // all three refusals.
        val world = newWorld()
        val holding = stepUntil(world) { it.status == AuthorizationStatus.APPROVED }
        val clock = world.context.clock

        val overClear = AuthorizationLifecycle.clear(
            holding,
            holding.amountMinorUnits + 1,
            holding.currencyCode,
            clock,
        )
        assertThat(overClear).isEqualTo(PresentmentOutcome.Refused(PresentmentRefusal.EXCEEDS_AUTHORIZED_AMOUNT))

        val wrongCurrency = AuthorizationLifecycle.clear(holding, 1, "EUR", clock)
        assertThat(wrongCurrency).isEqualTo(PresentmentOutcome.Refused(PresentmentRefusal.CURRENCY_MISMATCH))

        val earlyExpiry = AuthorizationLifecycle.expire(holding, clock)
        assertThat(earlyExpiry).isEqualTo(PresentmentOutcome.Refused(PresentmentRefusal.NOT_YET_EXPIRED))

        // The hold semantics the conservation invariant relies on, pinned on the real aggregate:
        // a hold counts in full for limit purposes; a cleared authorisation counts only what cleared.
        assertThat(holding.heldAmountMinorUnits)
            .isEqualTo(holding.amountMinorUnits - holding.clearedAmountMinorUnits)
        assertThat(holding.effectiveSpendMinorUnits).isEqualTo(holding.amountMinorUnits)
    }
}
