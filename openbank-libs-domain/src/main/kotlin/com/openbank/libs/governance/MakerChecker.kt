// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.governance

import java.time.Duration
import java.time.Instant

/**
 * Maker-checker (four-eyes) state machine for sensitive operations (ADR-0023, ADR-0034).
 *
 * Originally introduced for analytics reloads (ADR-0023, finding F3); generalised here into
 * `libs/governance` by ADR-0034 because it is not analytics-specific — any sensitive runtime
 * action (a governed parameter change, a break-glass resume) is gated through the same
 * primitive. `com.openbank.libs.analytics` re-exports these types via `typealias` for
 * backward compatibility; analytics call sites migrate opportunistically.
 *
 * Reloading a 10-year regulatory store, or re-enabling a halted payment rail, is a
 * high-impact action: EBA/GL/2020/06 (ICT & security risk management — segregation of
 * duties) and BCBS 239 require that such an action cannot be performed by a single person.
 * This is a pure state machine; callers persist proposals through a port and gate their REST
 * verbs on it, so the *segregation of duties is enforced in code*, not by convention.
 *
 * Lifecycle:
 *
 * ```
 *   PROPOSED --approve(checker != maker)--> APPROVED --execute--> EXECUTED
 *      |                                                              ^
 *      |--reject--> REJECTED                                          |
 *      |--withdraw(maker)--> WITHDRAWN          (only APPROVED can be executed)
 * ```
 *
 * The single hard rule that makes it "four-eyes": [Proposal.approve] **must** reject a checker
 * equal to the maker. Everything else is ordinary state-transition hygiene (no double-approve,
 * no execute before approve, no mutation after a terminal state).
 *
 * **Person-bound proposals.** Comparing account names lets one person with two staff accounts
 * pass four-eyes. A proposal created with [Proposal.Companion.propose] is bound to the proposer's
 * PERSON ([StaffIdentity.personId]) and is decided only through [Proposal.approveBy] /
 * [Proposal.rejectBy] / [Proposal.withdrawBy], which:
 *  - compare person ids, and fail CLOSED when either side has none;
 *  - require strong (multi-factor) authentication of the decider;
 *  - refuse a decider in the caller-supplied conflict set (e.g. the analyst who escalated the case);
 *  - refuse any decision once the proposal has expired ([expiresAt] is mandatory and bounded).
 * The account-based [approve] / [reject] / [withdraw] remain for proposals created without a
 * person, and REFUSE a person-bound one — so a call site cannot fall back to the weaker check.
 */
enum class ProposalState { PROPOSED, APPROVED, REJECTED, WITHDRAWN, EXECUTED }

/** Raised when a transition is not allowed from the current state, or violates segregation of duties. */
class MakerCheckerViolation(message: String) : IllegalStateException(message)

/**
 * An auditable proposal for a sensitive action. Immutable; each transition returns a new
 * instance so the full decision trail can be persisted as evidence (who proposed, who approved,
 * when). [T] is the action payload — kept generic so libs need not depend on caller DTOs.
 */
data class Proposal<T>(
    val id: String,
    val action: T,
    val proposedBy: String,
    val proposedAt: Instant,
    val state: ProposalState = ProposalState.PROPOSED,
    val decidedBy: String? = null,
    val decidedAt: Instant? = null,
    val decisionReason: String? = null,
    val executedAt: Instant? = null,
    /** Person who proposed; non-null exactly when the proposal is person-bound (see [propose]). */
    val proposerPersonId: String? = null,
    /** Person who approved/rejected a person-bound proposal. */
    val decidedByPersonId: String? = null,
    /** After this instant a pending proposal can no longer be decided ([isOpen] is false). */
    val expiresAt: Instant? = null,
) {
    /** True when this proposal was created person-bound and must be decided via [approveBy]. */
    val isPersonBound: Boolean
        get() = proposerPersonId != null

    /** Pending and not expired — the only state in which it can be approved or rejected. */
    fun isOpen(now: Instant): Boolean = state == ProposalState.PROPOSED && !isExpired(now)

    /** Pending but past [expiresAt]. An expired proposal may be superseded by anyone. */
    fun isExpired(now: Instant): Boolean =
        state == ProposalState.PROPOSED && expiresAt != null && !now.isBefore(expiresAt)

    /**
     * Whether a NEW proposal for the same subject may replace this one at [now]: only once this one
     * is no longer open (withdrawn, decided, or expired). Never silently while it is pending — a
     * pending proposal is withdrawn by its proposer or it runs out; that is the audit trail.
     */
    fun isSupersedable(now: Instant): Boolean = !isOpen(now)

    /**
     * Approve as the PERSON [checker] (person-bound proposals). Fails closed with
     * [MakerCheckerViolation] when: the proposal is not person-bound or not open (state/expiry);
     * [checker] has no person id; [checker] is the proposer's person; [checker]'s person is in
     * [conflictedPersons]; or [checker] did not authenticate with a second factor.
     */
    fun approveBy(
        checker: StaffIdentity,
        at: Instant,
        reason: String? = null,
        conflictedPersons: Set<String> = emptySet(),
    ): Proposal<T> {
        val person = requireDecider(checker, at, conflictedPersons, "approver")
        return copy(
            state = ProposalState.APPROVED,
            decidedBy = checker.account,
            decidedByPersonId = person,
            decidedAt = at,
            decisionReason = reason,
        )
    }

    /** Reject as the PERSON [checker]; same rules as [approveBy]. */
    fun rejectBy(
        checker: StaffIdentity,
        at: Instant,
        reason: String? = null,
        conflictedPersons: Set<String> = emptySet(),
    ): Proposal<T> {
        val person = requireDecider(checker, at, conflictedPersons, "rejecter")
        return copy(
            state = ProposalState.REJECTED,
            decidedBy = checker.account,
            decidedByPersonId = person,
            decidedAt = at,
            decisionReason = reason,
        )
    }

    /**
     * The proposer PERSON withdraws their own pending proposal (any of their accounts). Allowed
     * while PROPOSED, expired or not. Nobody else can withdraw it — another person waits for expiry.
     */
    fun withdrawBy(by: StaffIdentity, at: Instant, reason: String? = null): Proposal<T> {
        requireState(ProposalState.PROPOSED)
        val proposer = proposerPersonId ?: refuse("proposal '$id' is not person-bound; use withdraw(account)")
        val person = by.person ?: refuse("four-eyes: '${by.account}' carries no person id; cannot withdraw")
        if (person != proposer) {
            refuse("only the proposing person may withdraw proposal '$id', not '${by.account}'")
        }
        return copy(
            state = ProposalState.WITHDRAWN,
            decidedBy = by.account,
            decidedByPersonId = person,
            decidedAt = at,
            decisionReason = reason,
        )
    }

    /**
     * Approve as [checker]. Enforces the four-eyes rule: [checker] must differ from [proposedBy].
     * Only a [ProposalState.PROPOSED] proposal can be approved.
     */
    fun approve(checker: String, at: Instant, reason: String? = null): Proposal<T> {
        requireState(ProposalState.PROPOSED)
        refuseAccountOnly("approve")
        if (checker == proposedBy) {
            refuse("four-eyes: approver '$checker' must differ from proposer '$proposedBy'")
        }
        return copy(state = ProposalState.APPROVED, decidedBy = checker, decidedAt = at, decisionReason = reason)
    }

    /** Reject as [checker]. Like [approve], a rejecter should differ from the proposer. */
    fun reject(checker: String, at: Instant, reason: String? = null): Proposal<T> {
        requireState(ProposalState.PROPOSED)
        refuseAccountOnly("reject")
        if (checker == proposedBy) {
            refuse("four-eyes: rejecter '$checker' must differ from proposer '$proposedBy'")
        }
        return copy(state = ProposalState.REJECTED, decidedBy = checker, decidedAt = at, decisionReason = reason)
    }

    /** The maker withdraws their own still-pending proposal. */
    fun withdraw(by: String, at: Instant): Proposal<T> {
        requireState(ProposalState.PROPOSED)
        refuseAccountOnly("withdraw")
        if (by != proposedBy) {
            refuse("only the proposer '$proposedBy' may withdraw, not '$by'")
        }
        return copy(state = ProposalState.WITHDRAWN, decidedAt = at)
    }

    /** Execute an approved proposal exactly once. */
    fun markExecuted(at: Instant): Proposal<T> {
        requireState(ProposalState.APPROVED)
        return copy(state = ProposalState.EXECUTED, executedAt = at)
    }

    val isTerminal: Boolean
        get() = state == ProposalState.REJECTED || state == ProposalState.WITHDRAWN || state == ProposalState.EXECUTED

    companion object {
        /** Upper bound on [propose]'s ttl: a pending proposal cannot block a decision for longer. */
        val MAX_TTL: Duration = Duration.ofDays(7)

        /**
         * Create a PERSON-BOUND proposal. Fails closed when [proposer] has no person id. [ttl] must
         * be positive and at most [MAX_TTL]; the proposal can only be decided before it elapses.
         */
        fun <T> propose(id: String, action: T, proposer: StaffIdentity, at: Instant, ttl: Duration): Proposal<T> {
            val person = proposer.person
                ?: refuse("four-eyes: proposer '${proposer.account}' carries no person id")
            if (ttl.isNegative || ttl.isZero || ttl > MAX_TTL) {
                refuse("four-eyes: proposal ttl $ttl must be in (0, $MAX_TTL]")
            }
            return Proposal(
                id = id,
                action = action,
                proposedBy = proposer.account,
                proposedAt = at,
                proposerPersonId = person,
                expiresAt = at.plus(ttl),
            )
        }
    }
}

private fun <T> Proposal<T>.requireDecider(
    checker: StaffIdentity,
    at: Instant,
    conflicted: Set<String>,
    role: String,
): String {
    requireState(ProposalState.PROPOSED)
    val proposer = proposerPersonId ?: refuse("proposal '$id' is not person-bound; it cannot be decided by person")
    if (isExpired(at)) refuse("proposal '$id' expired at $expiresAt")
    val person = checker.person
        ?: refuse("four-eyes: $role '${checker.account}' carries no person id")
    if (person == proposer || checker.account == proposedBy) {
        refuse("four-eyes: $role '${checker.account}' is the same person as proposer '$proposedBy'")
    }
    if (person in conflicted) {
        refuse("four-eyes: $role '${checker.account}' has a conflict of interest on proposal '$id'")
    }
    if (!checker.strongAuth) {
        refuse("four-eyes: $role '${checker.account}' must authenticate with a second factor")
    }
    return person
}

private fun Proposal<*>.refuseAccountOnly(verb: String) {
    if (isPersonBound) {
        refuse("proposal '$id' is person-bound: $verb by account is refused, use ${verb}By(StaffIdentity)")
    }
}

private fun Proposal<*>.requireState(required: ProposalState) {
    if (state != required) {
        refuse("illegal transition: proposal '$id' is $state, required $required")
    }
}

private fun refuse(message: String): Nothing = throw MakerCheckerViolation(message)
