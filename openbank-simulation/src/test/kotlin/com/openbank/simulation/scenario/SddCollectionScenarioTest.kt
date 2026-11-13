// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.sdd.domain.lifecycle.MandateLifecycle
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The SDD collection scenario driven directly against the REAL `SddMandate` aggregate and the
 * REAL `MandateLifecycle` state machine, mirroring [SepaSettlementScenarioTest]'s "real aggregate,
 * real assertions" convention (issue #267 / ADR-0100, extended to the direct-debit rail).
 */
class SddCollectionScenarioTest {

    private fun newWorld(faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed = 42L, faultProfile), SimulationConfig())

    @Test
    fun `a happy-path collection stamps the mandate and settles the money path`() {
        val world = newWorld()
        SddCollectionScenario.step(world)
        world.context.scheduler.drain()

        assertThat(world.sddMandates).hasSize(1)
        val mandate = world.sddMandates.single()
        assertThat(world.sddCollections.postedCollections(mandate.id)).isEqualTo(1)
        assertThat(mandate.lastCollectionDate).isNotNull()
        assertThat(world.sddCollections.lastCollectionDate(mandate.id)).isEqualTo(mandate.lastCollectionDate)
        assertThat(world.ledger.postedCount()).isEqualTo(1)
        assertThat(MoneyPathInvariants.sddCollectionCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.projectionConsistency.check(world)).isNull()
    }

    @Test
    fun `an explicitly FRST mandate advances to RCUR when that same mandate is collected`() {
        val world = World(SimulationContext(seed = 0L, FaultProfile.NONE), SimulationConfig())
        val mandate = freshMandate(world)
        // This is an explicit transition precondition, not proof of randomized factory defaults.
        assertThat(mandate.sequenceType).isEqualTo(SequenceType.FRST)
        assertThat(mandate.lastCollectionDate).isNull()
        world.sddMandates.add(mandate)

        SddCollectionScenario.step(world)
        world.context.scheduler.drain()

        val actual = world.sddMandates.single()
        assertThat(actual.id).isEqualTo(mandate.id)
        assertThat(actual.sequenceType).isEqualTo(SequenceType.RCUR)
        assertThat(actual.lastCollectionDate).isEqualTo(today(world))
        assertThat(world.sddCollections.postedCollections(mandate.id)).isEqualTo(1)
        assertThat(world.sddCollections.lastCollectionDate(mandate.id)).isEqualTo(actual.lastCollectionDate)
        assertThat(world.ledger.postedCount()).isEqualTo(1)
        assertThat(MoneyPathInvariants.sddCollectionCompleteness.check(world)).isNull()
    }

    @Test
    fun `a lifecycle collection stamp without its book outcome is a completeness violation`() {
        val world = newWorld()
        val mandate = MandateLifecycle.recordCollection(freshMandate(world), today(world))
        world.sddMandates.add(mandate)
        assertThat(world.sddCollections.postedCollections(mandate.id)).isZero()

        val violation = MoneyPathInvariants.sddCollectionCompleteness.check(world)
        assertThat(violation).isNotNull()
        assertThat(violation?.invariant).isEqualTo("sdd-collection-completeness")
    }

    @Test
    fun `a never-collected mandate legitimately has no book outcome`() {
        val world = newWorld()
        val mandate = freshMandate(world)
        world.sddMandates.add(mandate)
        assertThat(mandate.lastCollectionDate).isNull()
        assertThat(world.sddCollections.postedCollections(mandate.id)).isZero()
        assertThat(MoneyPathInvariants.sddCollectionCompleteness.check(world)).isNull()
    }

    @Test
    fun `an active mandate idle for 37 months expires before any collection write`() {
        val world = World(SimulationContext(seed = 0L, FaultProfile.NONE), SimulationConfig())
        val mandate = freshMandate(world, signatureDate = today(world).minusMonths(37))
        world.sddMandates.add(mandate)

        SddCollectionScenario.step(world)
        world.context.scheduler.drain()

        val actual = world.sddMandates.single()
        assertThat(actual.id).isEqualTo(mandate.id)
        assertThat(actual.status).isEqualTo(MandateStatus.EXPIRED)
        assertThat(actual.lastCollectionDate).isNull()
        assertThat(world.sddCollections.postedCollections(mandate.id)).isZero()
        assertThat(world.sddCollections.attemptedFailureCount(mandate.id)).isZero()
        assertThat(world.ledger.postedCount()).isZero()
        assertThat(MoneyPathInvariants.sddCollectionCompleteness.check(world)).isNull()
    }

    private fun today(world: World): LocalDate =
        LocalDate.ofInstant(world.context.clock.instant(), ZoneId.of("Europe/Prague"))

    // Explicit domain fixture; it consumes no RNG values and claims no production birth behavior.
    private fun freshMandate(world: World, signatureDate: LocalDate = today(world)): SddMandate = SddMandate(
        id = UUID(0L, 501L),
        accountId = world.customerAccounts.first(),
        debtorIban = "SIM-DEBTOR",
        creditorIdentifier = "SIM-CREDITOR",
        umr = "SIM-MANDATE",
        scheme = SddScheme.CORE,
        sequenceType = SequenceType.FRST,
        creditorName = "Sim Creditor",
        debtorName = "Sim Debtor",
        signatureDate = signatureDate,
        status = MandateStatus.ACTIVE,
        b2bConfirmed = false,
        lastCollectionDate = null,
        lastPreNotificationDate = null,
        createdAt = signatureDate.atStartOfDay(ZoneId.of("Europe/Prague")).toInstant(),
    )

    @Test
    fun `a B2B mandate is confirmed through the debtor-bank gate before it can be collected`() {
        var sawB2b = false
        for (seed in 0L until 40L) {
            val world = World(SimulationContext(seed, FaultProfile.NONE), SimulationConfig())
            SddCollectionScenario.step(world)
            val mandate = world.sddMandates.single()
            if (mandate.scheme == SddScheme.B2B) {
                sawB2b = true
                // The scenario pays the confirmation up front: the mandate is ACTIVE (never left
                // PENDING_CONFIRMATION to reach the collection path) and, if money moved, stamped.
                assertThat(mandate.status).isEqualTo(MandateStatus.ACTIVE)
                assertThat(MoneyPathInvariants.sddCollectionCompleteness.check(world)).isNull()
            }
        }
        assertThat(sawB2b).isTrue()
    }

    @Test
    fun `a write-failure fault leaves no journal and records the failed attempt`() {
        val world = newWorld(FaultProfile(writeFailureRate = 1.0))
        SddCollectionScenario.step(world)
        world.context.scheduler.drain()

        val mandate = world.sddMandates.single()
        assertThat(world.ledger.postedCount()).isZero()
        assertThat(mandate.lastCollectionDate).isNull()
        assertThat(world.sddCollections.attemptedFailureCount(mandate.id)).isEqualTo(1)
        assertThat(MoneyPathInvariants.sddCollectionCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.projectionConsistency.check(world)).isNull()
    }

    @Test
    fun `a post-commit conflict reverses the durable journal and records the failed attempt`() {
        val world = newWorld(FaultProfile(lockConflictRate = 1.0))
        SddCollectionScenario.step(world)
        world.context.scheduler.drain()

        val mandate = world.sddMandates.single()
        assertThat(mandate.lastCollectionDate).isNull()
        assertThat(world.ledger.postedCount()).isEqualTo(2)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.sddCollectionCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.projectionConsistency.check(world)).isNull()
    }

    @Test
    fun `adversarial faults keep every mandate reconciled with the book across 25 steps`() {
        val world = newWorld(FaultProfile.ADVERSARIAL)
        repeat(25) {
            SddCollectionScenario.step(world)
            world.context.scheduler.drain()
            assertThat(MoneyPathInvariants.sddCollectionCompleteness.check(world)).isNull()
        }
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.noNegativeBalance.check(world)).isNull()
        assertThat(MoneyPathInvariants.projectionConsistency.check(world)).isNull()
    }
}
