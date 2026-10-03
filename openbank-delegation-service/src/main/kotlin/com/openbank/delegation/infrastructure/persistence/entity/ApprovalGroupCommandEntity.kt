// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.entity

import com.openbank.delegation.domain.model.ApprovalGroup
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

/** Immutable command result keyed by its one-shot, dynamically linked SCA session. */
@Entity
@Table(name = "delegation_approval_group_commands")
class ApprovalGroupCommandEntity : PanacheEntityBase() {
    @Id
    @Column(name = "sca_session_id", nullable = false, updatable = false)
    lateinit var scaSessionId: UUID

    @Column(name = "group_id", nullable = false, updatable = false)
    lateinit var groupId: UUID

    @Column(name = "owner_party_id", nullable = false, updatable = false)
    lateinit var ownerPartyId: UUID

    @Column(name = "group_name", nullable = false, updatable = false, length = 120)
    lateinit var groupName: String

    @Column(name = "members", nullable = false, updatable = false, columnDefinition = "TEXT")
    lateinit var members: String

    @Column(name = "approval_threshold", nullable = false, updatable = false)
    var threshold: Int = 0

    @Column(name = "revision", nullable = false, updatable = false)
    var revision: Long = 0

    @Column(name = "active", nullable = false, updatable = false)
    var active: Boolean = true

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: OffsetDateTime

    @Column(name = "recorded_at", nullable = false, updatable = false)
    lateinit var recordedAt: OffsetDateTime

    fun toDomain() = ApprovalGroup(
        id = groupId,
        ownerPartyId = ownerPartyId,
        name = groupName,
        members = members.split(',').map(UUID::fromString).toSet(),
        threshold = threshold,
        revision = revision,
        active = active,
        lastScaSessionId = scaSessionId,
        createdAt = createdAt,
        updatedAt = recordedAt,
    )

    companion object {
        fun fromDomain(group: ApprovalGroup) = ApprovalGroupCommandEntity().apply {
            scaSessionId = group.lastScaSessionId
            groupId = group.id
            ownerPartyId = group.ownerPartyId
            groupName = group.name
            members = group.members.map(UUID::toString).sorted().joinToString(",")
            threshold = group.threshold
            revision = group.revision
            active = group.active
            createdAt = group.createdAt
            recordedAt = group.updatedAt
        }
    }
}
