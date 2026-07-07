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

interface ApprovalStore {
    suspend fun create(action: String, resourceId: String?, makerId: String, ttlSeconds: Long = 86400): PendingApproval

    suspend fun find(id: String): PendingApproval?

    /**
     * Records a checker's decision.
     *
     * @throws SelfApprovalNotAllowedException if [decidedBy] is the original maker —
     *   enforced here, not just by the caller's REST layer, so segregation of duties
     *   holds even if a service's endpoint forgets to re-check it.
     */
    suspend fun decide(id: String, decidedBy: String, approve: Boolean): PendingApproval?

    /** One-time consumption: an EXECUTED approval can never be replayed. */
    suspend fun markExecuted(id: String): PendingApproval?
}
