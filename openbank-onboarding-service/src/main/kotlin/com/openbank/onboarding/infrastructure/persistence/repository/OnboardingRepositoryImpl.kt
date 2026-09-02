// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.persistence.repository

import com.openbank.onboarding.application.port.out.OnboardingRepository
import com.openbank.onboarding.domain.model.FunnelStage
import com.openbank.onboarding.domain.model.KycStage
import com.openbank.onboarding.domain.model.OnboardingRecord
import com.openbank.onboarding.domain.model.PartyStage
import com.openbank.onboarding.infrastructure.persistence.entity.DeviceEnrolmentEntity
import com.openbank.onboarding.infrastructure.persistence.entity.OnboardingEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class OnboardingRepositoryImpl :
    OnboardingRepository,
    PanacheRepository<OnboardingEntity> {

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

    /**
     * Check-then-insert inside one transaction, so a duplicate is absorbed without ever letting a
     * constraint violation reach the reactive session (a failed statement poisons it for the rest
     * of the transaction, and this method must be safe to call on every replayed event). The
     * UNIQUE index remains the real guarantee under concurrency; this is the common path.
     */
    override suspend fun recordDeviceEnrolment(partyId: UUID, credentialId: String, enrolledAt: Instant): Int =
        Panache.withTransaction {
            DeviceEnrolmentEntity
                .find("partyId = ?1 and credentialId = ?2", partyId, credentialId)
                .firstResult()
                .flatMap { existing ->
                    if (existing != null) {
                        Uni.createFrom().item(Unit)
                    } else {
                        val e = DeviceEnrolmentEntity()
                        e.partyId = partyId
                        e.credentialId = credentialId
                        e.enrolledAt = enrolledAt
                        // replaceWith(Unit), not the bare persist(): Kotlin infers the branch
                        // type from the `if`, and `Uni<DeviceEnrolmentEntity>` vs `Uni<Void>`
                        // unifies to a cast that only blows up at runtime.
                        e.persist<DeviceEnrolmentEntity>().replaceWith(Unit)
                    }
                }
                .flatMap { DeviceEnrolmentEntity.count("partyId", partyId) }
        }.awaitSuspending().toInt()

    override suspend fun findByPartyId(partyId: UUID): OnboardingRecord? =
        Panache.withSession { find("partyId", partyId).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun listByStage(stage: FunnelStage, page: Int, size: Int): List<OnboardingRecord> =
        Panache.withSession { find("funnelStage", stage.name).page(page, size).list() }
            .awaitSuspending().map { it.toDomain() }

    override suspend fun countByStage(stage: FunnelStage): Long =
        Panache.withSession { count("funnelStage", stage.name) }.awaitSuspending()

    override suspend fun listAll(page: Int, size: Int): List<OnboardingRecord> =
        Panache.withSession { findAll().page(page, size).list() }.awaitSuspending().map { it.toDomain() }

    override suspend fun countAll(): Long = Panache.withSession { count() }.awaitSuspending()

    override suspend fun listStuckBefore(stages: List<FunnelStage>, cutoff: java.time.Instant): List<OnboardingRecord> {
        val stageNames = stages.map { it.name }
        return Panache.withSession {
            find("funnelStage IN ?1 AND updatedAt < ?2", stageNames, cutoff).list()
        }.awaitSuspending().map { it.toDomain() }
    }

    override suspend fun eraseByPartyId(partyId: UUID) {
        Panache.withTransaction {
            find("partyId", partyId).firstResult().flatMap { existing ->
                if (existing != null) {
                    existing.legalName = null
                    existing.email = null
                    Uni.createFrom().item(existing)
                } else {
                    Uni.createFrom().nullItem()
                }
            }
        }.awaitSuspending()
    }
}

// Entity <-> domain mapping as file-private top-level extensions rather than methods: they
// need nothing from the class, and keeping them off it holds it under detekt's
// TooManyFunctions threshold, which fires AT 11 and not above it.
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
