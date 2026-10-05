// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval

import java.time.OffsetDateTime

/**
 * Second-approver (maker-checker) record for a money-path action OPA flagged
 * `four_eyes_required` (ADR-0034, ADR-0155, issue #395). The maker's original
 * request is paused — [com.openbank.libs.authz.AuthorizeInterceptor] returns
 * HTTP 202 instead of invoking the annotated method — until a DIFFERENT
 * principal decides this record via the service's own approval-decide
 * endpoint. The maker then retries the original request carrying the approval
 * id (`X-Approval-Id` header); the interceptor lets it proceed exactly once.
 *
 * An approval is bound to the request it was issued for: [PendingApproval.requestFingerprint]
 * is recorded when the maker's request is paused, and only a retry whose fingerprint is equal
 * (same endpoint, same arguments) can consume it. An approval with no fingerprint — one created
 * directly by application code rather than by the interceptor — never satisfies an intercepted
 * request.
 */
enum class ApprovalStatus { PENDING, APPROVED, REJECTED, EXECUTED }

/** Provenance captured at creation, not inferred later from the displayed maker id. */
enum class MakerActorKind { HUMAN, AI_AGENT, SERVICE_ACCOUNT, CUSTOMER_PARTY, UNKNOWN }

data class PendingApproval(
    val id: String,
    val action: String,
    val resourceId: String?,
    val makerId: String,
    val status: ApprovalStatus,
    val createdAt: OffsetDateTime,
    val decidedBy: String? = null,
    val decidedAt: OffsetDateTime? = null,
    /** Hex SHA-256 of the paused request (see [ApprovalRequestBinding]); `null` when unbound. */
    val requestFingerprint: String? = null,
    /** Bounded, human-readable description of the paused request for the checker; `null` when unbound. */
    val summary: String? = null,
    val makerActorKind: MakerActorKind = MakerActorKind.UNKNOWN,
)

/**
 * What a [PendingApproval] is bound to. [fingerprint] is compared for equality on execution;
 * [summary] is informational only and is never used for the comparison.
 */
data class ApprovalRequestBinding(val fingerprint: String, val summary: String?) {
    init {
        require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
        require(summary == null || summary.length <= MAX_SUMMARY_LENGTH) {
            "summary must be at most $MAX_SUMMARY_LENGTH characters"
        }
    }

    companion object {
        const val MAX_SUMMARY_LENGTH = 1024
    }
}

/**
 * The maker already holds [limit] PENDING approvals for [action]. Bounds how many open records one
 * principal can accumulate; a decided or expired approval frees its slot.
 */
class ApprovalLimitExceededException(val action: String, val limit: Int) :
    IllegalStateException("too many pending approvals for action '$action' (limit $limit); wait for a decision")

/** A principal tried to decide (approve/reject) their own [PendingApproval]. */
class SelfApprovalNotAllowedException(makerId: String) :
    IllegalStateException("principal '$makerId' cannot approve/reject their own request (segregation of duties)")

/**
 * A [PendingApproval] was not in the required status for the attempted transition
 * (code review finding: without this, [ApprovalStore.decide] could re-decide an
 * already-EXECUTED approval, flipping it back to APPROVED and letting the maker
 * replay the original request a second time — the "one-time consumption" contract
 * on [ApprovalStore.markExecuted] was documented but not actually enforced).
 */
class InvalidApprovalStateException(id: String, expected: ApprovalStatus, actual: ApprovalStatus) :
    IllegalStateException("approval '$id' must be $expected for this operation, but is $actual")

interface ApprovalStore {
    /**
     * Records a new PENDING approval.
     *
     * @param binding the request this approval may later execute; `null` for an approval that
     *   application code decides and consumes itself.
     * @throws ApprovalLimitExceededException when [makerId] already holds the store's configured
     *   maximum of PENDING approvals for [action].
     */
    suspend fun create(
        action: String,
        resourceId: String?,
        makerId: String,
        ttlSeconds: Long = 86400,
        binding: ApprovalRequestBinding? = null,
        makerActorKind: MakerActorKind = MakerActorKind.UNKNOWN,
    ): PendingApproval

    suspend fun find(id: String): PendingApproval?

    /**
     * PENDING approvals, oldest first (FIFO fairness for the checker queue) — the read side of
     * the unified approval inbox (ADR-0227 D2): each service exposes its pending queue over REST
     * and the admin-UI BFF federates them into one supervisor surface. Read-only; deciding stays
     * on the per-service decide endpoint. A store only ever returns, finds and decides the
     * approvals of its own service, even when several services share one backing store.
     */
    suspend fun findPending(limit: Int = 100): List<PendingApproval>

    /**
     * Records a checker's decision. An approval can only ever be decided once: the check of
     * status and maker and the write are one atomic step, so of two concurrent decisions exactly
     * one succeeds and the other gets [InvalidApprovalStateException].
     *
     * @throws SelfApprovalNotAllowedException if [decidedBy] is the original maker —
     *   enforced here, not just by the caller's REST layer, so segregation of duties
     *   holds even if a service's endpoint forgets to re-check it.
     * @throws InvalidApprovalStateException if the approval is not currently PENDING —
     *   prevents re-deciding an already APPROVED/REJECTED/EXECUTED approval, which would
     *   otherwise let a consumed approval be replayed.
     */
    suspend fun decide(id: String, decidedBy: String, approve: Boolean): PendingApproval?

    /**
     * One-time consumption: an EXECUTED approval can never be replayed. APPROVED -> EXECUTED is
     * one atomic step, so of N concurrent calls exactly one succeeds.
     *
     * @throws InvalidApprovalStateException if the approval is not currently APPROVED —
     *   e.g. a concurrent second consumption attempt on the same approval.
     */
    suspend fun markExecuted(id: String): PendingApproval?
}
