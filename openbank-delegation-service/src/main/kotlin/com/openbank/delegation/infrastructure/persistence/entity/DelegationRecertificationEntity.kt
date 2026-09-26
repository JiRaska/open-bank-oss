// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.entity

import com.openbank.delegation.domain.model.DelegationRecertificationAudience
import com.openbank.delegation.domain.model.DelegationRecertificationCycle
import com.openbank.delegation.domain.model.DelegationRecertificationStatus
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "delegation_recertification_cycles")
class DelegationRecertificationEntity : PanacheEntityBase() {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "delegation_id", nullable = false, updatable = false)
    lateinit var delegationId: UUID

    @Column(name = "grantor_party_id", nullable = false, updatable = false)
    lateinit var grantorPartyId: UUID

    @Column(name = "expected_lifecycle_revision", nullable = false, updatable = false)
    var expectedLifecycleRevision: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, updatable = false, length = 16)
    lateinit var audience: DelegationRecertificationAudience

    @Column(name = "sequence", nullable = false, updatable = false)
    var sequence: Int = 0

    @Column(name = "due_at", nullable = false, updatable = false)
    lateinit var dueAt: OffsetDateTime

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    lateinit var status: DelegationRecertificationStatus

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: OffsetDateTime

    @Column(name = "confirmed_at")
    var confirmedAt: OffsetDateTime? = null

    @Column(name = "confirmed_by")
    var confirmedBy: UUID? = null

    fun toDomain(): DelegationRecertificationCycle = DelegationRecertificationCycle(
        id = id,
        delegationId = delegationId,
        grantorPartyId = grantorPartyId,
        expectedLifecycleRevision = expectedLifecycleRevision,
        audience = audience,
        sequence = sequence,
        dueAt = dueAt,
        status = status,
        createdAt = createdAt,
        confirmedAt = confirmedAt,
        confirmedBy = confirmedBy,
    )

    companion object {
        fun fromDomain(cycle: DelegationRecertificationCycle) = DelegationRecertificationEntity().apply {
            id = cycle.id
            delegationId = cycle.delegationId
            grantorPartyId = cycle.grantorPartyId
            expectedLifecycleRevision = cycle.expectedLifecycleRevision
            audience = cycle.audience
            sequence = cycle.sequence
            dueAt = cycle.dueAt
            status = cycle.status
            createdAt = cycle.createdAt
            confirmedAt = cycle.confirmedAt
            confirmedBy = cycle.confirmedBy
        }
    }
}
