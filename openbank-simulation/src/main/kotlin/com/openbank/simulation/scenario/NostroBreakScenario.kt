// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.simulation.runner.World
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.BreakAlertPolicy
import com.openbank.treasury.domain.model.BreakSide
import com.openbank.treasury.domain.model.NostroBreak
import com.openbank.treasury.domain.model.Side
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Test Intelligence simulation gap: one step of the treasury nostro-reconciliation desk, modelled
 * directly against the **real `treasury-service` domain** — the `NostroBreak` aggregate (ADR-0315
 * D7: its two-sided identity rules, its aging over the pure `BusinessDays` calendar, its alert
 * policy) and its four-eyes manual resolution (propose by one person, approve by a DIFFERENT
 * person, reject with a reason kept in history) — not a re-model of any of them (ADR-0100). The
 * domain package is framework-free (ADR-0002).
 *
 * A step ages the virtual clock a few business days, occasionally opens a new break (statement
 * side or ledger side — each with exactly the identity fields its side requires), runs one
 * resolution flow to a settled conclusion — a proposal is approved or rejected inside the SAME
 * step, so no break ever carries a proposal awaiting a second person overnight; an injected write
 * fault defers the whole flow, not half of it — occasionally matches an open break (a later
 * reconciliation no longer observes it), and runs the alert sweep: every open break the REAL
 * `BreakAlertPolicy` reads as aged must carry its `alertedAt`, because an aged break nobody was
 * told about is money leaking quietly.
 */
object NostroBreakScenario {

    private const val MAX_BREAK_AMOUNT_MINOR = 5_000_000L // 50 000.00
    private const val ALERT_AGE_DAYS = 3
    private const val MAX_STEP_DAYS = 3
    private const val PROPOSER = "treasury-analyst"
    private const val APPROVER = "treasury-senior"
    private const val RESOLUTION_REASON = "Correspondent confirmed duplicate booking; closing."
    private const val REJECTION_REASON = "Evidence incomplete; investigate the ledger side."
    private const val FAIR_CHANCE = 0.5

    /** Aged ≥ 3 business days with any amount alerts — the early-alert-never-missed side of the policy. */
    private val ALERT_POLICY = BreakAlertPolicy(ageDays = ALERT_AGE_DAYS, minAmount = BigDecimal.ZERO)

    fun step(world: World) {
        val random = world.context.random
        world.context.clock.advance(Duration.ofDays(1 + random.nextInt(MAX_STEP_DAYS).toLong()))
        val now = world.context.clock.instant()
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()

        if (random.chance(FAIR_CHANCE)) openBreak(world, today)
        resolveOne(world, today, now)
        if (random.chance(FAIR_CHANCE)) matchOne(world, today)
        // The per-step sweep covers this step's own clock advance; the runner calls
        // [sweepAlerts] once more after every scenario has stepped, so breaks aged by a LATER
        // scenario's clock advance are still alerted before the invariant sweep.
        sweepAlerts(world, today, now)
    }

    /**
     * The production alert sweep as a standalone job (the runner's pre-invariant pass): an open
     * break the REAL policy reads as aged carries its alertedAt — an aged break nobody was told
     * about is money leaking quietly.
     */
    fun sweepAlerts(world: World) {
        val now = world.context.clock.instant()
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        sweepAlerts(world, today, now)
    }

    private fun openBreak(world: World, today: LocalDate) {
        val random = world.context.random
        val statementSide = random.chance(FAIR_CHANCE)
        val breakId = random.nextUuid()
        val statementUuid = random.nextUuid()
        val amount = minorAmount(random.nextLong(1, MAX_BREAK_AMOUNT_MINOR))
        // ourSide is stated from OUR side for both kinds: a correspondent CRDT is our DEBIT.
        val ourSide = if (random.chance(FAIR_CHANCE)) Side.DEBIT else Side.CREDIT
        val reference = if (statementSide) NostroBreak.statementKey(statementUuid, 1) else null
        val ledgerLineId = if (statementSide) null else random.nextUuid()
        val breakRow = NostroBreak(
            id = breakId,
            breakKey = reference ?: NostroBreak.ledgerKey(checkNotNull(ledgerLineId)),
            iban = "CZ6508000000192000145399",
            glCode = "NOSTRO-CZK",
            currency = "CZK",
            side = if (statementSide) BreakSide.STATEMENT else BreakSide.LEDGER,
            ourSide = ourSide,
            amount = amount,
            bookingDate = today,
            reference = reference,
            statementUuid = statementUuid,
            statementSequence = if (statementSide) 1 else null,
            ledgerLineId = ledgerLineId,
            firstSeenOn = today,
            alertedAt = null,
        )
        world.nostroBreaks.add(breakRow)
        world.audit.append("nostro break $breakId opened (${breakRow.side}, $amount CZK, our ${breakRow.ourSide})")
    }

    /**
     * One resolution flow, concluded inside the step: a proposal is approved by a DIFFERENT person
     * (the REAL `approveResolution` four-eyes rule), or rejected with its reason kept. A write
     * fault defers the entire flow — a proposal left half-written awaiting a second person
     * overnight is exactly the stall the hygiene invariant fails.
     */
    private fun resolveOne(world: World, today: LocalDate, now: Instant) {
        val random = world.context.random
        val candidates = world.nostroBreaks.filter { it.open && !it.resolutionProposed }
        if (candidates.isEmpty() || !random.chance(FAIR_CHANCE)) return
        val breakRow = candidates[random.nextInt(candidates.size)]
        if (world.context.faults.shouldFailWrite()) {
            world.audit.append("nostro break ${breakRow.id} resolution deferred by write fault")
            return
        }
        val index = world.nostroBreaks.indexOf(breakRow)
        val proposer = Actor(PROPOSER, ActorType.HUMAN)
        val approver = Actor(APPROVER, ActorType.HUMAN)
        val proposed = world.nostroBreaks[index].proposeResolution(RESOLUTION_REASON, proposer, now)
        world.nostroBreaks[index] = if (random.chance(FAIR_CHANCE)) {
            val approved = proposed.approveResolution(approver, today, checkNotNull(proposed.proposedAt))
            world.audit.append("nostro break ${breakRow.id} manually resolved (four eyes)")
            approved
        } else {
            val rejected = proposed.rejectResolution(REJECTION_REASON, approver)
            world.audit.append("nostro break ${breakRow.id} proposal rejected, still open")
            rejected
        }
    }

    /** A later reconciliation no longer observes one open break: the REAL `matched` resolution. */
    private fun matchOne(world: World, today: LocalDate) {
        val random = world.context.random
        val candidates = world.nostroBreaks.filter { it.open }
        if (candidates.isEmpty()) return
        val breakRow = candidates[random.nextInt(candidates.size)]
        val index = world.nostroBreaks.indexOf(breakRow)
        world.nostroBreaks[index] = breakRow.matched(today)
        world.audit.append("nostro break ${breakRow.id} matched by a later reconciliation")
    }

    private fun sweepAlerts(world: World, today: LocalDate, now: Instant) {
        world.nostroBreaks.replaceAll { breakRow ->
            if (breakRow.open && breakRow.alertedAt == null && ALERT_POLICY.isAged(breakRow, today)) {
                world.audit.append(
                    "nostro break ${breakRow.id} alerted at ${breakRow.ageBusinessDays(today)} business days",
                )
                breakRow.copy(alertedAt = now)
            } else {
                breakRow
            }
        }
    }

    /** Whole-currency amounts: minor units in, a 2-dp [BigDecimal] out. */
    private fun minorAmount(minorUnits: Long): BigDecimal = BigDecimal(minorUnits).movePointLeft(2)
}
