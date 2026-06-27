// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.infrastructure.persistence.entity

import com.openbank.dispute.domain.model.ComplaintCategory
import com.openbank.dispute.domain.model.ComplaintChannel
import com.openbank.dispute.domain.model.ComplaintStatus
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "complaints")
class ComplaintEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = UUID.randomUUID()

    @Column(name = "reference", unique = true)
    var reference: String = ""

    @Column(name = "category")
    @Enumerated(EnumType.STRING)
    var category: ComplaintCategory = ComplaintCategory.OTHER

    @Column(name = "channel")
    @Enumerated(EnumType.STRING)
    var channel: ComplaintChannel = ComplaintChannel.APP

    @Column(name = "description", columnDefinition = "TEXT")
    var description: String = ""

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    var status: ComplaintStatus = ComplaintStatus.RECEIVED

    @Column(name = "account_id", columnDefinition = "uuid")
    var accountId: UUID? = null

    @Column(name = "transaction_id", columnDefinition = "uuid")
    var transactionId: UUID? = null

    @Column(name = "dispute_id", columnDefinition = "uuid")
    var disputeId: UUID? = null

    @Column(name = "received_date")
    var receivedDate: LocalDate = LocalDate.EPOCH

    @Column(name = "due_date")
    var dueDate: LocalDate = LocalDate.EPOCH

    @Column(name = "interim_reply_at")
    var interimReplyAt: OffsetDateTime? = null

    @Column(name = "interim_reply_reason", columnDefinition = "TEXT")
    var interimReplyReason: String? = null

    @Column(name = "resolved_at")
    var resolvedAt: OffsetDateTime? = null

    @Column(name = "outcome", columnDefinition = "TEXT")
    var outcome: String? = null

    @Column(name = "redress_granted")
    var redressGranted: Boolean? = null

    @Column(name = "root_cause_code")
    var rootCauseCode: String? = null

    @Column(name = "closed_at")
    var closedAt: OffsetDateTime? = null

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN

    @Column(name = "updated_at")
    var updatedAt: OffsetDateTime = OffsetDateTime.MIN
}
