// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.entity

import com.openbank.account.domain.model.ApprovalGroupRevision
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.nio.charset.StandardCharsets
import java.util.UUID

@Entity
@Table(name = "approval_group_revisions")
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

    fun toDomain() = ApprovalGroupRevision(
        groupId,
        ownerPartyId,
        revision,
        groupName,
        members.split(',').map(UUID::fromString).toSet(),
        threshold,
        active,
    )

    companion object {
        fun from(value: ApprovalGroupRevision) = ApprovalGroupRevisionEntity().apply {
            id = UUID.nameUUIDFromBytes(
                "${value.groupId}:${value.revision}".toByteArray(StandardCharsets.UTF_8),
            )
            groupId = value.groupId
            ownerPartyId = value.ownerPartyId
            revision = value.revision
            groupName = value.name
            members = value.members.map(UUID::toString).sorted().joinToString(",")
            threshold = value.threshold
            active = value.active
        }
    }
}
