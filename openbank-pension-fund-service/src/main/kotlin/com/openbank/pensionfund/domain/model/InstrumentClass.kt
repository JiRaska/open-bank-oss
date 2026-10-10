// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.domain.model

import java.time.Instant
import java.util.UUID

/**
 * The asset category of a fund position, after the ECB pension-fund statistics instrument
 * breakdown (Reg. (EU) 2018/231, ECB/2018/2: deposits F.2, debt securities F.3, loans F.4, equity
 * F.51, investment-fund shares F.52, financial derivatives F.71) — what ČNB's PEF returns are
 * built on (#12425).
 *
 * [UNCLASSIFIED] is the explicit state of a position recorded before the attribute existed (or
 * submitted without one): it is UNKNOWN, never "other". A figure that depends on the class — the
 * loans of PEF 13-04 — is refused while any closing position is still unclassified, because an
 * unclassified position may be a loan.
 */
enum class InstrumentClass {
    DEPOSIT,
    DEBT_SECURITY,
    LOAN,
    EQUITY,
    FUND_SHARE,
    DERIVATIVE,
    OTHER,
    UNCLASSIFIED,
}

/** A classification an operator may propose; unknown is a recorded state, never a correction target. */
enum class ClassificationTarget {
    DEPOSIT,
    DEBT_SECURITY,
    LOAN,
    EQUITY,
    FUND_SHARE,
    DERIVATIVE,
    OTHER,
    ;

    fun instrumentClass(): InstrumentClass = InstrumentClass.valueOf(name)
}

enum class ClassificationCorrectionStatus { PROPOSED, APPROVED, REJECTED }

/**
 * An operator's correction of one recorded position's [InstrumentClass], four-eyes.
 *
 * The position row itself is never rewritten — it is what the NAV was struck on. The latest
 * APPROVED correction is the position's effective class, so the history of every
 * reclassification stays readable and a report's fingerprint changes when one lands.
 */
data class PositionClassificationCorrection(
    val id: UUID,
    val positionId: UUID,
    val navId: UUID,
    val fromClass: InstrumentClass,
    val toClass: InstrumentClass,
    val reason: String,
    val proposedBy: String,
    val proposedAt: Instant,
    val status: ClassificationCorrectionStatus = ClassificationCorrectionStatus.PROPOSED,
    val decidedBy: String? = null,
    val decidedAt: Instant? = null,
) {
    init {
        require(toClass != InstrumentClass.UNCLASSIFIED) { "a correction must name a class, not UNCLASSIFIED" }
        require(toClass != fromClass) { "the position is already $fromClass" }
        require(reason.isNotBlank()) { "reason must not be blank" }
    }

    fun approve(approver: String, now: Instant): PositionClassificationCorrection {
        check(status == ClassificationCorrectionStatus.PROPOSED) { "correction $id is $status" }
        if (approver == proposedBy) {
            throw FourEyesViolationException("the proposer of correction $id cannot approve it")
        }
        return copy(status = ClassificationCorrectionStatus.APPROVED, decidedBy = approver, decidedAt = now)
    }

    fun reject(approver: String, now: Instant): PositionClassificationCorrection {
        check(status == ClassificationCorrectionStatus.PROPOSED) { "correction $id is $status" }
        if (approver == proposedBy) {
            throw FourEyesViolationException("the proposer of correction $id cannot reject it")
        }
        return copy(status = ClassificationCorrectionStatus.REJECTED, decidedBy = approver, decidedAt = now)
    }
}

/** The class each position carries now: its recorded class, overridden by its latest approved correction. */
fun effectiveClasses(
    positions: List<NavPosition>,
    corrections: List<PositionClassificationCorrection>,
): List<NavPosition> {
    val latest = corrections
        .filter { it.status == ClassificationCorrectionStatus.APPROVED }
        .groupBy { it.positionId }
        .mapValues { (_, list) -> list.maxBy { checkNotNull(it.decidedAt) }.toClass }
    return positions.map { p -> latest[p.id]?.let { p.copy(instrumentClass = it) } ?: p }
}
