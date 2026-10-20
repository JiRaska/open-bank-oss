// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.port.out

import com.openbank.fraud.domain.rules.FraudThresholdSet
import com.openbank.libs.governance.ProposalState
import java.time.Instant
import java.util.UUID

/**
 * One row of the threshold-set four-eyes workflow (ADR-0347): a proposed version of the money
 * thresholds, and — once decided — who decided it and when it takes effect.
 *
 * [effectiveFrom] is what the maker asked for (null = "on approval"); [activeFrom] is what the
 * approval fixed: the later of [effectiveFrom] and the approval instant, so an approval never
 * back-dates a set into decisions already made under its predecessor.
 */
data class ThresholdSetProposal(
    val id: UUID,
    val set: FraudThresholdSet,
    val contentHash: String,
    val effectiveFrom: Instant?,
    val state: ProposalState,
    val proposedBy: String,
    val proposedAt: Instant,
    val decidedBy: String? = null,
    val decidedAt: Instant? = null,
    val decisionReason: String? = null,
    val activeFrom: Instant? = null,
)

/** Persistence of threshold-set versions and their four-eyes workflow (ADR-0347). */
interface FraudThresholdRepository {

    /**
     * The set the engine must use at [at]: the EXECUTED version with the latest `activeFrom` not
     * after [at] (ties broken by the higher version). Null when none is active — the engine then
     * fails closed.
     */
    suspend fun findActive(at: Instant): FraudThresholdSet?

    suspend fun insert(proposal: ThresholdSetProposal)

    suspend fun findById(id: UUID): ThresholdSetProposal?

    suspend fun findByState(state: ProposalState): List<ThresholdSetProposal>

    /** Highest version in any live state (PROPOSED/APPROVED/EXECUTED), or null when the table is empty. */
    suspend fun maxLiveVersion(): Int?

    /**
     * Write [decided]'s decision **only if** the stored row is still PROPOSED, as one statement.
     * Returns 1 when this caller claimed the decision, 0 when another decision got there first —
     * the caller must treat 0 as a refusal (the lending compliance-pack lesson: a read-then-write
     * lets two concurrent decisions both pass the state check).
     */
    suspend fun compareAndSetDecision(decided: ThresholdSetProposal): Int
}
