// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.domestic.application.port.out.DomesticPaymentProposalDraftRepository
import com.openbank.domestic.domain.model.DomesticPaymentProposalDraft
import com.openbank.domestic.infrastructure.persistence.entity.DomesticPaymentProposalDraftEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
// Hibernate/Mutiny wrap a unique-constraint violation in different runtime subclasses; inspect
// the complete cause chain and rethrow unless the exact maker/key constraint is named.
@Suppress("TooGenericExceptionCaught")
class DomesticPaymentProposalDraftRepositoryImpl(private val mapper: ObjectMapper) :
    DomesticPaymentProposalDraftRepository,
    PanacheRepository<DomesticPaymentProposalDraftEntity> {
    override suspend fun saveOrGetWinner(draft: DomesticPaymentProposalDraft): DomesticPaymentProposalDraft = try {
        Panache.withTransaction {
            persist(DomesticPaymentProposalDraftEntity.fromDomain(draft, mapper)).replaceWith(draft)
        }.awaitSuspending()
    } catch (exception: RuntimeException) {
        if (!exception.isMakerKeyViolation()) throw exception
        findByMakerAndKey(draft.makerPartyId, draft.idempotencyKey) ?: throw exception
    }

    override suspend fun findByMakerAndKey(makerPartyId: UUID, idempotencyKey: String): DomesticPaymentProposalDraft? =
        Panache.withSession {
            find("makerPartyId = ?1 and idempotencyKey = ?2", makerPartyId, idempotencyKey)
                .firstResult()
        }.awaitSuspending()?.toDomain(mapper)

    override suspend fun findById(id: UUID): DomesticPaymentProposalDraft? = Panache.withSession {
        find("proposalId", id).firstResult()
    }.awaitSuspending()?.toDomain(mapper)

    override suspend fun listByMaker(
        makerPartyId: UUID,
        before: DomesticPaymentProposalDraft?,
        limit: Int,
    ): List<DomesticPaymentProposalDraft> = Panache.withSession {
        val query = if (before == null) {
            find("makerPartyId = ?1 order by createdAt desc, proposalId desc", makerPartyId)
        } else {
            find(
                "makerPartyId = ?1 and (createdAt < ?2 or (createdAt = ?2 and proposalId < ?3)) " +
                    "order by createdAt desc, proposalId desc",
                makerPartyId,
                before.createdAt,
                before.id,
            )
        }
        query.range(0, limit - 1).list()
    }.awaitSuspending().map { it.toDomain(mapper) }

    private fun Throwable.isMakerKeyViolation(): Boolean =
        generateSequence(this) { cause -> cause.cause.takeIf { it !== cause } }.any { cause ->
            val constraint = (cause as? org.hibernate.exception.ConstraintViolationException)?.constraintName
            constraint == MAKER_KEY_CONSTRAINT || cause.message?.contains(MAKER_KEY_CONSTRAINT) == true
        }

    private companion object {
        const val MAKER_KEY_CONSTRAINT = "uq_domestic_payment_proposal_maker_key"
    }
}
