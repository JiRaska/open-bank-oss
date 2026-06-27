// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.persistence.repository

import com.openbank.onboarding.application.port.out.OnboardingRepository
import com.openbank.onboarding.domain.model.*
import com.openbank.onboarding.infrastructure.persistence.entity.OnboardingEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class OnboardingRepositoryImpl : OnboardingRepository, PanacheRepository<OnboardingEntity> {

    override suspend fun upsert(record: OnboardingRecord) {
        Panache.withTransaction {
            // flatMap (not map): persist() returns a Uni that MUST be chained, or the
            // INSERT is never subscribed and the transaction commits without writing the
            // row (the bug that left the onboarding funnel empty). The update branch
            // mutates the managed entity and relies on dirty-checking flush on commit.
            find("partyId", record.partyId).firstResult().flatMap { existing ->
                if (existing != null) {
                    existing.legalName = record.legalName
                    existing.email = record.email
                    existing.partyStatus = record.partyStatus.name
                    existing.kycCaseId = record.kycCaseId
                    existing.kycStatus = record.kycStatus?.name
                    existing.scaEnrolled = record.scaEnrolled
                    existing.deviceCount = record.deviceCount
                    existing.funnelStage = record.funnelStage.name
                    existing.blockedReason = record.blockedReason
                    existing.updatedAt = record.updatedAt
                    Uni.createFrom().item(existing)
                } else {
                    persist(record.toEntity())
                }
            }
        }.awaitSuspending()
    }

    override suspend fun findByPartyId(partyId: UUID): OnboardingRecord? =
        Panache.withSession { find("partyId", partyId).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun listByStage(stage: FunnelStage, page: Int, size: Int): List<OnboardingRecord> =
        Panache.withSession { find("funnelStage", stage.name).page(page, size).list() }
            .awaitSuspending().map { it.toDomain() }

    override suspend fun countByStage(stage: FunnelStage): Long =
        Panache.withSession { count("funnelStage", stage.name) }.awaitSuspending()

    override suspend fun listAll(page: Int, size: Int): List<OnboardingRecord> =
        Panache.withSession { findAll().page(page, size).list() }.awaitSuspending().map { it.toDomain() }

    override suspend fun countAll(): Long =
        Panache.withSession { count() }.awaitSuspending()

    override suspend fun listStuckBefore(stages: List<FunnelStage>, cutoff: java.time.Instant): List<OnboardingRecord> {
        val stageNames = stages.map { it.name }
        return Panache.withSession {
            find("funnelStage IN ?1 AND updatedAt < ?2", stageNames, cutoff).list()
        }.awaitSuspending().map { it.toDomain() }
    }

    private fun OnboardingRecord.toEntity() = OnboardingEntity().also {
        it.partyId = partyId
        it.legalName = legalName
        it.email = email
        it.partyStatus = partyStatus.name
        it.kycCaseId = kycCaseId
        it.kycStatus = kycStatus?.name
        it.scaEnrolled = scaEnrolled
        it.deviceCount = deviceCount
        it.funnelStage = funnelStage.name
        it.blockedReason = blockedReason
        it.createdAt = createdAt
        it.updatedAt = updatedAt
    }

    private fun OnboardingEntity.toDomain() = OnboardingRecord(
        partyId = partyId,
        legalName = legalName,
        email = email,
        partyStatus = PartyStage.valueOf(partyStatus),
        kycCaseId = kycCaseId,
        kycStatus = kycStatus?.let { runCatching { KycStage.valueOf(it) }.getOrNull() },
        scaEnrolled = scaEnrolled,
        deviceCount = deviceCount,
        funnelStage = FunnelStage.valueOf(funnelStage),
        blockedReason = blockedReason,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
