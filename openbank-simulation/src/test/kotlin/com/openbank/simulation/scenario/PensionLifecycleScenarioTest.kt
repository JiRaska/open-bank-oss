// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.domain.statecontribution.CzStateContributionCalendar
import com.openbank.pension.domain.statecontribution.ReturnCause
import com.openbank.pension.domain.statecontribution.ReturnStatus
import com.openbank.pension.domain.statecontribution.StateContributionReturn
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Issue #14366 (Test Intelligence simulation gap, money path): exercises [PensionLifecycleScenario]
 * directly against the REAL `pension-service` domain — the `PensionContract` lifecycle machine,
 * the append-only `Contribution` credit and the statutory `StateContributionReturn` obligation —
 * so the harness's binding to the service is proven by an actual assertion, not merely by
 * compiling.
 *
 * The scenario draws its exit path uniformly per step, so each test steps the world until the
 * shape it needs exists (bounded), instead of pinning a seed to a specific draw order.
 */
class PensionLifecycleScenarioTest {

    private fun newWorld(faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed = 42L, faultProfile), SimulationConfig())

    /** Step until the last contract matches [predicate], bounded so a regression fails instead of looping. */
    private fun stepUntilContract(world: World, predicate: (PensionContract) -> Boolean): PensionContract {
        repeat(60) {
            PensionLifecycleScenario.step(world)
            world.pensionContracts.lastOrNull()?.let { if (predicate(it)) return it }
        }
        throw AssertionError("no contract matching the predicate within 60 steps")
    }

    @Test
    fun `an exit path concludes the contract and settles its statutory return obligation`() {
        val world = newWorld()
        val contract = stepUntilContract(world) {
            it.status == ContractStatus.PAID_OUT || it.status == ContractStatus.TRANSFERRED_OUT
        }

        assertThat(contract.status.terminal).isTrue()
        // The exit contract was credited at least once; every one of its contributions carries
        // its id (previous steps' contracts have their own).
        val contributions = world.pensionContributions.filter { it.contractId == contract.id }
        assertThat(contributions).isNotEmpty()
        assertThat(contributions).allMatch { it.contractId == contract.id }
        // The exit received state money: exactly one return obligation, driven to its terminal
        // SETTLED inside the same step.
        val obligation = world.pensionReturns.single { it.contractId == contract.id }
        assertThat(obligation.status).isEqualTo(ReturnStatus.SETTLED)
        assertThat(obligation.cause).isEqualTo(ReturnCause.CONTRACT_TERMINATED)
        assertThat(obligation.dueBy).isEqualTo(
            CzStateContributionCalendar.terminationReturnDue(obligation.discoveredOn),
        )

        assertThat(MoneyPathInvariants.pensionContractCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.pensionReturnTimeliness.check(world)).isNull()
    }

    @Test
    fun `a write fault at activation closes the contract withdrawn, crediting nothing`() {
        // 100% write-failure rate forces the closed-withdrawn branch every time.
        val world = newWorld(FaultProfile(writeFailureRate = 1.0))
        PensionLifecycleScenario.step(world)

        val contract = world.pensionContracts.single()
        assertThat(contract.status).isEqualTo(ContractStatus.CLOSED)
        assertThat(contract.startDate).isNull()
        assertThat(world.pensionContributions).isEmpty()
        assertThat(world.pensionReturns).isEmpty()
        assertThat(MoneyPathInvariants.pensionContractCompleteness.check(world)).isNull()
    }

    @Test
    fun `a post-commit conflict after termination reopens the contract on the real compensation edge`() {
        // 100% conflict rate forces the reversed-payout compensation branch on every exit path:
        // no contract may conclude, every one stays ACTIVE through the REAL
        // reopenAfterReversedPayout edge.
        val world = newWorld(FaultProfile(lockConflictRate = 1.0))
        repeat(12) { PensionLifecycleScenario.step(world) }

        assertThat(world.pensionContracts).isNotEmpty()
        assertThat(world.pensionContracts).allMatch { it.status == ContractStatus.ACTIVE }
        assertThat(MoneyPathInvariants.pensionContractCompleteness.check(world)).isNull()
    }

    @Test
    fun `the real contract machine refuses unlisted transitions and the calendar pins its dates`() {
        // The scenario drives the REAL PensionContract lifecycle; if a regression added an
        // unlisted edge (or the scenario silently bypassed the machine), this assertion against
        // the REAL transition table is what catches the drift.
        val now = Instant.now()
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val contract = PensionContract.draft(
            participantPartyId = UUID.randomUUID(),
            productLine = ProductLine.DPS,
            jurisdiction = "CZ",
            packVersion = 1,
            providerEntityId = UUID.randomUUID(),
            providerType = ProviderType.PENSION_COMPANY,
            participantBirthDate = today.minusYears(30),
            schedule = ContributionSchedule(
                amount = BigDecimal("1000.00"),
                currency = "CZK",
                frequency = ContributionFrequency.MONTHLY,
            ),
            initialStrategy = "STRAT-A",
            beneficiaries = emptyList(),
            today = today,
            now = now,
            idempotencyKey = "test-${UUID.randomUUID()}",
        )

        // DRAFT -> ACTIVE directly is not a listed edge; only PENDING_ACTIVATION -> ACTIVE is.
        assertThatThrownBy { contract.activate(today, now) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(ContractStatus.DRAFT.canMoveTo(ContractStatus.PENDING_ACTIVATION)).isTrue()
        assertThat(ContractStatus.DRAFT.canMoveTo(ContractStatus.ACTIVE)).isFalse()

        // Terminal states have no outgoing edge.
        val paidOut = contract.submit(now).activate(today, now).requestTermination(now).markPaidOut(now)
        assertThat(paidOut.status.terminal).isTrue()
        assertThatThrownBy { paidOut.suspendContributions(now) }
            .isInstanceOf(IllegalStateException::class.java)

        // The statutory calendar, pinned: a termination return is due by the end of the month
        // six months after the exit (ZDPS §18(3), R2), and an unsettled obligation past that
        // deadline reads as overdue.
        val terminatedOn = LocalDate.of(2026, 3, 15)
        assertThat(CzStateContributionCalendar.terminationReturnDue(terminatedOn))
            .isEqualTo(LocalDate.of(2026, 9, 30))
        val obligation = StateContributionReturn(
            id = UUID.randomUUID(),
            contractId = contract.id,
            claimId = null,
            cause = ReturnCause.CONTRACT_TERMINATED,
            amount = BigDecimal("200.00"),
            currency = "CZK",
            discoveredOn = terminatedOn,
            dueBy = CzStateContributionCalendar.terminationReturnDue(terminatedOn),
            sourceKey = "test",
            status = ReturnStatus.DUE,
            createdAt = now,
            updatedAt = now,
        )
        assertThat(obligation.overdue(LocalDate.of(2026, 9, 30))).isFalse()
        assertThat(obligation.overdue(LocalDate.of(2026, 10, 1))).isTrue()
    }
}
