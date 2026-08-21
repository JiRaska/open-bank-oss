// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.application.usecase

import com.openbank.onboarding.application.port.`in`.OnboardingUseCase
import com.openbank.onboarding.application.port.out.OnboardingRepository
import com.openbank.onboarding.domain.model.FunnelStage
import com.openbank.onboarding.domain.model.KycStage
import com.openbank.onboarding.domain.model.OnboardingEvent
import com.openbank.onboarding.domain.model.OnboardingRecord
import com.openbank.onboarding.domain.model.PartyStage
import com.openbank.onboarding.domain.model.ProjectionResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID

class OnboardingRecordNotFoundException(partyId: UUID) :
    RuntimeException("Onboarding record not found for party $partyId")

/**
 * Projects upstream domain events into [OnboardingRecord] and answers cockpit queries.
 *
 * Event projection (apply*) is called from [OnboardingEventConsumer] on each inbound event.
 * Query methods are called from [OnboardingResource] for REST responses.
 */
@ApplicationScoped
class OnboardingProjectionService : OnboardingUseCase {

    @Inject lateinit var repo: OnboardingRepository

    // ── Query side ──────────────────────────────────────────────────────────────

    override suspend fun listRecords(page: Int, size: Int, stage: FunnelStage?): Map<String, Any> {
        val items = if (stage != null) repo.listByStage(stage, page, size) else repo.listAll(page, size)
        val total = if (stage != null) repo.countByStage(stage) else repo.countAll()
        return buildMap {
            put("items", items.map { it.toDto() })
            put("total", total)
            put("page", page)
            put("size", size)
            if (stage != null) put("stageFilter", stage.name)
        }
    }

    override suspend fun getRecord(partyId: UUID): OnboardingRecord =
        repo.findByPartyId(partyId) ?: throw OnboardingRecordNotFoundException(partyId)

    override suspend fun funnelCounts(): Map<String, Long> =
        FunnelStage.entries.associate { stage -> stage.name to repo.countByStage(stage) }

    // ── Event projection ─────────────────────────────────────────────────────

    /**
     * Applies one event to the read model and reports what it did.
     *
     * Returns [ProjectionResult.SKIPPED_UNKNOWN_PARTY] rather than throwing when the event names
     * a party with no row: the three source topics are independent consumer groups with no
     * ordering between them, so this is an expected race, not a fault. The caller must record it
     * as its own outcome and never as a success — see [ProjectionResult] for what folding the two
     * together cost (#6248).
     */
    suspend fun applyEvent(event: OnboardingEvent): ProjectionResult = when (event) {
        is OnboardingEvent.PartyCreated -> repo.applyPartyCreated(event)
        is OnboardingEvent.PartyStatusChanged -> repo.applyPartyStatusChanged(event)
        is OnboardingEvent.KycCaseOpened -> repo.applyKycCaseOpened(event)
        is OnboardingEvent.KycStatusChanged -> repo.applyKycStatusChanged(event)
        is OnboardingEvent.DeviceEnrolled -> repo.applyDeviceEnrolled(event)
    }

    // ── GDPR erasure ─────────────────────────────────────────────────────────

    /**
     * GDPR Art. 17 — Right to Erasure.
     * Delegates PII removal to the repository.  The row itself is kept so funnel metrics
     * remain accurate; only legalName and email are nulled out.
     */
    suspend fun eraseParty(partyId: UUID) {
        repo.eraseByPartyId(partyId)
    }

    // ── DTO mapping (application layer — no infra import) ───────────────────

    private fun OnboardingRecord.toDto(): Map<String, Any?> = mapOf(
        "partyId" to partyId.toString(),
        "legalName" to legalName,
        "email" to email,
        "partyStatus" to partyStatus.name,
        "kycCaseId" to kycCaseId?.toString(),
        "kycStatus" to kycStatus?.name,
        "scaEnrolled" to scaEnrolled,
        "deviceCount" to deviceCount,
        "funnelStage" to funnelStage.name,
        "blockedReason" to blockedReason,
        "createdAt" to createdAt.toString(),
        "updatedAt" to updatedAt.toString(),
    )
}

// ── Per-event projection ────────────────────────────────────────────────────
//
// File-private extensions on the repository rather than methods on the service: they need
// nothing else from it, and keeping them off the class holds it under detekt's TooManyFunctions
// threshold, which fires AT 11 and not above it.

/** The only branch that creates a row, and so the only one that cannot skip. */
private suspend fun OnboardingRepository.applyPartyCreated(event: OnboardingEvent.PartyCreated): ProjectionResult {
    val now = event.occurredAt
    upsert(
        OnboardingRecord(
            partyId = event.partyId,
            legalName = event.legalName,
            email = event.email,
            partyStatus = PartyStage.PENDING_KYC,
            kycCaseId = null,
            kycStatus = null,
            scaEnrolled = false,
            deviceCount = 0,
            funnelStage = FunnelStage.REGISTERED,
            blockedReason = null,
            createdAt = now,
            updatedAt = now,
        ),
    )
    return ProjectionResult.APPLIED
}

private suspend fun OnboardingRepository.applyPartyStatusChanged(
    event: OnboardingEvent.PartyStatusChanged,
): ProjectionResult {
    val existing = findByPartyId(event.partyId) ?: return ProjectionResult.SKIPPED_UNKNOWN_PARTY
    upsert(
        existing.copy(
            partyStatus = event.newStatus,
            funnelStage = FunnelStage.derive(event.newStatus, existing.kycStatus, existing.scaEnrolled),
            blockedReason = if (event.newStatus == PartyStage.SUSPENDED ||
                event.newStatus == PartyStage.CLOSED
            ) {
                "Party ${event.newStatus.name.lowercase()}"
            } else {
                null
            },
            updatedAt = event.occurredAt,
        ),
    )
    return ProjectionResult.APPLIED
}

private suspend fun OnboardingRepository.applyKycCaseOpened(event: OnboardingEvent.KycCaseOpened): ProjectionResult {
    val existing = findByPartyId(event.partyId) ?: return ProjectionResult.SKIPPED_UNKNOWN_PARTY
    upsert(
        existing.copy(
            kycCaseId = event.kycCaseId,
            kycStatus = KycStage.OPEN,
            funnelStage = FunnelStage.derive(existing.partyStatus, KycStage.OPEN, existing.scaEnrolled),
            updatedAt = event.occurredAt,
        ),
    )
    return ProjectionResult.APPLIED
}

private suspend fun OnboardingRepository.applyKycStatusChanged(
    event: OnboardingEvent.KycStatusChanged,
): ProjectionResult {
    val existing = findByPartyId(event.partyId) ?: return ProjectionResult.SKIPPED_UNKNOWN_PARTY
    upsert(
        existing.copy(
            kycStatus = event.newStatus,
            funnelStage = FunnelStage.derive(existing.partyStatus, event.newStatus, existing.scaEnrolled),
            blockedReason = when (event.newStatus) {
                KycStage.REJECTED -> "KYC rejected"
                KycStage.EXPIRED -> "KYC expired"
                else -> null
            },
            updatedAt = event.occurredAt,
        ),
    )
    return ProjectionResult.APPLIED
}

private suspend fun OnboardingRepository.applyDeviceEnrolled(event: OnboardingEvent.DeviceEnrolled): ProjectionResult {
    val existing = findByPartyId(event.partyId) ?: return ProjectionResult.SKIPPED_UNKNOWN_PARTY
    upsert(
        existing.copy(
            scaEnrolled = true,
            deviceCount = existing.deviceCount + 1,
            funnelStage = FunnelStage.derive(existing.partyStatus, existing.kycStatus, true),
            updatedAt = event.occurredAt,
        ),
    )
    return ProjectionResult.APPLIED
}
