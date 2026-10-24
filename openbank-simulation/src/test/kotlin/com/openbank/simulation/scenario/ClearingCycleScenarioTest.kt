// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.clearing.domain.model.ClearingStatus
import com.openbank.clearing.infrastructure.client.NetSettlementJournalFactory
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Money-path clearing adoption: exercises [ClearingCycleScenario] directly against the REAL
 * `clearing-service` `ClearingItem`/`ClearingBatch`/`ClearingCycleResult` aggregates and the REAL
 * `NetSettlementJournalFactory` settleable-currency/GL-pair resolution, so the harness's binding
 * to clearing-service (beyond ledger/balance/SEPA/settlement/sct-inst) is proven by an actual
 * assertion, not merely by compiling — the same shape as [SctInstPaymentScenarioTest].
 *
 * The forced-fault branches are deterministic (`FaultProfile(1.0)` fires its draw with certainty,
 * and the write-failure draws precede the duplicate/conflict draws); the seeded draws (unsettleable
 * item, other-rail probe) are located by scanning fixed seeds, so the test stays green if the
 * scenario's draw order changes.
 */
class ClearingCycleScenarioTest {

    private fun newWorld(seed: Long, faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed, faultProfile), SimulationConfig())

    @Test
    fun `a happy-path cycle settles one batch with exactly its items and one net-settlement journal`() {
        val world = newWorld(seed = 7L)
        ClearingCycleScenario.step(world)

        val batch = world.clearing.batches.single()
        assertThat(batch.status).isEqualTo(ClearingStatus.SETTLED)
        assertThat(batch.settledAt).isNotNull()
        assertThat(batch.totalDebit).isEqualByComparingTo(batch.totalCredit)
        assertThat(batch.netPosition).isEqualByComparingTo(BigDecimal.ZERO)
        val items = world.clearing.itemsOf(batch.id)
        assertThat(items.size).isEqualTo(batch.itemCount)
        assertThat(items).allMatch { it.status == ClearingStatus.SETTLED }
        val posting = world.clearing.postings[batch.id]
        assertThat(posting).isNotNull()
        assertThat(posting!!.amount).isEqualByComparingTo(batch.totalDebit)
        assertThat(posting.reversed).isFalse()
        // exactly one balanced GL-to-GL journal, no customer projection
        assertThat(world.ledger.postedCount()).isEqualTo(1)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.clearingCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.clearingBatchConservation.check(world)).isNull()
    }

    @Test
    fun `an unsettleable-currency item stays pending and is reported by the cycle, not dropped`() {
        val world = worldWithUnsettleableReported()
        // items that a cycle ran over but left pending must ALL be unsettleable-currency items
        // counted in that rail's latest cycle result — the #11974 reported-not-dropped law.
        val swept = world.clearing.items.filter {
            it.status == ClearingStatus.PENDING &&
                world.clearing.cycleEpoch(it.rail!!) > world.clearing.seedEpochOf(it.id)
        }
        assertThat(swept).isNotEmpty()
        swept.forEach { item ->
            assertThat(NetSettlementJournalFactory.settleableCurrencies).doesNotContain(item.currency)
        }
        assertThat(MoneyPathInvariants.clearingCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
    }

    @Test
    fun `a write fault at batch open fails the selected items without opening a batch`() {
        // 100% write-failure rate forces the open fault; production would leave the items PENDING
        // for the next sweep, the DST maps them to the FAILED terminal (see the scenario KDoc).
        val world = newWorld(seed = 42L, faultProfile = FaultProfile(writeFailureRate = 1.0))
        ClearingCycleScenario.step(world)

        assertThat(world.clearing.batches).isEmpty()
        val settleable = NetSettlementJournalFactory.settleableCurrencies
        assertThat(world.clearing.items.filter { it.currency in settleable })
            .allMatch { it.status == ClearingStatus.FAILED }
        assertThat(world.ledger.postedCount()).isEqualTo(0)
        assertThat(MoneyPathInvariants.clearingCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.clearingBatchConservation.check(world)).isNull()
    }

    @Test
    fun `a write fault at the settlement post fails the batch without moving money`() {
        // 50% write-failure rate: batches whose open draw succeeded but settle draw failed are
        // FAILED with no journal; located by scanning fixed seeds.
        val world = worldWithBatchStatus(ClearingStatus.FAILED, FaultProfile(writeFailureRate = 0.5))
        val batch = world.clearing.batches.single { it.status == ClearingStatus.FAILED }
        assertThat(world.clearing.postings[batch.id]).isNull()
        assertThat(world.clearing.itemsOf(batch.id)).allMatch { it.status == ClearingStatus.FAILED }
        assertThat(world.ledger.postedCount()).isEqualTo(0)
        assertThat(MoneyPathInvariants.clearingCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.clearingBatchConservation.check(world)).isNull()

        // mutation: a settlement journal for a FAILED batch would be money moved for a dead batch
        world.clearing.recordPosting(batch.id, UUID.randomUUID(), batch.totalDebit)
        assertThat(MoneyPathInvariants.clearingBatchConservation.check(world)).isNotNull()
    }

    @Test
    fun `a post-commit conflict reverses the durable journal and reverses the batch`() {
        // 100% lock-conflict rate forces the reversal branch after the journal committed.
        val world = newWorld(seed = 42L, faultProfile = FaultProfile(lockConflictRate = 1.0))
        ClearingCycleScenario.step(world)

        val batch = world.clearing.batches.single()
        assertThat(batch.status).isEqualTo(ClearingStatus.REVERSED)
        assertThat(world.clearing.postings[batch.id]!!.reversed).isTrue()
        assertThat(world.clearing.itemsOf(batch.id)).allMatch { it.status == ClearingStatus.REVERSED }
        // original + reversal both durable; the GL legs still net to zero per currency
        assertThat(world.ledger.postedCount()).isEqualTo(2)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.clearingCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.clearingBatchConservation.check(world)).isNull()
    }

    @Test
    fun `a duplicate net-settlement delivery collapses onto the one booked journal`() {
        // 100% duplicate-event rate redelivers the settlement command after it booked.
        val world = newWorld(seed = 42L, faultProfile = FaultProfile(duplicateEventRate = 1.0))
        ClearingCycleScenario.step(world)

        val batch = world.clearing.batches.single()
        assertThat(batch.status).isEqualTo(ClearingStatus.SETTLED)
        assertThat(world.ledger.postedCount()).isEqualTo(1)
        assertThat(MoneyPathInvariants.clearingBatchConservation.check(world)).isNull()
    }

    @Test
    fun `a rail's cycle settles only its own items — other-rail items wait for their rail`() {
        val world = worldWithOtherRailItem()
        val probedId = world.clearing.items.single { it.rail != world.clearing.cycles.last().rail }.id
        assertThat(itemById(world, probedId).status).isEqualTo(ClearingStatus.PENDING)
        assertThat(MoneyPathInvariants.clearingCompleteness.check(world)).isNull()

        // keep cycling until the probed rail's cycle runs: the item must then settle, and the
        // completeness invariant must hold on every intermediate step.
        var steps = 0
        while (itemById(world, probedId).status == ClearingStatus.PENDING && steps < MAX_RAIL_SWEEP_STEPS) {
            ClearingCycleScenario.step(world)
            assertThat(MoneyPathInvariants.clearingCompleteness.check(world)).isNull()
            steps++
        }
        assertThat(itemById(world, probedId).status).isEqualTo(ClearingStatus.SETTLED)
    }

    @Test
    fun `the completeness invariant catches a silently dropped settleable item`() {
        val world = newWorld(seed = 7L)
        ClearingCycleScenario.step(world)
        // mutation: a settled item whose rail has cycled is neither batched nor reported — the
        // exact "skipped by the cycle" defect class #11974 designed the reporting path to prevent.
        val item = world.clearing.items.first { it.status == ClearingStatus.SETTLED }
        world.clearing.replaceItem(item.copy(status = ClearingStatus.PENDING))
        assertThat(MoneyPathInvariants.clearingCompleteness.check(world)).isNotNull()
    }

    /** Scan fixed seeds until a cycle reports an unsettleable currency, so the test survives draw-order changes. */
    private fun worldWithUnsettleableReported(): World {
        for (seed in 0L..200L) {
            val world = newWorld(seed)
            ClearingCycleScenario.step(world)
            if (world.clearing.cycles.any { it.unsettleablePending.isNotEmpty() }) return world
        }
        error("no seed in 0..200 drew an unsettleable item — is UNSETTLEABLE_ITEM_RATE misconfigured?")
    }

    /** Scan fixed seeds until a batch reaches [status], so the test survives draw-order changes. */
    private fun worldWithBatchStatus(status: ClearingStatus, profile: FaultProfile): World {
        for (seed in 0L..500L) {
            val world = newWorld(seed, profile)
            ClearingCycleScenario.step(world)
            if (world.clearing.batches.any { it.status == status }) return world
        }
        error("no seed in 0..500 produced a $status batch under $profile")
    }

    /** Scan fixed seeds until the other-rail probe fired, so the test survives draw-order changes. */
    private fun worldWithOtherRailItem(): World {
        for (seed in 0L..200L) {
            val world = newWorld(seed)
            ClearingCycleScenario.step(world)
            val cycledRail = world.clearing.cycles.single().rail
            if (world.clearing.items.any { it.rail != cycledRail }) return world
        }
        error("no seed in 0..200 drew the other-rail probe — is OTHER_RAIL_ITEM_RATE misconfigured?")
    }

    private fun itemById(world: World, id: UUID) = world.clearing.items.first { it.id == id }

    companion object {
        private const val MAX_RAIL_SWEEP_STEPS = 20
    }
}
