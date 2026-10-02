// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.entity

import com.openbank.delegation.domain.model.ApprovalGroup
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "delegation_approval_groups")
class ApprovalGroupEntity : PanacheEntityBase() {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var groupId: UUID

    @Column(name = "owner_party_id", nullable = false, updatable = false)
    lateinit var ownerPartyId: UUID

    @Column(name = "name", nullable = false)
    lateinit var groupName: String

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "delegation_approval_group_members", joinColumns = [JoinColumn(name = "group_id")])
    @Column(name = "member_party_id", nullable = false)
    var members: MutableSet<UUID> = linkedSetOf()

    @Column(name = "approval_threshold", nullable = false)
    var threshold: Int = 1

    @Column(name = "revision", nullable = false)
    var revision: Long = 1

    @Column(name = "active", nullable = false)
    var active: Boolean = true

    @Column(name = "last_sca_session_id", nullable = false)
    lateinit var lastScaSessionId: UUID

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: OffsetDateTime

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: OffsetDateTime

    fun toDomain() = ApprovalGroup(
        id = groupId,
        ownerPartyId = ownerPartyId,
        name = groupName,
        members = members.toSet(),
        threshold = threshold,
        revision = revision,
        active = active,
        lastScaSessionId = lastScaSessionId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromDomain(group: ApprovalGroup) = ApprovalGroupEntity().apply {
            groupId = group.id
            ownerPartyId = group.ownerPartyId
            groupName = group.name
            members = group.members.toMutableSet()
            threshold = group.threshold
            revision = group.revision
            active = group.active
            lastScaSessionId = group.lastScaSessionId
            createdAt = group.createdAt
            updatedAt = group.updatedAt
        }
    }
}
