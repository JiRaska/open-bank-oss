// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "business_onboarding_records",
    indexes = [
        Index(name = "idx_business_onboarding_stage", columnList = "stage"),
        Index(name = "idx_business_onboarding_initiator", columnList = "initiator_party_id"),
    ],
)
class BusinessOnboardingEntity : PanacheEntity() {

    @Column(name = "case_id", nullable = false, unique = true)
    lateinit var caseId: UUID

    @Column(name = "identifier_scheme", nullable = false)
    lateinit var identifierScheme: String

    @Column(name = "identifier", nullable = false)
    lateinit var identifier: String

    @Column(name = "country")
    var country: String? = null

    @Column(name = "legal_name")
    var legalName: String? = null

    @Column(name = "legal_form_class")
    var legalFormClass: String? = null

    @Column(name = "initiator_party_id", nullable = false)
    lateinit var initiatorPartyId: UUID

    @Column(name = "entity_party_id")
    var entityPartyId: UUID? = null

    @Column(name = "case_status", nullable = false)
    lateinit var caseStatus: String

    @Column(name = "stage", nullable = false)
    lateinit var stage: String

    @Column(name = "required_signatures")
    var requiredSignatures: Int? = null

    @Column(name = "signed_count", nullable = false)
    var signedCount: Int = 0

    @Column(name = "review_reason", columnDefinition = "TEXT")
    var reviewReason: String? = null

    /** The projection's ordering guard — see [com.openbank.onboarding.application.port.out.BusinessOnboardingRepository.upsert]. */
    @Column(name = "last_event_at", nullable = false)
    lateinit var lastEventAt: Instant

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
