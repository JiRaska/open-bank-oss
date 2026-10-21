// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.usecase

import com.openbank.fraud.application.port.out.FraudThresholdRepository
import com.openbank.fraud.application.port.out.ThresholdSetProposal
import com.openbank.fraud.domain.rules.FraudThresholdSet
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Four-eyes activation of fraud threshold sets (ADR-0347, the ADR-0212 D4 pattern applied to fraud).
 *
 * A maker proposes a complete new version; a DIFFERENT principal approves or rejects it. Nothing a
 * maker does changes a verdict: the scoring path reads only EXECUTED rows
 * ([FraudThresholdRepository.findActive]), and a row becomes EXECUTED only through [decide] with a
 * checker other than the proposer — [Proposal.approve] raises
 * [com.openbank.libs.governance.MakerCheckerViolation] otherwise (HTTP 422).
 *
 * There is no in-memory registry to converge, deliberately: every score reads the active set from
 * the table, so an approval served by one replica is in force on every replica at its `activeFrom`,
 * with no refresh window in which pods disagree (the divergence lending's pack registry needed a
 * refresher to bound, #3467).
 */
@ApplicationScoped
class FraudThresholdActivationService(private val repository: FraudThresholdRepository, private val clock: Clock) {
    private val log = Logger.getLogger(FraudThresholdActivationService::class.java)

    /**
     * Record a proposal. Invalid content is an [IllegalArgumentException] (HTTP 400) raised by
     * [FraudThresholdSet]'s own validation. A retry of an identical still-PROPOSED proposal replays
     * it instead of stacking a duplicate for the checker (content-addressed, as ADR-0297 does for
     * compliance packs).
     */
    suspend fun propose(set: FraudThresholdSet, effectiveFrom: Instant?, maker: String): ThresholdSetProposal {
        require(maker.isNotBlank()) { "proposer identity is required" }
        val now = clock.instant()
        require(effectiveFrom == null || !effectiveFrom.isBefore(now)) {
            "effectiveFrom must not be in the past — a threshold set cannot be back-dated over decisions already made"
        }
        val hash = set.contentHash
        repository.findByState(ProposalState.PROPOSED)
            .firstOrNull {
                it.contentHash == hash && it.set.version == set.version && it.effectiveFrom == effectiveFrom
            }
            ?.let { return it }
        val highest = repository.maxLiveVersion() ?: 0
        require(set.version > highest) {
            "threshold set version ${set.version} is not above the highest live version $highest"
        }
        val proposal = ThresholdSetProposal(
            id = Ids.newId(),
            set = set,
            contentHash = hash,
            effectiveFrom = effectiveFrom,
            state = ProposalState.PROPOSED,
            proposedBy = maker,
            proposedAt = now,
        )
        repository.insert(proposal)
        // Operator identities are retained in the access-controlled audit row, not operational logs.
        log.infof("fraud threshold set v%d proposed (hash %s)", set.version, hash)
        return proposal
    }

    /** Approve (→ EXECUTED, in force from `activeFrom`) or reject. The checker must not be the maker. */
    suspend fun decide(id: UUID, approve: Boolean, checker: String, reason: String?): ThresholdSetProposal {
        require(checker.isNotBlank()) { "checker identity is required" }
        val stored = requireNotNull(repository.findById(id)) { "threshold set proposal not found: $id" }
        require(stored.state == ProposalState.PROPOSED) {
            "threshold set proposal $id is ${stored.state}, not decidable"
        }
        val now = clock.instant()
        val workflow = Proposal(id.toString(), stored.set, stored.proposedBy, stored.proposedAt)
        val decided = if (approve) {
            workflow.approve(checker, now, reason).markExecuted(now)
        } else {
            workflow.reject(checker, now, reason)
        }
        val result = stored.copy(
            state = decided.state,
            decidedBy = decided.decidedBy,
            decidedAt = decided.decidedAt,
            decisionReason = decided.decisionReason,
            activeFrom = if (approve) maxOf(stored.effectiveFrom ?: now, now) else null,
        )
        require(repository.compareAndSetDecision(result) == 1) {
            "threshold set proposal $id was decided concurrently and is no longer decidable"
        }
        log.infof(
            "fraud threshold set v%d %s%s",
            stored.set.version,
            result.state,
            result.activeFrom?.let { ", in force from $it" } ?: "",
        )
        return result
    }

    suspend fun listPending(): List<ThresholdSetProposal> = repository.findByState(ProposalState.PROPOSED)

    suspend fun active(): FraudThresholdSet? = repository.findActive(clock.instant())
}
