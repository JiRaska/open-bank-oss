// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
         *
         * The `kyc` branch is an exhaustive `when` over [KycStage] (no `else`) so that a value
         * added to that enum without being classified here is a compile error, not a silent
         * fall-through — that fall-through is what previously sent an APPROVED kyc back to
         * [REGISTERED], moving the funnel board backwards at the moment it should be closest to
         * done (#8951). [REGISTERED] itself is not a `derive()` output: it is assigned directly
         * when a party record is first created, before any KYC case exists.
         */
        fun derive(party: PartyStage, kyc: KycStage?, scaEnrolled: Boolean): FunnelStage = when {
            party == PartyStage.SUSPENDED || party == PartyStage.CLOSED -> BLOCKED
            party == PartyStage.ACTIVE && scaEnrolled -> ACTIVE
            party == PartyStage.ACTIVE && !scaEnrolled -> SCA_PENDING
            else -> when (kyc) {
                null, KycStage.OPEN -> KYC_OPEN
                KycStage.DOCUMENTS_REQUIRED -> KYC_DOCUMENTS_REQUIRED
                KycStage.UNDER_REVIEW, KycStage.APPROVED -> KYC_UNDER_REVIEW
                KycStage.REJECTED, KycStage.EXPIRED -> BLOCKED
            }
        }
    }
}
