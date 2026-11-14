// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import com.openbank.libs.governance.MakerCheckerViolation
import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import com.openbank.libs.governance.StaffIdentity
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * One finding of ongoing monitoring about a subject of an APPROVED case (ADR-0376 D6), with the
 * operator's decision about it. Every finding is decided by itself: a later finding never
 * reopens, replaces or hides an earlier decision.
 *
 * [matchKind], [listType], [matchStatus] and [detection] are the producer's vocabulary kept
 * verbatim as text: a value added upstream must still reach an operator, so nothing here
 * enumerates them. Only `PEP` is read: a PEP match is a customer-risk fact, and every other
 * kind, known or not, is a sanctions-list match — the direction that cannot approve by mistake.
 */
data class MonitoringFinding(
    val findingId: UUID,
    /** The subject of the case it is about; see [ScreeningSubject.key]. */
    val subjectKey: String,
    val matchKind: String,
    val listType: String,
    val listEntryRef: String,
    val listEntryVersion: String,
    val matchScore: Double,
    val matchStatus: String,
    val detection: String,
    val receivedAt: Instant,
    val disposition: FindingDisposition = FindingDisposition.OPEN,
    val decidedBy: String? = null,
    val decisionReason: String? = null,
    val decidedAt: Instant? = null,
) {
    val pep: Boolean get() = matchKind == PEP_KIND

    val open: Boolean get() = disposition == FindingDisposition.OPEN

    /**
     * An operator confirmed that a sanctions-list entry is this subject. That is final, and its
     * only consequence is that the relationship has to end. A confirmed PEP match is a different
     * outcome: a fact about the customer's risk, which ends nothing.
     */
    val requiresExit: Boolean get() = !pep && disposition == FindingDisposition.CONFIRMED

    /** The one and only decision of this finding; a decided finding is never decided again. */
    fun decided(disposition: FindingDisposition, operator: String, reason: String, at: Instant): MonitoringFinding {
        if (!open) throw CaseTransitionException("monitoring finding $findingId is already decided")
        val checked = DispositionRule.checked(disposition, operator, reason)
        return copy(disposition = disposition, decidedBy = operator, decisionReason = checked, decidedAt = at)
    }

    private companion object {
        const val PEP_KIND = "PEP"
    }
}

/**
 * A reviewer proposes that the exit a confirmed sanctions match requires has been carried out.
 * It ends nothing by itself: a DIFFERENT reviewer has to confirm it (four-eyes, [Proposal]).
 */
data class ExitProposal(
    val proposedAt: Instant,
    val proposedBy: String,
    val evidence: String,
    /** The proposer's PERSON; four-eyes compares persons. `null` cannot be confirmed (fails closed). */
    val proposerPersonId: String? = null,
    /** After this the proposal cannot be confirmed, and a new one may replace it. */
    val expiresAt: Instant? = null,
    /** Set when the proposer withdrew it. */
    val withdrawnBy: String? = null,
    val withdrawnAt: Instant? = null,
) {
    /**
     * Whether this is a four-eyes proposal under the person rule at all: it names the proposer's
     * person AND has an expiry. A row without either (the V15 shape) is inert: never open, so a
     * fresh proposal may replace it, and never confirmable. Nothing is backfilled.
     */
    val isPersonBound: Boolean
        get() = proposerPersonId != null && expiresAt != null

    /** Pending, person-bound and unexpired. The ONLY test of "a proposal blocks a new one". */
    fun isOpenAt(now: Instant): Boolean = isPersonBound && asProposal().isOpen(now)

    /** The fleet's four-eyes primitive over this proposal; every rule is enforced there. */
    fun asProposal(): Proposal<String> = Proposal(
        id = "exit",
        action = evidence,
        proposedBy = proposedBy,
        proposedAt = proposedAt,
        state = if (withdrawnAt != null) ProposalState.WITHDRAWN else ProposalState.PROPOSED,
        proposerPersonId = proposerPersonId,
        expiresAt = expiresAt,
    )

    companion object {
        /** How long an exit proposal stays open for a second reviewer. */
        val TTL: Duration = Duration.ofHours(72)
    }
}

/**
 * The exit a confirmed sanctions match requires has been carried out: proposed by one reviewer
 * ([CaseMonitoring.exitProposal] keeps who) and confirmed by [completedBy], a different one.
 */
data class ExitCompletion(val completedAt: Instant, val completedBy: String, val evidence: String)

/**
 * Everything ongoing monitoring has said about an approved case and what was decided. It sits
 * BESIDE the case's status and never changes it: the status is what the applicant, the events and
 * every other service read, and none of them may be able to tell that a finding exists.
 *
 * Nothing here is a switch somebody sets. Whether a review is open and whether an exit is owed
 * are read off the findings, so they cannot disagree with them.
 */
data class CaseMonitoring(
    val findings: List<MonitoringFinding> = emptyList(),
    val exitCompleted: ExitCompletion? = null,
    val exitProposal: ExitProposal? = null,
) {
    val openFindings: List<MonitoringFinding> get() = findings.filter { it.open }

    /** Since when a finding has been waiting for an operator; null when none is. */
    val reviewSince: Instant? get() = openFindings.minOfOrNull { it.receivedAt }

    /** Since when the relationship has had to end and has not: the first confirmed sanctions match. */
    val exitOwedSince: Instant?
        get() = if (exitCompleted !=
            null
        ) {
            null
        } else {
            findings.filter { it.requiresExit }.mapNotNull { it.decidedAt }.minOrNull()
        }

    /**
     * The case must stay under monitoring whatever its status becomes: a finding nobody decided,
     * or a confirmed match whose exit has not been carried out. Neither the applicant nor a timer
     * can end monitoring while this holds.
     */
    val hold: Boolean get() = openFindings.isNotEmpty() || exitOwedSince != null

    /**
     * Whether the case may NOT become ACTIVE: a hold, or a confirmed sanctions match at all — an
     * exit that was owed is never followed by an activation, carried out or not (#16028 item 1).
     */
    val blocksActivation: Boolean get() = hold || findings.any { it.requiresExit }

    /** Records [finding] once; the SAME instance when its id is already here. */
    fun recorded(finding: MonitoringFinding): CaseMonitoring =
        if (findings.any { it.findingId == finding.findingId }) this else copy(findings = findings + finding)

    fun decided(
        findingId: UUID,
        disposition: FindingDisposition,
        operator: String,
        reason: String,
        at: Instant,
    ): CaseMonitoring {
        val finding = findings.firstOrNull { it.findingId == findingId }
            ?: throw IllegalArgumentException("no monitoring finding $findingId on this case")
        val decided = finding.decided(disposition, operator, reason, at)
        return copy(findings = findings.map { if (it.findingId == findingId) decided else it })
    }

    /**
     * A reviewer proposes that the exit has been carried out. [relationshipEnded] is the owner's
     * finding that it demonstrably has (see `BusinessOnboardingCase.exitProposed`). The proposal is
     * bound to [proposer]'s PERSON and open for [ExitProposal.TTL]. It replaces an earlier one only
     * once that one is no longer open — withdrawn by its proposer, or expired — never while pending.
     */
    fun exitProposed(
        proposer: StaffIdentity,
        evidence: String,
        relationshipEnded: Boolean,
        at: Instant,
    ): CaseMonitoring {
        require(proposer.account.isNotBlank()) { "the operator proposing the exit must be identified" }
        require(evidence.trim().length >= DispositionRule.MIN_REASON) {
            "proposing an exit needs evidence of at least ${DispositionRule.MIN_REASON} characters"
        }
        requireExitRecordable(relationshipEnded)
        refuseUnless(exitProposal?.isOpenAt(at) != true) {
            "an exit is already proposed; its proposer withdraws it, or it expires at ${exitProposal?.expiresAt}"
        }
        val bound = fourEyes { Proposal.propose("exit", evidence.trim(), proposer, at, ExitProposal.TTL) }
        return copy(
            exitProposal = ExitProposal(
                proposedAt = at,
                proposedBy = bound.proposedBy,
                evidence = evidence.trim(),
                proposerPersonId = bound.proposerPersonId,
                expiresAt = bound.expiresAt,
            ),
        )
    }

    /** The proposing PERSON (any of their accounts) withdraws the pending exit proposal. */
    fun exitWithdrawn(by: StaffIdentity, at: Instant): CaseMonitoring {
        refuseUnless(exitCompleted == null) { "the exit is already confirmed" }
        val proposal = exitProposal ?: throw CaseTransitionException("no exit has been proposed for this case")
        val withdrawn = fourEyes { proposal.asProposal().withdrawBy(by, at) }
        return copy(exitProposal = proposal.copy(withdrawnBy = withdrawn.decidedBy, withdrawnAt = at))
    }

    /**
     * A second reviewer — a different PERSON, authenticated with a second factor — confirms the
     * pending, unexpired proposal; monitoring ends with it. [evidence] must be the evidence of the
     * proposal being confirmed, so a confirmation cannot land on a proposal replaced after the
     * confirmer read it. Four-eyes is the fleet's [Proposal.approveBy] rule.
     */
    fun exitConfirmed(
        checker: StaffIdentity,
        evidence: String,
        relationshipEnded: Boolean,
        at: Instant,
    ): CaseMonitoring {
        requireExitRecordable(relationshipEnded)
        val proposal = exitProposal ?: throw CaseTransitionException("no exit has been proposed for this case")
        refuseUnless(evidence.trim() == proposal.evidence) {
            "the exit proposal changed; read the case again before confirming"
        }
        refuseUnless(proposal.isPersonBound) {
            "the pending exit proposal predates the person rule and cannot be confirmed; propose it again"
        }
        val approved = fourEyes { proposal.asProposal().approveBy(checker, at) }
        return copy(exitCompleted = ExitCompletion(at, approved.decidedBy!!, proposal.evidence))
    }

    /**
     * Only a case that owes an exit can record one; every finding must be decided first (an exit
     * does not answer a finding nobody looked at); and the relationship must have ended.
     */
    private fun requireExitRecordable(relationshipEnded: Boolean) {
        refuseUnless(exitOwedSince != null) { "this case owes no exit" }
        refuseUnless(openFindings.isEmpty()) {
            "${openFindings.size} monitoring finding(s) are undecided; decide them first"
        }
        refuseUnless(relationshipEnded) {
            "the relationship has not demonstrably ended: the case must be rejected or abandoned " +
                "and the entity party CLOSED in party-service; monitoring continues until then"
        }
    }

    /** A refused transition (409), as opposed to a malformed request (`require`, 400). */
    private inline fun refuseUnless(condition: Boolean, message: () -> String) {
        if (!condition) throw CaseTransitionException(message())
    }
}

/** The one rule for an operator's decision about a screening finding, at onboarding or from monitoring. */
object DispositionRule {
    const val MIN_REASON = 10

    /** Returns the trimmed reason. */
    fun checked(disposition: FindingDisposition, operator: String, reason: String): String {
        require(disposition != FindingDisposition.OPEN) { "a disposition must be CLEARED_FALSE_POSITIVE or CONFIRMED" }
        require(operator.isNotBlank()) { "the deciding operator must be identified" }
        require(reason.trim().length >= MIN_REASON) {
            "a disposition needs a reason of at least $MIN_REASON characters"
        }
        return reason.trim()
    }
}

/** The libs four-eyes refusal as a refused transition (409), keeping its cause. */
private inline fun <R> fourEyes(block: () -> R): R = try {
    block()
} catch (e: MakerCheckerViolation) {
    throw CaseTransitionException(e.message ?: "four-eyes refused", e)
}
