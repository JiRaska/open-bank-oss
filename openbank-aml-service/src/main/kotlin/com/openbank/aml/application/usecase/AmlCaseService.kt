// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.`in`.CreateAmlCaseCommand
import com.openbank.aml.application.port.`in`.ListAmlCasesQuery
import com.openbank.aml.application.port.`in`.UpdateAmlDecisionCommand
import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.domain.event.AmlCaseStatusChangedEvent
import com.openbank.aml.domain.event.toCreatedEvent
import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

class AmlCaseNotFoundException(caseId: UUID) : RuntimeException("AML case not found: $caseId")
class InvalidAmlCaseStateTransitionException(message: String) : RuntimeException(message)

@ApplicationScoped
class AmlCaseService(
    private val amlCaseRepository: AmlCaseRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : AmlCaseUseCase {

    override suspend fun createCase(command: CreateAmlCaseCommand): AmlCase {
        amlCaseRepository.findByIdempotencyKey(command.idempotencyKey)?.let { return it }

        val now = Instant.now(clock)
        val amlCase = AmlCase(
            id = UUID.randomUUID(),
            idempotencyKey = command.idempotencyKey,
            partyId = command.partyId,
            accountId = command.accountId,
            transactionId = command.transactionId,
            customerReference = command.customerReference.trim(),
            screeningType = command.screeningType,
            riskLevel = command.riskLevel,
            status = initialStatus(command.riskLevel),
            alertCode = command.alertCode.trim(),
            alertDetail = command.alertDetail?.trim()?.ifBlank { null },
            matchedEntity = command.matchedEntity?.trim()?.ifBlank { null },
            decisionReason = null,
            assignedAnalyst = null,
            decidedBy = null,
            screenedAt = now,
            decidedAt = null,
            createdAt = now,
            updatedAt = now,
        )

        // ADR-0050: the aggregate and its domain event commit atomically via the outbox.
        return try {
            amlCaseRepository.save(amlCase, caseCreatedEvent(amlCase, now))
        } catch (e: Exception) {
            org.jboss.logging.Logger.getLogger(AmlCaseService::class.java)
                .errorf(
                    "createCase save failed: %s: %s | cause: %s",
                    e::class.qualifiedName,
                    e.message,
                    e.cause?.message,
                )
            throw e
        }
    }

    override suspend fun getCase(caseId: UUID): AmlCase =
        amlCaseRepository.findById(caseId) ?: throw AmlCaseNotFoundException(caseId)

    override suspend fun listCases(query: ListAmlCasesQuery): List<AmlCase> = amlCaseRepository.list(
        status = query.status,
        partyId = query.partyId,
        screeningType = query.screeningType,
        limit = query.limit.coerceIn(1, 200),
        offset = query.offset.coerceAtLeast(0),
    )

    override suspend fun updateDecision(command: UpdateAmlDecisionCommand): AmlCase {
        val amlCase = amlCaseRepository.findById(command.caseId)
            ?: throw AmlCaseNotFoundException(command.caseId)

        if (!amlCase.canTransitionTo(command.targetStatus)) {
            throw InvalidAmlCaseStateTransitionException(
                "Invalid AML case status transition: ${amlCase.status} -> ${command.targetStatus}",
            )
        }

        val updated = try {
            amlCase.transitionTo(
                targetStatus = command.targetStatus,
                decisionReason = command.decisionReason,
                assignedAnalyst = command.assignedAnalyst,
                decidedBy = command.decidedBy,
                now = Instant.now(clock),
            )
        } catch (ex: IllegalArgumentException) {
            throw InvalidAmlCaseStateTransitionException(ex.message ?: "Invalid AML case state transition")
        }

        // ADR-0050: the transition and its status-changed event commit atomically via the outbox.
        return amlCaseRepository.update(updated, statusChangedEvent(amlCase, updated))
    }

    private fun initialStatus(riskLevel: com.openbank.aml.domain.model.AmlRiskLevel): AmlCaseStatus = when (riskLevel) {
        com.openbank.aml.domain.model.AmlRiskLevel.CRITICAL,
        com.openbank.aml.domain.model.AmlRiskLevel.HIGH,
        -> AmlCaseStatus.UNDER_REVIEW
        com.openbank.aml.domain.model.AmlRiskLevel.MEDIUM,
        com.openbank.aml.domain.model.AmlRiskLevel.LOW,
        -> AmlCaseStatus.OPEN
    }

    private fun caseCreatedEvent(amlCase: AmlCase, now: Instant): OutboxMessage = OutboxMessage(
        eventId = UUID.randomUUID(),
        aggregateId = amlCase.id,
        eventType = EVENT_CASE_CREATED,
        payload = objectMapper.writeValueAsString(amlCase.toCreatedEvent(now)),
    )

    private fun statusChangedEvent(previous: AmlCase, current: AmlCase): OutboxMessage = OutboxMessage(
        eventId = UUID.randomUUID(),
        aggregateId = current.id,
        eventType = EVENT_STATUS_CHANGED,
        payload = objectMapper.writeValueAsString(
            AmlCaseStatusChangedEvent(
                caseId = current.id,
                partyId = current.partyId,
                previousStatus = previous.status,
                newStatus = current.status,
                decisionReason = current.decisionReason,
                assignedAnalyst = current.assignedAnalyst,
                decidedBy = current.decidedBy,
                occurredAt = current.updatedAt,
            ),
        ),
    )

    companion object {
        const val EVENT_CASE_CREATED = "aml.case.created.v1"
        const val EVENT_STATUS_CHANGED = "aml.case.status_changed.v1"
    }
}
