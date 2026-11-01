// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.pension.domain.contribution.Contribution
import com.openbank.pension.domain.contribution.ContributionChannel
import com.openbank.pension.domain.contribution.ContributionSource
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.domain.statecontribution.CzStateContributionCalendar
import com.openbank.pension.domain.statecontribution.ReturnCause
import com.openbank.pension.domain.statecontribution.ReturnStatus
import com.openbank.pension.domain.statecontribution.StateContributionReturn
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Issue #14366 (Test Intelligence simulation gap, money path): one step of the participant-side
 * pension path, modelled directly against the **real `pension-service` domain** — the
 * `PensionContract` lifecycle (every edge listed in `ContractStatus`, refused by the aggregate
 * itself), the append-only `Contribution` credit (idempotent by paymentId, bounded by the real
 * `MoneyBounds`), and the statutory `StateContributionReturn` obligation whose deadlines come
 * from the pure `CzStateContributionCalendar` (Act 427/2011 Coll.) — not a re-model of any of
 * them, the same way [DomesticClearingScenario] builds on the real `DomesticPayment` aggregate
 * (ADR-0100). The domain packages are framework-free (ADR-0002).
 *
 * A step mirrors the production ladder: open a contract (DRAFT -> PENDING_ACTIVATION -> ACTIVE on
 * the real machine), credit one or more contributions, and either keep it open (SUSPEND/RESUME,
 * or a payout whose premium came back — the TERMINATING -> ACTIVE compensation edge) or run the
 * exit to a terminal status (PAID_OUT / TRANSFERRED_OUT). An injected write fault at activation
 * means the contract never funded: it is CLOSED withdrawn (legal from DRAFT/PENDING_ACTIVATION,
 * ADR-0334 S2). Every exit that received state money registers a REAL return obligation with its
 * statutory `dueBy` and drives it DUE -> REPORTED -> CONFIRMED -> SETTLED inside the same step —
 * a return left open past its deadline is a missed statutory obligation, which
 * `MoneyPathInvariants.pensionReturnTimeliness` fails.
 */
object PensionLifecycleScenario {

    private const val MIN_CONTRIBUTION_MINOR = 500L // §14(1): below 500 CZK earns no state incentive
    private const val MAX_CONTRIBUTION_MINOR = 50_000L
    private const val STATE_INCENTIVE_MINOR = 200L // the monthly state incentive this scenario received
    private const val MAX_CREDITS_PER_STEP = 3
    private const val PARTICIPANT_AGE_YEARS = 30L
    private const val STRATEGY_CODE_SPACE = 100
    private const val JURISDICTION_CZ = "CZ"
    private const val PACK_VERSION = 1

    private val PATHS = Path.entries

    fun step(world: World) {
        val now = world.context.clock.instant()
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val path = PATHS[world.context.random.nextInt(PATHS.size)]

        // 1-2. Open the contract on the REAL lifecycle machine and activate. An injected write
        //    fault means the contract never funded: close it withdrawn (a legal edge from
        //    DRAFT/PENDING_ACTIVATION), credit nothing.
        val contract = activate(world, now, today) ?: return

        // 3. Credit one or more REAL contributions (append-only, idempotent by paymentId).
        repeat(creditCount(world)) {
            creditContribution(world, contract, now, today)
        }

        // 4. The path: keep the contract open, or run the exit to a terminal status.
        when (path) {
            Path.SUSPEND_RESUME -> {
                val resumed = contract.suspendContributions(now).resumeContributions(now)
                world.pensionContracts[world.pensionContracts.lastIndex] = resumed
                world.audit.append("pension ${resumed.id} suspended and resumed")
            }
            Path.PAYOUT -> exit(world, contract, now, today, paidOut = true)
            Path.TRANSFER_OUT -> exit(world, contract, now, today, paidOut = false)
        }
    }

    private fun activate(world: World, now: Instant, today: LocalDate): PensionContract? {
        val contract = openContract(world, now, today)
        val activated = try {
            if (world.context.faults.shouldFailWrite()) {
                throw SimulatedWriteFailure("pension activation failed for ${contract.id}")
            }
            contract.submit(now).activate(today, now)
        } catch (e: SimulatedWriteFailure) {
            val closed = contract.close(now)
            world.pensionContracts.add(closed)
            world.audit.append("pension ${closed.id} closed: ${e.message}")
            return null
        }
        world.pensionContracts.add(activated)
        world.audit.append("pension ${activated.id} activated")
        return activated
    }

    private fun creditCount(world: World): Int = 1 + world.context.random.nextInt(MAX_CREDITS_PER_STEP)

    private fun creditContribution(world: World, contract: PensionContract, now: Instant, today: LocalDate) {
        val contribution = Contribution(
            id = world.context.random.nextUuid(),
            contractId = contract.id,
            paymentId = "pay-${world.context.random.nextUuid()}",
            source = ContributionSource.PARTICIPANT,
            channel = ContributionChannel.STANDING_ORDER,
            amount = minorAmount(world.context.random.nextLong(MIN_CONTRIBUTION_MINOR, MAX_CONTRIBUTION_MINOR)),
            currency = world.currency,
            valueDate = today,
            receivedAt = now,
        )
        world.pensionContributions.add(contribution)
        world.audit.append("pension ${contract.id} credited: ${contribution.amount}")
    }

    private fun exit(world: World, contract: PensionContract, now: Instant, today: LocalDate, paidOut: Boolean) {
        var terminating = contract.requestTermination(now)
        // A post-commit conflict after termination means the payout reversed and the premium
        // came back: the REAL compensation edge (TERMINATING -> ACTIVE, #12383).
        if (world.context.faults.shouldConflict()) {
            val reopened = terminating.reopenAfterReversedPayout(now)
            world.pensionContracts[world.pensionContracts.lastIndex] = reopened
            world.audit.append("pension ${reopened.id} reopened: payout reversed")
            return
        }
        terminating = if (paidOut) terminating.markPaidOut(now) else terminating.markTransferredOut(now)
        world.pensionContracts[world.pensionContracts.lastIndex] = terminating
        world.audit.append("pension ${terminating.id} -> ${terminating.status}")

        // The exit received state money: a REAL return obligation with its statutory deadline,
        // driven to SETTLED inside the same step.
        settleStateReturn(world, terminating, now, today)
    }

    private fun openContract(world: World, now: Instant, today: LocalDate): PensionContract {
        val random = world.context.random
        val amount = minorAmount(random.nextLong(MIN_CONTRIBUTION_MINOR, MAX_CONTRIBUTION_MINOR))
        return PensionContract.draft(
            participantPartyId = world.customerAccounts[random.nextInt(world.customerAccounts.size)],
            productLine = ProductLine.DPS,
            jurisdiction = JURISDICTION_CZ,
            packVersion = PACK_VERSION,
            providerEntityId = random.nextUuid(),
            providerType = ProviderType.entries[random.nextInt(ProviderType.entries.size)],
            participantBirthDate = today.minusYears(PARTICIPANT_AGE_YEARS),
            schedule = ContributionSchedule(
                amount = amount,
                currency = world.currency,
                frequency = ContributionFrequency.MONTHLY,
            ),
            initialStrategy = "STRAT-${random.nextInt(STRATEGY_CODE_SPACE)}",
            beneficiaries = emptyList(),
            today = today,
            now = now,
            idempotencyKey = "pension-${random.nextUuid()}",
        )
    }

    private fun settleStateReturn(world: World, contract: PensionContract, now: Instant, today: LocalDate) {
        val random = world.context.random
        val obligation = StateContributionReturn(
            id = random.nextUuid(),
            contractId = contract.id,
            claimId = null,
            cause = ReturnCause.CONTRACT_TERMINATED,
            amount = minorAmount(STATE_INCENTIVE_MINOR),
            currency = world.currency,
            discoveredOn = today,
            // The statutory deadline is computed by the REAL calendar (ZDPS §18(3), R2).
            dueBy = CzStateContributionCalendar.terminationReturnDue(today),
            sourceKey = "exit-${contract.id}",
            status = ReturnStatus.DUE,
            createdAt = now,
            updatedAt = now,
        )
        world.pensionReturns.add(obligation)
        val reportId = random.nextUuid()
        val settled = obligation.report(reportId, now).confirm(now).settle(now)
        world.pensionReturns[world.pensionReturns.lastIndex] = settled
        world.audit.append("pension ${contract.id} state return settled: ${settled.amount}")
    }

    /** Whole-currency amounts: minor units in, a 2-dp [BigDecimal] out (the bounds require ≤ 2dp). */
    private fun minorAmount(minorUnits: Long): BigDecimal = BigDecimal(minorUnits).movePointLeft(2)

    /** Which exit the participant takes in this step. */
    private enum class Path { SUSPEND_RESUME, PAYOUT, TRANSFER_OUT }
}
