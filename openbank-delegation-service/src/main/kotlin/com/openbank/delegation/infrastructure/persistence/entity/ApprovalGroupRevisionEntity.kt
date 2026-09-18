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
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID

/** Append-only source of truth used to rebuild downstream approval snapshots after event retention expires. */
@Entity
@Table(name = "delegation_approval_group_revisions")
class ApprovalGroupRevisionEntity : PanacheEntityBase() {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "group_id", nullable = false, updatable = false)
    lateinit var groupId: UUID

    @Column(name = "owner_party_id", nullable = false, updatable = false)
    lateinit var ownerPartyId: UUID

    @Column(name = "revision", nullable = false, updatable = false)
    var revision: Long = 0

    @Column(name = "name", nullable = false, updatable = false, length = 120)
    lateinit var groupName: String

    @Column(name = "members", nullable = false, updatable = false, columnDefinition = "TEXT")
    lateinit var members: String

    @Column(name = "approval_threshold", nullable = false, updatable = false)
    var threshold: Int = 0

    @Column(name = "active", nullable = false, updatable = false)
    var active: Boolean = true

    @Column(name = "recorded_at", nullable = false, updatable = false)
    lateinit var recordedAt: OffsetDateTime

    companion object {
        fun fromDomain(group: ApprovalGroup) = ApprovalGroupRevisionEntity().apply {
            id = UUID.nameUUIDFromBytes(
                "${group.id}:${group.revision}".toByteArray(StandardCharsets.UTF_8),
            )
            groupId = group.id
            ownerPartyId = group.ownerPartyId
            revision = group.revision
            groupName = group.name
            members = group.members.map(UUID::toString).sorted().joinToString(",")
            threshold = group.threshold
            active = group.active
            recordedAt = group.updatedAt
        }
    }
}
