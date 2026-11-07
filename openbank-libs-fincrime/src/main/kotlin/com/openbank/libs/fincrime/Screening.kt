// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime

/** Which side of the payment a screened name belongs to. */
enum class ScreeningRole { DEBTOR, CREDITOR }

/**
 * Status of one name screened against the sanctions lists — sanctions-service's published check
 * status (ADR-0353 D4). sanctions-service owns its meaning; the rails read it off the wire.
 */
enum class ScreeningMatchStatus {
    CLEAR,
    POTENTIAL_HIT,
    HIT,
    WHITELISTED,
    ESCALATED,
    ;

    companion object {
        /**
         * Total, fail-closed parse of a wire value: an absent or unrecognised status is [ESCALATED]
         * (which [ScreeningPolicy] blocks), never [CLEAR] (ADR-0032, ADR-0353 D5).
         */
        fun fromWire(value: String?): ScreeningMatchStatus =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: ESCALATED
    }
}

/** Outcome of screening a single name against the sanctions lists. */
data class ScreeningResult(
    val subject: String,
    val role: ScreeningRole,
    val status: ScreeningMatchStatus,
    val score: Double,
    val matchedEntity: String?,
)

/** The verdict the policy renders over all screened names of one payment. */
enum class ScreeningDecision { CLEAR, REVIEW, BLOCK }

/**
 * Pure decision over a payment's screening results (ADR-0032 §B), one copy for every rail
 * (ADR-0353 D4). The block threshold is the sanctions service's own `isHighRisk` bound, so the
 * two never drift:
 *
 *  - **BLOCK**  — any HIT, any ESCALATED, or a POTENTIAL_HIT strictly above [POTENTIAL_HIT_BLOCK_THRESHOLD].
 *  - **REVIEW** — any POTENTIAL_HIT at or below the threshold (false-positive candidate → human review).
 *  - **CLEAR**  — everything else (CLEAR / WHITELISTED), including an empty result set.
 *
 * BLOCK dominates REVIEW dominates CLEAR.
 */
object ScreeningPolicy {

    const val POTENTIAL_HIT_BLOCK_THRESHOLD = 0.85

    fun decide(results: List<ScreeningResult>): ScreeningDecision = when {
        results.any(::isBlock) -> ScreeningDecision.BLOCK
        results.any(::isReview) -> ScreeningDecision.REVIEW
        else -> ScreeningDecision.CLEAR
    }

    private fun isBlock(r: ScreeningResult): Boolean = r.status == ScreeningMatchStatus.HIT ||
        r.status == ScreeningMatchStatus.ESCALATED ||
        (r.status == ScreeningMatchStatus.POTENTIAL_HIT && r.score > POTENTIAL_HIT_BLOCK_THRESHOLD)

    private fun isReview(r: ScreeningResult): Boolean = r.status == ScreeningMatchStatus.POTENTIAL_HIT
}
