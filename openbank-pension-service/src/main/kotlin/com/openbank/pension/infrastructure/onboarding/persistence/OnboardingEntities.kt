// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.persistence

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/*
 * Slice S2 aggregates are stored as a JSON `payload` plus the columns something queries or
 * constrains. EVERY column is named explicitly: this service sets no physical naming strategy, so
 * an implicit multi-word name would resolve to a folded identifier no migration creates
 * (entity-column-names). `version` is compared in a conditional UPDATE, never by Hibernate.
 */

@Entity
@Table(name = "pension_onboarding_applications")
class OnboardingApplicationEntity : PanacheEntity() {
    @Column(name = "application_id", nullable = false, unique = true)
    lateinit var applicationId: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "contract_id")
    var contractId: UUID? = null

    @Column(name = "transfer_request_id")
    var transferRequestId: UUID? = null

    @Column(name = "payload", nullable = false)
    lateinit var payload: String

    @Column(name = "version", nullable = false)
    var version: Long = 0

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}

@Entity
@Table(name = "pension_suitability_assessments")
class SuitabilityAssessmentEntity : PanacheEntity() {
    @Column(name = "assessment_id", nullable = false, unique = true)
    lateinit var assessmentId: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "application_id", nullable = false)
    lateinit var applicationId: UUID

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "payload", nullable = false)
    lateinit var payload: String

    @Column(name = "assessed_at", nullable = false)
    lateinit var assessedAt: Instant
}

@Entity
@Table(name = "pension_transfer_requests")
class TransferRequestEntity : PanacheEntity() {
    @Column(name = "transfer_id", nullable = false, unique = true)
    lateinit var transferId: UUID

    @Column(name = "direction", nullable = false)
    lateinit var direction: String

    @Column(name = "contract_id", nullable = false)
    lateinit var contractId: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "payload", nullable = false)
    lateinit var payload: String

    @Column(name = "version", nullable = false)
    var version: Long = 0

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
