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
 */
enum class ApprovalStatus { PENDING, APPROVED, REJECTED, EXECUTED }

data class PendingApproval(
    val id: String,
    val action: String,
    val resourceId: String?,
    val makerId: String,
    val status: ApprovalStatus,
    val createdAt: OffsetDateTime,
    val decidedBy: String? = null,
    val decidedAt: OffsetDateTime? = null,
)

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
    suspend fun create(action: String, resourceId: String?, makerId: String, ttlSeconds: Long = 86400): PendingApproval

    suspend fun find(id: String): PendingApproval?

    /**
     * PENDING approvals, oldest first (FIFO fairness for the checker queue) — the read side of
     * the unified approval inbox (ADR-0227 D2): each service exposes its pending queue over REST
     * and the admin-UI BFF federates them into one supervisor surface. Read-only; deciding stays
     * on the per-service decide endpoint.
     */
    suspend fun findPending(limit: Int = 100): List<PendingApproval>

    /**
     * Records a checker's decision. An approval can only ever be decided once.
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
     * One-time consumption: an EXECUTED approval can never be replayed.
     *
     * @throws InvalidApprovalStateException if the approval is not currently APPROVED —
     *   e.g. a concurrent second consumption attempt on the same approval.
     */
    suspend fun markExecuted(id: String): PendingApproval?
}
