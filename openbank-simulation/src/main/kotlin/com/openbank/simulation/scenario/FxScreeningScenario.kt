// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.fx.domain.screening.ScreeningDecision
import com.openbank.fx.domain.screening.ScreeningMatchStatus
import com.openbank.fx.domain.screening.ScreeningPolicy
import com.openbank.fx.domain.screening.ScreeningResult
import com.openbank.fx.domain.screening.ScreeningRole
import com.openbank.simulation.runner.World

/**
 * Issue #667 shape (ADR-0100 "build on the real system"): one step of the FX conversion
 * sanctions-screening path — modelled directly against the REAL
 * [com.openbank.fx.domain.screening.ScreeningPolicy] (ADR-0032 §B), the pure, framework-free
 * verdict function over a conversion's screened names. The screening HTTP client and the OPA
 * wiring are ports and cannot run inside the pure-JVM harness (same reasoning documented on
 * [StatementCloseScenario]), so this scenario generates the screened-name results the real
 * sanctions adapter would return and hands them to the REAL policy.
 *
 * Every step screens one customer conversion's debtor and creditor names. A seeded fraction of
 * names carry a POTENTIAL_HIT at scores straddling [ScreeningPolicy.POTENTIAL_HIT_BLOCK_THRESHOLD]
 * (0.85) — so the policy's REVIEW-vs-BLOCK boundary is exercised on BOTH sides every run — and a
 * rarer seeded HIT / ESCALATED stands in for a true list match. The disposition mirrors the
 * production rule: CLEAR executes, BLOCK refuses, REVIEW is held and then resolved exactly once
 * — mostly a false positive (released, then executed), sometimes a confirmed hit (escalated,
 * refused). `MoneyPathInvariants.screeningDispositionIntegrity` cross-checks the decision
 * against the disposition independently recorded in [com.openbank.simulation.model.ScreeningBook].
 */
object FxScreeningScenario {

    /** Preset scores on BOTH sides of the 0.85 block threshold, plus one just under review. */
    private val POTENTIAL_HIT_SCORES = listOf(
        SCORE_REVIEW_LOW,
        SCORE_REVIEW_MID,
        SCORE_THRESHOLD_MINUS,
        SCORE_BLOCK_LOW,
        SCORE_BLOCK_HIGH,
    )

    private const val SCORE_REVIEW_LOW = 0.62
    private const val SCORE_REVIEW_MID = 0.78
    private const val SCORE_THRESHOLD_MINUS = 0.84
    private const val SCORE_BLOCK_LOW = 0.91
    private const val SCORE_BLOCK_HIGH = 0.97

    /** Fair coin for tie-breaking a name's score / status flavour. */
    private const val COIN_FLIP = 0.5

    /** Per-name probability of carrying a seeded POTENTIAL_HIT (a false-positive candidate). */
    private const val POTENTIAL_HIT_RATE = 0.10

    /** Per-name probability of a true list match (always blocks, whatever the score). */
    private const val TRUE_HIT_RATE = 0.01

    /** Per-name probability of an escalated prior alert (also always blocks). */
    private const val ESCALATED_RATE = 0.005

    /** Of the REVIEW alerts a human looks at, the fraction that turns out to be a real hit. */
    private const val REVIEW_CONFIRMED_HIT_RATE = 0.2

    private val NAMES = listOf(
        "Alois Novak",
        "Blanka Svobodova",
        "Cyril Dvorak",
        "Dana Cerna",
        "Emil Prochazka",
        "Frantiska Urbanova",
        "Gustav Kral",
        "Hana Blazkova",
    )

    fun step(world: World) {
        val random = world.context.random
        val conversionId = random.nextUuid()

        val results = ScreeningRole.entries.flatMap { role ->
            val name = random.pick(NAMES)
            listOf(
                ScreeningResult(
                    subject = name,
                    role = role,
                    status = seededStatus(random),
                    score = when {
                        random.chance(COIN_FLIP) -> random.pick(POTENTIAL_HIT_SCORES)
                        else -> 0.0
                    },
                    matchedEntity = null,
                ),
            )
        }

        val decision = ScreeningPolicy.decide(results)
        world.screening.recordDecision(conversionId, decision)
        when (decision) {
            ScreeningDecision.CLEAR -> world.screening.markExecuted(conversionId)
            ScreeningDecision.BLOCK -> world.screening.markRefused(conversionId)
            ScreeningDecision.REVIEW -> {
                world.screening.openReview(conversionId)
                // The human review resolves the alert exactly once: false positive (released and
                // the conversion then executes) or confirmed hit (escalated and refused).
                if (random.chance(REVIEW_CONFIRMED_HIT_RATE)) {
                    world.screening.resolveReview(conversionId, escalated = true)
                    world.screening.markRefused(conversionId)
                } else {
                    world.screening.resolveReview(conversionId, escalated = false)
                    world.screening.markExecuted(conversionId)
                }
            }
        }
    }

    private fun seededStatus(random: com.openbank.simulation.engine.SimulationRandom): ScreeningMatchStatus = when {
        random.chance(TRUE_HIT_RATE) -> ScreeningMatchStatus.HIT
        random.chance(ESCALATED_RATE) -> ScreeningMatchStatus.ESCALATED
        random.chance(POTENTIAL_HIT_RATE) -> ScreeningMatchStatus.POTENTIAL_HIT
        random.chance(COIN_FLIP) -> ScreeningMatchStatus.CLEAR
        else -> ScreeningMatchStatus.WHITELISTED
    }
}
