// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.BreakResolutionKind
import com.openbank.treasury.domain.model.BreakSide
import com.openbank.treasury.domain.model.BusinessDays
import com.openbank.treasury.domain.model.NostroBreak
import com.openbank.treasury.domain.model.Side
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Test Intelligence simulation gap: exercises [NostroBreakScenario] directly against the REAL
 * `treasury-service` nostro domain — the `NostroBreak` identity rules, `BusinessDays` aging, the
 * alert policy and the four-eyes resolution — so the harness's binding is proven by an actual
 * assertion, not merely by compiling.
 */
class NostroBreakScenarioTest {

    private fun newWorld(faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed = 42L, faultProfile), SimulationConfig())

    /** Step until a break matching [predicate] exists, bounded so a regression fails instead of looping. */
    private fun stepUntilBreak(world: World, predicate: (NostroBreak) -> Boolean): NostroBreak {
        repeat(80) {
            NostroBreakScenario.step(world)
            world.nostroBreaks.lastOrNull { predicate(it) }?.let { return it }
        }
        throw AssertionError("no break matching the predicate within 80 steps")
    }

    @Test
    fun `the desk settles every break into an alerted or resolved row`() {
        val world = newWorld()
        repeat(40) { NostroBreakScenario.step(world) }

        assertThat(world.nostroBreaks).isNotEmpty()
        assertThat(MoneyPathInvariants.nostroBreakHygiene.check(world)).isNull()
    }

    @Test
    fun `a manual resolution carries both pairs of eyes and closes the break`() {
        val world = newWorld()
        val resolved = stepUntilBreak(world) { it.manuallyResolved }

        assertThat(resolved.resolutionKind).isEqualTo(BreakResolutionKind.MANUAL)
        assertThat(resolved.proposedBy).isNotNull()
        assertThat(resolved.resolvedBy).isNotNull()
        assertThat(resolved.resolvedBy).isNotEqualTo(resolved.proposedBy)
        assertThat(resolved.resolutionReason).isNotBlank()
        assertThat(MoneyPathInvariants.nostroBreakHygiene.check(world)).isNull()
    }

    @Test
    fun `a write fault defers the resolution flow entirely, never half of it`() {
        // 100% write failures: breaks open and age, the alert sweep still runs, but no proposal
        // is ever written — so the no-overnight-proposal rule stays green.
        val world = newWorld(FaultProfile(writeFailureRate = 1.0))
        repeat(10) {
            NostroBreakScenario.step(world)
            assertThat(MoneyPathInvariants.nostroBreakHygiene.check(world)).isNull()
        }
        assertThat(world.nostroBreaks).isNotEmpty()
        assertThat(world.nostroBreaks).allMatch { !it.resolutionProposed }
    }

    @Test
    fun `the real aggregate refuses a non human proposer and a self approval`() {
        val now = Instant.now()
        val today = LocalDate.of(2026, 11, 18)
        val statementUuid = UUID.randomUUID()
        val open = NostroBreak(
            id = UUID.randomUUID(),
            breakKey = NostroBreak.statementKey(statementUuid, 1),
            iban = "CZ6508000000192000145399",
            glCode = "NOSTRO-CZK",
            currency = "CZK",
            side = BreakSide.STATEMENT,
            ourSide = Side.DEBIT,
            amount = java.math.BigDecimal("10000.00"),
            bookingDate = today,
            reference = null,
            statementUuid = statementUuid,
            statementSequence = 1,
            ledgerLineId = null,
            firstSeenOn = today,
        )

        val agent = Actor("agent:treasury-bot", ActorType.AI_AGENT)
        assertThatThrownBy { open.proposeResolution("Investigated: correspondent double-booked.", agent, now) }
            .isInstanceOf(ActorNotPermittedException::class.java)

        val human = Actor("treasury-analyst", ActorType.HUMAN)
        val proposed = open.proposeResolution("Investigated: correspondent double-booked.", human, now)
        assertThatThrownBy {
            proposed.approveResolution(human, today, checkNotNull(proposed.proposedAt))
        }.isInstanceOf(com.openbank.treasury.domain.model.FourEyesViolationException::class.java)

        // The real weekday calendar: Friday -> Monday is one business day, not three.
        assertThat(BusinessDays.between(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 16))).isEqualTo(1)
    }

    @Test
    fun `the hygiene invariant catches an aged break nobody alerted`() {
        // The DST mutation-equivalent proof: clear the alert timestamp the sweep would have set.
        val world = newWorld()
        val aged = stepUntilBreak(world) { it.open && it.alertedAt != null }
        assertThat(MoneyPathInvariants.nostroBreakHygiene.check(world)).isNull()

        val index = world.nostroBreaks.indexOf(aged)
        world.nostroBreaks[index] = aged.copy(alertedAt = null)

        assertThat(MoneyPathInvariants.nostroBreakHygiene.check(world)).isNotNull()
    }
}
