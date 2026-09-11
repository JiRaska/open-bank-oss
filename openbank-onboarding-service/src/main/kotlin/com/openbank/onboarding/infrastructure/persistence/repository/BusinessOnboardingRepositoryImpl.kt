// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.persistence.repository

import com.openbank.onboarding.application.port.out.BusinessOnboardingRepository
import com.openbank.onboarding.domain.model.BusinessCaseStage
import com.openbank.onboarding.domain.model.BusinessFunnelStage
import com.openbank.onboarding.domain.model.BusinessOnboardingRecord
import com.openbank.onboarding.infrastructure.persistence.entity.BusinessOnboardingEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class BusinessOnboardingRepositoryImpl :
    BusinessOnboardingRepository,
    PanacheRepository<BusinessOnboardingEntity> {

    override suspend fun upsert(record: BusinessOnboardingRecord, eventAt: Instant) {
        Panache.withTransaction {
            // flatMap, not map: persist() returns a Uni that must be chained or the INSERT is
            // never subscribed and the transaction commits having written nothing — the defect
            // that left the personal funnel empty (OnboardingRepositoryImpl carries the same note).
            find("caseId", record.caseId).firstResult().flatMap { existing ->
                if (existing == null) {
                    persist(newEntity(record, eventAt))
                } else if (eventAt.isBefore(existing.lastEventAt)) {
                    // Out-of-order replay. Dropping it is the whole reason lastEventAt is stored:
                    // a redelivered SIGNER_INVITED must not walk a live customer back to
                    // AWAITING_SIGNATURES. The row is left exactly as it is.
                    Uni.createFrom().voidItem()
                } else {
                    apply(existing, record, eventAt)
                    // Managed entity: dirty-checking flushes on commit, no explicit call needed.
                    Uni.createFrom().voidItem()
                }
            }
        }.awaitSuspending()
    }

    override suspend fun findByCaseId(caseId: UUID): BusinessOnboardingRecord? =
        Panache.withSession { find("caseId", caseId).firstResult() }
            .awaitSuspending()
            ?.let(::toRecord)

    override suspend fun listByStage(stage: BusinessFunnelStage, page: Int, size: Int): List<BusinessOnboardingRecord> =
        Panache.withSession {
            find("stage", Sort.by("updatedAt", Sort.Direction.Descending), stage.name)
                .page(Page.of(page, size))
                .list()
        }.awaitSuspending().map(::toRecord)

    override suspend fun listAll(page: Int, size: Int): List<BusinessOnboardingRecord> = Panache.withSession {
        findAll(Sort.by("updatedAt", Sort.Direction.Descending)).page(Page.of(page, size)).list()
    }.awaitSuspending().map(::toRecord)

    override suspend fun countAll(): Long = Panache.withSession { count() }.awaitSuspending()

    override suspend fun countByStage(stage: BusinessFunnelStage): Long =
        Panache.withSession { count("stage", stage.name) }.awaitSuspending()

    override suspend fun anonymizeParty(partyId: UUID) {
        Panache.withTransaction {
            // The rows are NOT deleted. A case is a record of a business relationship the bank
            // must keep (AML retention); what Art. 17 reaches is the natural person's link to it.
            // initiator_party_id is NOT NULL by design — the case has to have had an initiator —
            // so it is overwritten with the all-zero UUID rather than nulled, which reads as
            // "a person who has been erased" instead of as missing data.
            update(
                "initiatorPartyId = ?1 where initiatorPartyId = ?2",
                ERASED_PARTY,
                partyId,
            )
        }.awaitSuspending()
    }

    private fun newEntity(record: BusinessOnboardingRecord, eventAt: Instant) = BusinessOnboardingEntity().also {
        it.caseId = record.caseId
        it.createdAt = record.createdAt
        apply(it, record, eventAt)
    }

    private fun apply(entity: BusinessOnboardingEntity, record: BusinessOnboardingRecord, eventAt: Instant) {
        entity.identifierScheme = record.identifierScheme
        entity.identifier = record.identifier
        entity.country = record.country
        entity.legalName = record.legalName
        entity.legalFormClass = record.legalFormClass
        entity.initiatorPartyId = record.initiatorPartyId
        entity.entityPartyId = record.entityPartyId
        entity.caseStatus = record.caseStatus.name
        entity.stage = record.stage.name
        entity.requiredSignatures = record.requiredSignatures
        entity.signedCount = record.signedCount
        entity.reviewReason = record.reviewReason
        entity.lastEventAt = eventAt
        entity.updatedAt = record.updatedAt
    }

    private fun toRecord(entity: BusinessOnboardingEntity) = BusinessOnboardingRecord(
        caseId = entity.caseId,
        identifierScheme = entity.identifierScheme,
        identifier = entity.identifier,
        country = entity.country,
        legalName = entity.legalName,
        legalFormClass = entity.legalFormClass,
        initiatorPartyId = entity.initiatorPartyId,
        entityPartyId = entity.entityPartyId,
        // A status this build does not know is projected as MANUAL_REVIEW rather than dropped:
        // an unreadable row must reach a human, not disappear from every count.
        caseStatus = BusinessCaseStage.from(entity.caseStatus) ?: BusinessCaseStage.MANUAL_REVIEW,
        stage = BusinessFunnelStage.entries.firstOrNull { it.name == entity.stage }
            ?: BusinessFunnelStage.NEEDS_REVIEW,
        requiredSignatures = entity.requiredSignatures,
        signedCount = entity.signedCount,
        reviewReason = entity.reviewReason,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    companion object {
        /** Tombstone for an erased natural person. Never a real party id. */
        private val ERASED_PARTY: UUID = UUID(0L, 0L)
    }
}
