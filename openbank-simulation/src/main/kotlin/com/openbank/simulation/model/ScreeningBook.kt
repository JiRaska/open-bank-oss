// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import com.openbank.fx.domain.screening.ScreeningDecision
import java.util.UUID

/**
 * Simulated FX screening state (ADR-0032 §B, issue #667 shape): every conversion records two
 * independent facts — the verdict the REAL
 * [com.openbank.fx.domain.screening.ScreeningPolicy] rendered over its screened names, and what
 * the payment path actually did with the conversion — so
 * `MoneyPathInvariants.screeningDispositionIntegrity` can assert they never disagree:
 *
 *  - a **BLOCK** decision must refuse the conversion and must never execute it;
 *  - a **REVIEW** decision must be resolved exactly once — as a false positive (released and
 *    then executed) or as a confirmed hit (escalated, refused) — never silently dropped, never
 *    double-resolved;
 *  - a **CLEAR** decision must execute and must never be refused.
 *
 * This is the sanctions-screening equivalent of [StatementCloseBook]'s both-sides-recorded
 * cross-check: the decision and the disposition are recorded independently so the invariant
 * checks a real cross-side reconciliation rather than trusting one flag. An alert that vanished
 * without a resolution, or a conversion that executed under a BLOCK, is exactly the class of
 * defect an audit of a sanctions path is written to find.
 */
class ScreeningBook {
    private val records = mutableMapOf<UUID, ScreeningRecord>()

    fun recordDecision(conversionId: UUID, decision: ScreeningDecision) {
        records[conversionId] = ScreeningRecord(decision = decision)
    }

    fun markExecuted(conversionId: UUID) {
        records.getValue(conversionId).executed = true
    }

    fun markRefused(conversionId: UUID) {
        records.getValue(conversionId).refused = true
    }

    fun openReview(conversionId: UUID) {
        val r = records.getValue(conversionId)
        r.reviewOpen = true
        r.reviewWasOpened = true
    }

    /** A REVIEW alert's single resolution; [escalated] means the human confirmed the hit. */
    fun resolveReview(conversionId: UUID, escalated: Boolean) {
        val r = records.getValue(conversionId)
        r.reviewOpen = false
        r.reviewResolved = true
        r.reviewEscalatedToBlock = escalated
        r.resolutionCount += 1
    }

    fun record(conversionId: UUID): ScreeningRecord? = records[conversionId]

    /** Every conversion seen — the invariant checks the full set, not just one side. */
    fun conversions(): Set<UUID> = records.keys
}

class ScreeningRecord(
    val decision: ScreeningDecision,
    var executed: Boolean = false,
    var refused: Boolean = false,
    var reviewOpen: Boolean = false,
    var reviewResolved: Boolean = false,
    var reviewEscalatedToBlock: Boolean = false,
    var resolutionCount: Int = 0,
) {
    var reviewWasOpened: Boolean = false
}
