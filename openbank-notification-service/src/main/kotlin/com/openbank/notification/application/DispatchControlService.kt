// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.notification.application

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.governance.Proposal
import com.openbank.notification.application.port.out.DispatchControlStore
import com.openbank.notification.domain.ops.DispatchControlSnapshot
import com.openbank.notification.domain.ops.DispatchState
import com.openbank.notification.domain.ops.ResumeAction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Tier A break-glass control for the notification dispatch loop (ADR-0047).
 *
 * Governance asymmetry — the fail-safe direction is cheap, the risk-increasing one is gated:
 *   - [halt]: single-actor break-glass (stopping outbound notifications is safe); takes effect
 *     immediately and flags a mandatory deferred independent review.
 *   - [proposeResume] + [approveResume]: re-enabling needs four-eyes via [Proposal] — the
 *     approver must differ from the proposer (enforced in `libs/governance`, not by convention).
 *
 * Every transition emits an [AuditEvent] so the change is reconstructible (DORA Art. 17,
 * GDPR Art. 30). State is read fresh from the [DispatchControlStore], which is the single source
 * of truth all replicas converge on.
 */
@ApplicationScoped
class DispatchControlService(private val store: DispatchControlStore, private val audit: AuditEventPublisher) {
    @Inject lateinit var clock: Clock

    suspend fun snapshot(): DispatchControlSnapshot = store.current(KEY) ?: DispatchControlSnapshot(
        controlKey = KEY,
        state = DispatchState.ENABLED,
        version = 0,
        reason = null,
        actor = null,
        effectiveFrom = Instant.EPOCH,
        deferredReviewRequired = false,
    )

    suspend fun isHalted(): Boolean = snapshot().state == DispatchState.HALTED

    suspend fun history(limit: Int = 20): List<DispatchControlSnapshot> = store.history(KEY, limit)

    /** Break-glass halt: single actor, immediate effect, deferred review required. */
    suspend fun halt(actor: String, reason: String): DispatchControlSnapshot {
        require(actor.isNotBlank()) { "actor is required" }
        require(reason.isNotBlank()) { "reason is required" }
        val next = snapshot().let { prev ->
            DispatchControlSnapshot(KEY, DispatchState.HALTED, prev.version + 1, reason, actor, Instant.now(clock), true)
        }
        store.append(next)
        emit(actor, "notification.dispatch.halted", reason)
        return next
    }

    suspend fun proposeResume(proposer: String, reason: String): Proposal<ResumeAction> {
        require(proposer.isNotBlank()) { "proposer is required" }
        require(reason.isNotBlank()) { "reason is required" }
        val proposal = Proposal(
            id = UUID.randomUUID().toString(),
            action = ResumeAction(KEY, reason),
            proposedBy = proposer,
            proposedAt = Instant.now(clock),
        )
        store.saveProposal(proposal)
        emit(proposer, "notification.dispatch.resume.proposed", reason)
        return proposal
    }

    /** Approve and execute a resume. Throws [com.openbank.libs.governance.MakerCheckerViolation] if checker == proposer. */
    suspend fun approveResume(proposalId: String, checker: String, reason: String?): DispatchControlSnapshot {
        require(checker.isNotBlank()) { "checker is required" }
        val proposal = store.findProposal(proposalId)
            ?: throw NoSuchElementException("resume proposal $proposalId not found")

        val approved = proposal.approve(checker, Instant.now(clock), reason)
        store.saveProposal(approved)
        emit(checker, "notification.dispatch.resume.approved", reason)

        store.saveProposal(approved.markExecuted(Instant.now(clock)))
        val next = snapshot().let { prev ->
            DispatchControlSnapshot(
                KEY,
                DispatchState.ENABLED,
                prev.version + 1,
                proposal.action.reason,
                checker,
                Instant.now(clock),
                false,
            )
        }
        store.append(next)
        emit(checker, "notification.dispatch.resumed", proposal.action.reason)
        return next
    }

    suspend fun rejectResume(proposalId: String, checker: String, reason: String?): Proposal<ResumeAction> {
        require(checker.isNotBlank()) { "checker is required" }
        val proposal = store.findProposal(proposalId)
            ?: throw NoSuchElementException("resume proposal $proposalId not found")
        val rejected = proposal.reject(checker, Instant.now(clock), reason)
        store.saveProposal(rejected)
        emit(checker, "notification.dispatch.resume.rejected", reason)
        return rejected
    }

    private suspend fun emit(actor: String, operation: String, reason: String?) {
        audit.publish(
            AuditEvent(
                actorId = actor,
                actorType = "HUMAN",
                operation = operation,
                resourceType = "notification.dispatch",
                resourceId = KEY,
                result = AuditResult.SUCCESS,
                payload = mapOf("reason" to reason),
            ),
        )
    }

    companion object {
        const val KEY = "notification-dispatch"
    }
}
