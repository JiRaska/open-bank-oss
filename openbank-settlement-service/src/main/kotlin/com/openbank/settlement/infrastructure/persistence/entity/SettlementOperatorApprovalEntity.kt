// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.settlement.infrastructure.persistence.entity

import com.openbank.libs.approval.ApprovalStatus
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
@Table(name = "settlement_operator_approvals")
class SettlementOperatorApprovalEntity : PanacheEntityBase() {
    @Id
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var action: String

    @Column(name = "resource_id")
    var resourceId: String? = null

    @Column(name = "maker_id", nullable = false)
    lateinit var makerId: String

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
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

    fun toDomain() = PendingApproval(
        id.toString(),
        action,
        resourceId,
        makerId,
        status,
        createdAt,
        decidedBy,
        decidedAt,
    )
}
