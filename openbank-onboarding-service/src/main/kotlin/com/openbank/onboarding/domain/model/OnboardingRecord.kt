// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.onboarding.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Read-model projection of a single party's onboarding journey (ADR-0068).
 *
 * This record is assembled from events emitted by party-service, kyc-service and
 * sca-service. It is never the source of truth — it is a denormalised view for
 * operator dashboards and the approval queue. All mutations arrive via [OnboardingEvent].
 */
data class OnboardingRecord(
    val partyId: UUID,
    val legalName: String?,
    val email: String?,
    val partyStatus: PartyStage,
    val kycCaseId: UUID?,
    val kycStatus: KycStage?,
    val scaEnrolled: Boolean,
    val deviceCount: Int,
    val funnelStage: FunnelStage,
    val blockedReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Operator-visible party lifecycle stage (maps from PartyStatus values emitted by party-service).
 */
enum class PartyStage { PENDING_KYC, ACTIVE, SUSPENDED, CLOSED }

/**
 * Operator-visible KYC stage (maps from KycCaseStatus values emitted by kyc-service).
 */
enum class KycStage { OPEN, DOCUMENTS_REQUIRED, UNDER_REVIEW, APPROVED, REJECTED, EXPIRED }

/**
 * Derived funnel stage for the cockpit board columns (ADR-0068 §4.2).
 *
 * Ordering: REGISTERED → KYC_OPEN → KYC_UNDER_REVIEW → SCA_PENDING → ACTIVE | BLOCKED
 */
enum class FunnelStage {
    REGISTERED,
    KYC_OPEN,
    KYC_DOCUMENTS_REQUIRED,
    KYC_UNDER_REVIEW,
    SCA_PENDING,
    ACTIVE,
    BLOCKED,
    ;

    companion object {
        /**
         * Derive the funnel stage from the three upstream status dimensions.
         * The logic must stay in the domain layer — no framework imports allowed.
         */
        fun derive(party: PartyStage, kyc: KycStage?, scaEnrolled: Boolean): FunnelStage = when {
            party == PartyStage.SUSPENDED || party == PartyStage.CLOSED -> BLOCKED
            party == PartyStage.ACTIVE && scaEnrolled -> ACTIVE
            party == PartyStage.ACTIVE && !scaEnrolled -> SCA_PENDING
            kyc == KycStage.UNDER_REVIEW -> KYC_UNDER_REVIEW
            kyc == KycStage.DOCUMENTS_REQUIRED -> KYC_DOCUMENTS_REQUIRED
            kyc == KycStage.OPEN || kyc == null -> KYC_OPEN
            kyc == KycStage.REJECTED || kyc == KycStage.EXPIRED -> BLOCKED
            else -> REGISTERED
        }
    }
}
