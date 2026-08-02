// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval

import java.time.OffsetDateTime

/**
 * In-memory [ApprovalStore] test double, extracted from `AuthorizeInterceptorTest` (#3349).
 *
 * It re-implements the same rules as [com.openbank.libs.approval.impl.RedisApprovalStore] —
 * including the self-approval guard — which is exactly why it must not live as an anonymous copy
 * inside one test class. Two independent statements of a security rule drift: the copy is invisible
 * to any sweep over `src/main`, and if the production check is ever tightened (case-insensitive
 * comparison, subject-vs-name normalisation, a null-maker check) the double keeps certifying the old
 * behaviour.
 *
 * `ApprovalStoreContractTest` binds both implementations to one executable contract, which is the
 * only arrangement under which two copies of a rule are safe. Do not delete the guard below to
 * "remove the duplication": a double that permits what production forbids turns any future
 * interceptor test of a maker self-decide into a vacuous green, in the component the guard protects.
 *
 * [created] is load-bearing for the interceptor tests, and [createdAt] is fixed so `findPending`'s
 * ordering is deterministic.
 */
class InMemoryApprovalStore : ApprovalStore {
    val created = mutableListOf<PendingApproval>()
    private val approvals = mutableMapOf<String, PendingApproval>()
    private var nextId = 0

    override suspend fun create(
        action: String,
        resourceId: String?,
        makerId: String,
        ttlSeconds: Long,
    ): PendingApproval {
        val approval = PendingApproval(
            id = "approval-${nextId++}",
            action = action,
            resourceId = resourceId,
            makerId = makerId,
            status = ApprovalStatus.PENDING,
            createdAt = OffsetDateTime.parse("2026-06-22T10:20:00Z"),
        )
        created += approval
        approvals[approval.id] = approval
        return approval
    }

    override suspend fun find(id: String): PendingApproval? = approvals[id]

    override suspend fun findPending(limit: Int): List<PendingApproval> =
        approvals.values.filter { it.status == ApprovalStatus.PENDING }.sortedBy { it.createdAt }.take(limit)

    override suspend fun decide(id: String, decidedBy: String, approve: Boolean): PendingApproval? {
        val approval = approvals[id] ?: return null
        // Segregation of duties, checked BEFORE the status guard — same order as RedisApprovalStore,
        // and the contract test asserts that order rather than assuming it.
        if (decidedBy == approval.makerId) throw SelfApprovalNotAllowedException(approval.makerId)
        if (approval.status != ApprovalStatus.PENDING) {
            throw InvalidApprovalStateException(id, ApprovalStatus.PENDING, approval.status)
        }
        val decided = approval.copy(
            status = if (approve) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED,
            decidedBy = decidedBy,
        )
        approvals[id] = decided
        return decided
    }

    override suspend fun markExecuted(id: String): PendingApproval? {
        val approval = approvals[id] ?: return null
        if (approval.status != ApprovalStatus.APPROVED) {
            throw InvalidApprovalStateException(id, ApprovalStatus.APPROVED, approval.status)
        }
        val executed = approval.copy(status = ApprovalStatus.EXECUTED)
        approvals[id] = executed
        return executed
    }
}
