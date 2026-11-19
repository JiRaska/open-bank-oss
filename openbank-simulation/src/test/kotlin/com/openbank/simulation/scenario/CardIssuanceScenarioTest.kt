// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.cardissuance.domain.model.CardStatus
import com.openbank.cardissuance.domain.model.CardType
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Test Intelligence simulation gap: exercises [CardIssuanceScenario] directly against the REAL
 * `card-issuance-service` domain — the `Card` status machine, its limit/control invariants and
 * the ADR-0249 D2 delegation link — so the harness's binding is proven by an actual assertion.
 */
class CardIssuanceScenarioTest {

    private fun newWorld(faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed = 42L, faultProfile), SimulationConfig())

    /** Step until the last card matches [predicate], bounded so a regression fails instead of looping. */
    private fun stepUntilCard(world: World, predicate: (com.openbank.cardissuance.domain.model.Card) -> Boolean) {
        repeat(60) {
            CardIssuanceScenario.step(world)
            if (predicate(world.cards.lastOrNull() ?: return@repeat)) return
        }
        throw AssertionError("no card matching the predicate within 60 steps")
    }

    @Test
    fun `every lifecycle path settles into a consistent card`() {
        val world = newWorld()
        repeat(40) { CardIssuanceScenario.step(world) }

        assertThat(world.cards).isNotEmpty()
        assertThat(MoneyPathInvariants.cardIssuanceCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.delegatedGrantIntegrity.check(world)).isNull()
    }

    @Test
    fun `a single use card consumes itself and records the promise kept`() {
        val world = newWorld()
        stepUntilCard(world) {
            it.cardType == CardType.SINGLE_USE && it.status == CardStatus.CONSUMED
        }
        val card = world.cards.last()
        assertThat(card.closedReason)
            .isEqualTo(com.openbank.cardissuance.domain.model.CardClosedReason.SINGLE_USE_CONSUMED)
        assertThat(MoneyPathInvariants.cardIssuanceCompleteness.check(world)).isNull()
    }

    @Test
    fun `a lost card is blocked then cancelled and the block reason survives`() {
        val world = newWorld()
        stepUntilCard(world) { it.status == CardStatus.CANCELLED }

        val card = world.cards.last()
        assertThat(card.closedReason).isNull() // CANCELLED carries the block reason, not a closed reason
        assertThat(card.blockedReason).isNotBlank()
        assertThat(card.blockedAt).isNotNull()
        assertThat(MoneyPathInvariants.cardIssuanceCompleteness.check(world)).isNull()
    }

    @Test
    fun `a write fault at activation leaves a legitimate pending card`() {
        val world = newWorld(FaultProfile(writeFailureRate = 1.0))
        CardIssuanceScenario.step(world)

        assertThat(world.cards.single().status).isEqualTo(CardStatus.PENDING)
        assertThat(MoneyPathInvariants.cardIssuanceCompleteness.check(world)).isNull()
    }

    @Test
    fun `the real aggregate refuses illegal transitions and limits`() {
        val world = newWorld()
        CardIssuanceScenario.step(world)
        val active = world.cards.last()
        if (active.status != CardStatus.ACTIVE) {
            CardIssuanceScenario.step(world)
        }
        val card = world.cards.last { it.status == CardStatus.ACTIVE }

        // Only PENDING activates; only ACTIVE suspends; terminal cards re-limit never.
        assertThatThrownBy { card.activate(Instant.now()) }.isInstanceOf(IllegalArgumentException::class.java)
        val consumed = card.copy(
            cardType = CardType.SINGLE_USE,
            status = CardStatus.CONSUMED,
        )
        assertThatThrownBy { consumed.withLimits(1, 2, Instant.now()) }
            .isInstanceOf(IllegalArgumentException::class.java)

        // The REAL limit rule the scenario keeps: daily must not exceed monthly.
        assertThatThrownBy { card.withLimits(200, 100, Instant.now()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(card.withLimits(100, 200, Instant.now()).dailyLimitMinorUnits).isEqualTo(100)
    }

    @Test
    fun `the completeness invariant catches a card left live past its own expiry`() {
        // The DST mutation-equivalent proof: a card that quietly skipped the expiry sweep is
        // exactly what the invariant exists to surface.
        val world = newWorld()
        repeat(10) { CardIssuanceScenario.step(world) }
        assertThat(MoneyPathInvariants.cardIssuanceCompleteness.check(world)).isNull()

        val index = world.cards.indexOfFirst { it.status == CardStatus.ACTIVE }
        org.junit.jupiter.api.Assumptions.assumeTrue(index >= 0, "no active card in this draw")
        val expired = world.cards[index].copy(expiresAt = Instant.EPOCH)
        world.cards[index] = expired

        assertThat(MoneyPathInvariants.cardIssuanceCompleteness.check(world)).isNotNull()
    }
}
