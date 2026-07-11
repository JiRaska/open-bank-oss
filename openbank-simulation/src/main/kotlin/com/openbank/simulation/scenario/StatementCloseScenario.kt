// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.model.StatementCloseKey
import com.openbank.simulation.runner.World
import com.openbank.statement.domain.model.PeriodCloseStatus
import com.openbank.statement.domain.model.StatementPeriod
import com.openbank.statement.domain.reconcile.ReconciliationPolicy
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Issue #667 (E2E money-path): one step of the statement period-close path — modelled directly
 * against the **real `openbank-statement-service` domain** (`StatementPeriod`, and above all the
 * fail-closed `ReconciliationPolicy` a period-close must pass, ADR-0035 §E / ADR-0078), mirroring
 * [PaymentScenario]/[FeeBillingScenario]/[InterestAccrualScenario]'s "build on the real system"
 * convention (ADR-0100).
 *
 * `StatementService.mintPeriod`'s use case itself is Mutiny/CDI-bound and cannot run inside the
 * pure-JVM harness (same reasoning documented on [InterestAccrualScenario]), so this scenario
 * replicates its two-input shape — an opening balance carried from the prior close (or the
 * account's seeded opening balance, if never closed) and the period's net movement (the ledger's
 * cumulative net delta *since* the prior close) — and hands both, plus the account's current
 * booked balance as the independently-reported closing figure, to the REAL `ReconciliationPolicy`.
 *
 * A seeded fraction of attempts inject a **phantom haléř** into the computed net movement —
 * standing in for a real defect class (a booked-entries read-port that drops or duplicates an
 * entry) — so the scenario exercises BOTH outcomes every run: `Reconciled` (a `StatementPeriod` is
 * minted, the running close state advances) and `Mismatch` (nothing is persisted, the running
 * state is untouched, exactly ADR-0035 §E's "no partial, self-inconsistent legal document is ever
 * produced"). `MoneyPathInvariants.statementCloseIntegrity` asserts a period was persisted
 * precisely when reconciliation succeeded — never on a Mismatch, never skipped on a Reconciled.
 */
object StatementCloseScenario {

    private val ZONE = ZoneId.of("Europe/Prague")

    /** Seeded fraction of close attempts whose computed net movement is deliberately corrupted. */
    private const val MISMATCH_INJECTION_RATE = 0.15
    private val PHANTOM_HALER: BigDecimal = BigDecimal("0.01")

    fun step(world: World) {
        val random = world.context.random
        val accountId = random.pick(world.customerAccounts)
        val key = AccountCurrency(accountId, world.currency)
        val attemptId = "sim-close-${world.context.seed}-${random.nextLong()}"

        val opening = world.statementCloses.openingBalanceOf(key, world.openingBookedOf(key))
        val netAtLastClose = world.statementCloses.netAtLastCloseOf(key)
        val cumulativeNet = world.ledger.netDeltas()[key] ?: BigDecimal.ZERO
        var netMovement = cumulativeNet - netAtLastClose

        // Seeded fault: a booked-entries read-port gap, distinct from the shared FaultInjector's
        // network-level faults (this is a data-completeness defect, not a write/lock failure).
        if (random.chance(MISMATCH_INJECTION_RATE)) {
            netMovement += PHANTOM_HALER
        }

        val reportedClosing = world.balances.get(key).bookedAmount
        val decision = ReconciliationPolicy.reconcile(opening, netMovement, reportedClosing)
        val closeKey = StatementCloseKey(accountId, key.currency, attemptId)

        when (decision) {
            is ReconciliationPolicy.Result.Reconciled -> {
                world.statementCloses.recordDecision(closeKey, wasReconciled = true)
                val period = mintPeriod(world, key, opening, decision.closingBalance)
                world.statementCloses.recordPersisted(closeKey, wasPersisted = true)
                world.statementCloses.advance(key, decision.closingBalance, cumulativeNet)
                world.audit.append("statement period ${period.id} closed: seq=${period.legalSequenceNumber}")
            }
            is ReconciliationPolicy.Result.Mismatch -> {
                world.statementCloses.recordDecision(closeKey, wasReconciled = false)
                world.statementCloses.recordPersisted(closeKey, wasPersisted = false)
                world.audit.append(
                    "statement close REFUSED for $key: computed=${decision.computed} " +
                        "reported=${decision.reported} delta=${decision.delta}",
                )
            }
        }
    }

    /** Build the real `StatementPeriod` the reconciled close would persist — for realism/audit only. */
    private fun mintPeriod(
        world: World,
        key: AccountCurrency,
        opening: BigDecimal,
        closing: BigDecimal,
    ): StatementPeriod {
        val random = world.context.random
        val today = LocalDate.ofInstant(world.context.clock.instant(), ZONE)
        val seq = world.statementCloses.nextSequenceOf(key)
        return StatementPeriod(
            id = random.nextUuid(),
            accountId = key.accountId,
            pocketCurrency = key.currency,
            periodFrom = today,
            periodTo = today,
            legalSequenceNumber = seq,
            electronicSequenceNumber = seq,
            openingBalance = opening,
            closingBalance = closing,
            entryCount = 0,
            closedAt = world.context.clock.instant(),
            status = PeriodCloseStatus.CLOSED,
        )
    }
}
