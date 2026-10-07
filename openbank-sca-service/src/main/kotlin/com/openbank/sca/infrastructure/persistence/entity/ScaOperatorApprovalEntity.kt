// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.persistence.entity

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.MakerActorKind
import com.openbank.libs.approval.PendingApproval
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
@Table(name = "sca_operator_approvals")
class ScaOperatorApprovalEntity : PanacheEntityBase() {
    @Id
    lateinit var id: UUID

    @Column(name = "action", nullable = false)
    lateinit var action: String

    @Column(name = "resource_id")
    var resourceId: String? = null

    @Column(name = "maker_id", nullable = false)
    lateinit var makerId: String

    @Enumerated(EnumType.STRING)
    @Column(name = "maker_actor_kind", nullable = false)
    var makerActorKind: MakerActorKind = MakerActorKind.UNKNOWN

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    lateinit var status: ApprovalStatus

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: OffsetDateTime

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: OffsetDateTime

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "decided_at")
    var decidedAt: OffsetDateTime? = null

    @Column(name = "claimed_at")
    var claimedAt: OffsetDateTime? = null

    /** Hex SHA-256 of the paused request (#11675); `null` for an unbound approval. */
    @Column(name = "request_fingerprint")
    var requestFingerprint: String? = null

    @Column(name = "summary")
    var summary: String? = null

    fun toDomain() = PendingApproval(
        id = id.toString(),
        action = action,
        resourceId = resourceId,
        makerId = makerId,
        makerActorKind = makerActorKind,
        status = status,
        createdAt = createdAt,
        decidedBy = decidedBy,
        decidedAt = decidedAt,
        requestFingerprint = requestFingerprint,
        summary = summary,
    )
}
