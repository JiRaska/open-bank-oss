// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.usecase

import com.openbank.pid.application.port.`in`.DecideCaseCommand
import com.openbank.pid.application.port.`in`.IdentityAdjudicationUseCase
import com.openbank.pid.application.port.`in`.ManageVerificationCaseUseCase
import com.openbank.pid.application.port.`in`.OpenCaseCommand
import com.openbank.pid.application.port.`in`.PriorAdjudication
import com.openbank.pid.application.port.`in`.ReopenCaseCommand
import com.openbank.pid.application.port.out.PartyEventPublisher
import com.openbank.pid.application.port.out.VerificationCaseRepository
import com.openbank.pid.domain.event.VerificationCaseDecidedEvent
import com.openbank.pid.domain.event.VerificationCaseOpenedEvent
import com.openbank.pid.domain.model.IllegalCaseTransition
import com.openbank.pid.domain.model.VerificationCase
import com.openbank.pid.domain.model.VerificationCaseStatus
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Four-eyes identity-verification case management (ADR-0072 §1 / ADR-0030).
 *
 * As [IdentityAdjudicationUseCase] it backs the resolver: opens a durable case for an ambiguous
 * applicant (idempotent per dedup key) and answers the adjudication-cache consult. As
 * [ManageVerificationCaseUseCase] it backs the operator cockpit: lists active cases and records
 * the two-distinct-approver decision, emitting a decided event the moment concurrence is reached.
 */
@ApplicationScoped
class VerificationCaseService(
    private val repository: VerificationCaseRepository,
    private val eventPublisher: PartyEventPublisher,
    private val clock: Clock,
) : IdentityAdjudicationUseCase,
    ManageVerificationCaseUseCase {

    // ── IdentityAdjudicationUseCase (resolver side) ────────────────────────────────

    override suspend fun priorDecision(dedupKey: String): PriorAdjudication? {
        val decided = repository.findLatestDecidedByDedupKey(dedupKey) ?: return null
        val verdict = decided.finalVerdict ?: return null
        return PriorAdjudication(caseId = decided.id, verdict = verdict, linkPartyId = decided.finalLinkPartyId)
    }

    // The dedup-race catch must handle any constraint-violation variant (driver/Hibernate wrapping).
    @Suppress("TooGenericExceptionCaught")
    override suspend fun openOrReuse(command: OpenCaseCommand): UUID {
        repository.findActiveByDedupKey(command.dedupKey)?.let { return it.id }

        val case = VerificationCase.open(
            id = UUID.randomUUID(),
            dedupKey = command.dedupKey,
            trigger = command.trigger,
            applicant = command.applicant,
            blindIndex = command.blindIndex,
            candidatePartyIds = command.candidatePartyIds,
            now = Instant.now(clock),
        )
        return try {
            repository.save(case)
            eventPublisher.publish(
                VerificationCaseOpenedEvent(
                    aggregateId = case.id,
                    trigger = case.trigger,
                    candidatePartyIds = case.candidatePartyIds,
                ),
            )
            case.id
        } catch (ex: Exception) {
            // uq_ivc_active_dedup lost a concurrent race — reuse the winner's case instead of failing.
            Log.debug("openOrReuse: active case insert lost the dedup race (${ex.message}); reusing existing")
            repository.findActiveByDedupKey(command.dedupKey)?.id ?: throw ex
        }
    }

    // ── ManageVerificationCaseUseCase (cockpit side) ───────────────────────────────

    override suspend fun listActive(): List<VerificationCase> = repository.listByStatuses(
        listOf(VerificationCaseStatus.OPEN, VerificationCaseStatus.AWAITING_SECOND_APPROVAL),
    )

    override suspend fun get(id: UUID): VerificationCase? = repository.findById(id)

    override suspend fun decide(command: DecideCaseCommand): VerificationCase {
        val case = repository.findById(command.caseId)
            ?: throw VerificationCaseNotFoundException("verification case ${command.caseId} not found")
        val now = Instant.now(clock)
        val updated = when (case.status) {
            VerificationCaseStatus.OPEN ->
                case.proposeFirst(command.approver, command.verdict, command.linkPartyId, command.notes, now)

            VerificationCaseStatus.AWAITING_SECOND_APPROVAL ->
                case.confirmSecond(command.approver, command.verdict, command.linkPartyId, now)

            VerificationCaseStatus.DECIDED ->
                throw IllegalCaseTransition("case ${command.caseId} is already DECIDED")
        }
        repository.update(updated)

        if (updated.status == VerificationCaseStatus.DECIDED) {
            eventPublisher.publish(
                VerificationCaseDecidedEvent(
                    aggregateId = updated.id,
                    verdict = updated.finalVerdict!!,
                    linkPartyId = updated.finalLinkPartyId,
                    firstApprover = updated.firstApprover!!,
                    secondApprover = updated.secondApprover!!,
                ),
            )
            Log.info(
                "Identity verification case ${updated.id} DECIDED ${updated.finalVerdict} " +
                    "by ${updated.firstApprover} + ${updated.secondApprover}",
            )
        }
        return updated
    }

    override suspend fun reopen(command: ReopenCaseCommand): VerificationCase {
        val case = repository.findById(command.caseId)
            ?: throw VerificationCaseNotFoundException("verification case ${command.caseId} not found")
        val updated = case.reopen(Instant.now(clock))
        repository.update(updated)
        Log.info("Identity verification case ${updated.id} reopened by ${command.actor}")
        return updated
    }
}

/** Raised when a verification case id does not exist (mapped to HTTP 404). */
class VerificationCaseNotFoundException(message: String) : RuntimeException(message)
