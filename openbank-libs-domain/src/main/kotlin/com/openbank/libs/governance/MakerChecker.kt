// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.governance

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
) {
    /**
     * Approve as [checker]. Enforces the four-eyes rule: [checker] must differ from [proposedBy].
     * Only a [ProposalState.PROPOSED] proposal can be approved.
     */
    fun approve(checker: String, at: Instant, reason: String? = null): Proposal<T> {
        check(ProposalState.PROPOSED)
        if (checker == proposedBy) {
            throw MakerCheckerViolation("four-eyes: approver '$checker' must differ from proposer '$proposedBy'")
        }
        return copy(state = ProposalState.APPROVED, decidedBy = checker, decidedAt = at, decisionReason = reason)
    }

    /** Reject as [checker]. Like [approve], a rejecter should differ from the proposer. */
    fun reject(checker: String, at: Instant, reason: String? = null): Proposal<T> {
        check(ProposalState.PROPOSED)
        if (checker == proposedBy) {
            throw MakerCheckerViolation("four-eyes: rejecter '$checker' must differ from proposer '$proposedBy'")
        }
        return copy(state = ProposalState.REJECTED, decidedBy = checker, decidedAt = at, decisionReason = reason)
    }

    /** The maker withdraws their own still-pending proposal. */
    fun withdraw(by: String, at: Instant): Proposal<T> {
        check(ProposalState.PROPOSED)
        if (by != proposedBy) {
            throw MakerCheckerViolation("only the proposer '$proposedBy' may withdraw, not '$by'")
        }
        return copy(state = ProposalState.WITHDRAWN, decidedAt = at)
    }

    /** Execute an approved proposal exactly once. */
    fun markExecuted(at: Instant): Proposal<T> {
        check(ProposalState.APPROVED)
        return copy(state = ProposalState.EXECUTED, executedAt = at)
    }

    val isTerminal: Boolean
        get() = state == ProposalState.REJECTED || state == ProposalState.WITHDRAWN || state == ProposalState.EXECUTED

    private fun check(required: ProposalState) {
        if (state != required) {
            throw MakerCheckerViolation("illegal transition: proposal '$id' is $state, required $required")
        }
    }
}
