// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.onboarding.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "onboarding_records",
    indexes = [
        Index(name = "idx_onboarding_party_id", columnList = "party_id", unique = true),
        Index(name = "idx_onboarding_funnel_stage", columnList = "funnel_stage"),
    ]
)
class OnboardingEntity : PanacheEntity() {

    @Column(name = "party_id", nullable = false, unique = true)
    lateinit var partyId: UUID

    @Column(name = "legal_name")
    var legalName: String? = null

    @Column(name = "email")
    var email: String? = null

    @Column(name = "party_status", nullable = false)
    lateinit var partyStatus: String

    @Column(name = "kyc_case_id")
    var kycCaseId: UUID? = null

    @Column(name = "kyc_status")
    var kycStatus: String? = null

    @Column(name = "sca_enrolled", nullable = false)
    var scaEnrolled: Boolean = false

    @Column(name = "device_count", nullable = false)
    var deviceCount: Int = 0

    @Column(name = "funnel_stage", nullable = false)
    lateinit var funnelStage: String

    @Column(name = "blocked_reason")
    var blockedReason: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
