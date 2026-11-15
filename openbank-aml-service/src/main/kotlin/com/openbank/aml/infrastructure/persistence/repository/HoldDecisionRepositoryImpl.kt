// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.persistence.repository

import com.openbank.aml.application.port.out.CaseChangedConcurrentlyException
import com.openbank.aml.application.port.out.HoldDecisionRepository
import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.infrastructure.persistence.entity.AmlCaseEntity
import com.openbank.aml.infrastructure.persistence.mapper.readHoldProposal
import com.openbank.aml.infrastructure.persistence.mapper.writeHoldProposal
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType

/** ADR-0374 point 3: a held payment's proposal or confirmation, written under a lock on the case. */
@ApplicationScoped
class HoldDecisionRepositoryImpl(private val outboxRepository: AmlOutboxRepositoryImpl) :
    HoldDecisionRepository,
    PanacheRepository<AmlCaseEntity> {

    override suspend fun writeHoldDecision(readAs: AmlCase, decided: AmlCase, event: OutboxMessage?): AmlCase =
        Panache.withTransaction {
            find("caseId", readAs.id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
                .invoke { entity ->
                    if (entity == null || !entity.isAsRead(readAs)) throw CaseChangedConcurrentlyException(readAs.id)
                    entity.status = decided.status.name
                    entity.decisionReason = decided.decisionReason
                    entity.assignedAnalyst = decided.assignedAnalyst
                    entity.decidedBy = decided.decidedBy
                    entity.decidedAt = decided.decidedAt
                    entity.updatedAt = decided.updatedAt
                    entity.writeHoldProposal(decided.holdProposal)
                }
                .chain { _ ->
                    if (event ==
                        null
                    ) {
                        Uni.createFrom().voidItem()
                    } else {
                        outboxRepository.persistInTransaction(event)
                    }
                }
                .replaceWith(decided)
        }.awaitSuspending()

    /** Nobody changed the case since [readAs] was read: same status, party and pending proposal. */
    private fun AmlCaseEntity.isAsRead(readAs: AmlCase): Boolean =
        status == readAs.status.name && partyId == readAs.partyId && readHoldProposal() == readAs.holdProposal
}
