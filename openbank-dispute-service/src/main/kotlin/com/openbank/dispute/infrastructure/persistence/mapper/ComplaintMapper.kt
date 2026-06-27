// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.infrastructure.persistence.mapper

import com.openbank.dispute.domain.model.Complaint
import com.openbank.dispute.infrastructure.persistence.entity.ComplaintEntity
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ComplaintMapper {
    fun toEntity(c: Complaint) = ComplaintEntity().also {
        it.id = c.id
        it.reference = c.reference
        it.category = c.category
        it.channel = c.channel
        it.description = c.description
        it.status = c.status
        it.accountId = c.accountId
        it.transactionId = c.transactionId
        it.disputeId = c.disputeId
        it.receivedDate = c.receivedDate
        it.dueDate = c.dueDate
        it.interimReplyAt = c.interimReplyAt
        it.interimReplyReason = c.interimReplyReason
        it.resolvedAt = c.resolvedAt
        it.outcome = c.outcome
        it.redressGranted = c.redressGranted
        it.rootCauseCode = c.rootCauseCode
        it.closedAt = c.closedAt
        it.createdAt = c.createdAt
        it.updatedAt = c.updatedAt
    }

    fun toDomain(e: ComplaintEntity) = Complaint(
        id = e.id, reference = e.reference, category = e.category,
        channel = e.channel, description = e.description, status = e.status,
        accountId = e.accountId, transactionId = e.transactionId, disputeId = e.disputeId,
        receivedDate = e.receivedDate, dueDate = e.dueDate,
        interimReplyAt = e.interimReplyAt, interimReplyReason = e.interimReplyReason,
        resolvedAt = e.resolvedAt, outcome = e.outcome, redressGranted = e.redressGranted,
        rootCauseCode = e.rootCauseCode, closedAt = e.closedAt,
        createdAt = e.createdAt, updatedAt = e.updatedAt,
    )
}
