// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheCompanion
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * One enrolled SCA credential, as seen by the onboarding read model (#6248).
 *
 * Exists so `onboarding_records.device_count` can be *derived* rather than incremented. The
 * previous `deviceCount + 1` made DEVICE_ENROLLED non-idempotent, which in turn made replay —
 * the only recovery path for events Kafka retention has already dropped — unusable.
 */
@Entity
@Table(
    name = "onboarding_device_enrolments",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_onboarding_device_enrolment", columnNames = ["party_id", "credential_id"]),
    ],
    indexes = [Index(name = "idx_onboarding_device_enrolment_party", columnList = "party_id")],
)
class DeviceEnrolmentEntity : PanacheEntity() {

    companion object : PanacheCompanion<DeviceEnrolmentEntity>

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "credential_id", nullable = false)
    lateinit var credentialId: String

    @Column(name = "enrolled_at", nullable = false)
    lateinit var enrolledAt: Instant
}
