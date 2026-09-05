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
import java.time.Instant
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
     * No branch drops an event any more. The three source topics are independent consumer
     * groups with no ordering between them, so an SCA or KYC event legitimately arrives before
     * `PARTY_CREATED` has created the row. That used to `return` without writing anything, and
     * nothing ever replayed it — so the enrolment was lost permanently. The row is now seeded
     * from the event instead, and [ProjectionResult.APPLIED_TO_SEEDED_RECORD] says so, because
     * an out-of-order arrival is still worth seeing (#6248).
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

/**
 * The row this event applies to, creating a placeholder if the party has none yet (#6248).
 *
 * Returns the record and whether it had to be seeded. A seeded row carries only what is known
 * without `PARTY_CREATED` — the party id and the event time. No PII, because none is available,
 * and none is invented.
 */
private suspend fun OnboardingRepository.recordFor(
    partyId: UUID,
    occurredAt: Instant,
): Pair<OnboardingRecord, Boolean> {
    val existing = findByPartyId(partyId)
    if (existing != null) return existing to false
    val seeded = OnboardingRecord(
        partyId = partyId,
        legalName = null,
        email = null,
        partyStatus = PartyStage.PENDING_KYC,
        kycCaseId = null,
        kycStatus = null,
        scaEnrolled = false,
        deviceCount = 0,
        funnelStage = FunnelStage.REGISTERED,
        blockedReason = null,
        createdAt = occurredAt,
        updatedAt = occurredAt,
    )
    return seeded to true
}

private fun result(seeded: Boolean) =
    if (seeded) ProjectionResult.APPLIED_TO_SEEDED_RECORD else ProjectionResult.APPLIED

/**
 * `PARTY_CREATED` fills in identity; it must never reset progress.
 *
 * It used to write a fixed `scaEnrolled = false, deviceCount = 0, kycStatus = null,
 * funnelStage = REGISTERED` over whatever the row held. That is destructive on exactly the two
 * occasions it now matters: when the row was seeded by an out-of-order SCA/KYC event, and when
 * `PARTY_CREATED` is replayed. Preserve the projected state and re-derive the stage from it.
 */
private suspend fun OnboardingRepository.applyPartyCreated(event: OnboardingEvent.PartyCreated): ProjectionResult {
    val existing = findByPartyId(event.partyId)
    val base = existing ?: OnboardingRecord(
        partyId = event.partyId,
        legalName = null,
        email = null,
        partyStatus = PartyStage.PENDING_KYC,
        kycCaseId = null,
        kycStatus = null,
        scaEnrolled = false,
        deviceCount = 0,
        funnelStage = FunnelStage.REGISTERED,
        blockedReason = null,
        createdAt = event.occurredAt,
        updatedAt = event.occurredAt,
    )
    // Re-derive ONLY where there is progress to preserve. A brand-new party has none, and
    // `derive(PENDING_KYC, null, false)` answers KYC_OPEN — so deriving unconditionally would
    // push every new registration a stage down the funnel, and push it there again on replay.
    val hasProgress = base.scaEnrolled || base.kycStatus != null || base.partyStatus != PartyStage.PENDING_KYC
    upsert(
        base.copy(
            legalName = event.legalName,
            email = event.email,
            funnelStage = if (hasProgress) {
                FunnelStage.derive(base.partyStatus, base.kycStatus, base.scaEnrolled)
            } else {
                base.funnelStage
            },
            createdAt = event.occurredAt,
            updatedAt = maxOf(base.updatedAt, event.occurredAt),
        ),
    )
    return ProjectionResult.APPLIED
}

private suspend fun OnboardingRepository.applyPartyStatusChanged(
    event: OnboardingEvent.PartyStatusChanged,
): ProjectionResult {
    val (existing, seeded) = recordFor(event.partyId, event.occurredAt)
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
    return result(seeded)
}

private suspend fun OnboardingRepository.applyKycCaseOpened(event: OnboardingEvent.KycCaseOpened): ProjectionResult {
    val (existing, seeded) = recordFor(event.partyId, event.occurredAt)
    upsert(
        existing.copy(
            kycCaseId = event.kycCaseId,
            kycStatus = KycStage.OPEN,
            funnelStage = FunnelStage.derive(existing.partyStatus, KycStage.OPEN, existing.scaEnrolled),
            updatedAt = event.occurredAt,
        ),
    )
    return result(seeded)
}

private suspend fun OnboardingRepository.applyKycStatusChanged(
    event: OnboardingEvent.KycStatusChanged,
): ProjectionResult {
    val (existing, seeded) = recordFor(event.partyId, event.occurredAt)
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
    return result(seeded)
}

/**
 * `deviceCount` is DERIVED from the credential ledger, never incremented (#6248).
 *
 * `deviceCount + 1` cannot be replayed: re-consuming an event the read model has already seen
 * inflates the count instead of converging on it. That matters because replay is the only way
 * back for the enrolments lost to #4353 — the Kafka topic's retention dropped the originals long
 * ago, so any recovery necessarily re-publishes from `sca_enrolled_devices`, and a backfill you
 * cannot run twice is a backfill nobody dares run once.
 */
private suspend fun OnboardingRepository.applyDeviceEnrolled(event: OnboardingEvent.DeviceEnrolled): ProjectionResult {
    val (existing, seeded) = recordFor(event.partyId, event.occurredAt)
    val total = recordDeviceEnrolment(event.partyId, event.credentialId, event.occurredAt)
    upsert(
        existing.copy(
            scaEnrolled = total > 0,
            deviceCount = total,
            funnelStage = FunnelStage.derive(existing.partyStatus, existing.kycStatus, total > 0),
            updatedAt = event.occurredAt,
        ),
    )
    return result(seeded)
}
