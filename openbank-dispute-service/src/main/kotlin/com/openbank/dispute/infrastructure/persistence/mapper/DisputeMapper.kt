// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.infrastructure.persistence.mapper

import com.openbank.dispute.domain.model.Dispute
import com.openbank.dispute.domain.model.DisputeEvidence
import com.openbank.dispute.domain.model.DisputeTimelineEvent
import com.openbank.dispute.infrastructure.persistence.entity.DisputeEntity
import com.openbank.dispute.infrastructure.persistence.entity.DisputeEvidenceEntity
import com.openbank.dispute.infrastructure.persistence.entity.DisputeTimelineEntity
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class DisputeMapper {
    fun toEntity(d: Dispute) = DisputeEntity().also {
        it.id = d.id
        it.reference = d.reference
        it.transactionId = d.transactionId
        it.accountId = d.accountId
        it.partyId = d.partyId
        it.disputeType = d.disputeType
        it.status = d.status
        it.resolution = d.resolution
        it.amount = d.amount
        it.currency = d.currency
        it.description = d.description
        it.merchantName = d.merchantName
        it.merchantId = d.merchantId
        it.transactionDate = d.transactionDate
        it.filingDate = d.filingDate
        it.resolutionDeadline = d.resolutionDeadline
        it.resolvedAt = d.resolvedAt
        it.resolvedBy = d.resolvedBy
        it.chargebackAmount = d.chargebackAmount
        it.remediationOutcome = d.remediationOutcome
        it.remediationAmount = d.remediationAmount
        it.createdAt = d.createdAt
        it.updatedAt = d.updatedAt
    }

    fun toDomain(e: DisputeEntity) = Dispute(
        id = e.id,
        reference = e.reference,
        transactionId = e.transactionId,
        accountId = e.accountId,
        partyId = e.partyId,
        disputeType = e.disputeType,
        status = e.status,
        resolution = e.resolution,
        amount = e.amount,
        currency = e.currency,
        description = e.description,
        merchantName = e.merchantName,
        merchantId = e.merchantId,
        transactionDate = e.transactionDate,
        filingDate = e.filingDate,
        resolutionDeadline = e.resolutionDeadline,
        resolvedAt = e.resolvedAt,
        resolvedBy = e.resolvedBy,
        chargebackAmount = e.chargebackAmount,
        remediationOutcome = e.remediationOutcome,
        remediationAmount = e.remediationAmount,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
    )

    fun toEntity(e: DisputeEvidence) = DisputeEvidenceEntity().also {
        it.id = e.id
        it.disputeId = e.disputeId
        it.submittedBy = e.submittedBy
        it.evidenceType = e.evidenceType
        it.description = e.description
        it.fileReference = e.fileReference
        it.submittedAt = requireNotNull(e.submittedAt) { "submittedAt must be set before persisting DisputeEvidence" }
        it.sequence = e.sequence
        it.prevHash = e.prevHash
        it.recordHash = requireNotNull(e.recordHash) { "recordHash must be set before persisting DisputeEvidence" }
    }

    fun toDomain(e: DisputeEvidenceEntity) = DisputeEvidence(
        id = e.id,
        disputeId = e.disputeId,
        submittedBy = e.submittedBy,
        evidenceType = e.evidenceType,
        description = e.description,
        fileReference = e.fileReference,
        submittedAt = e.submittedAt,
        sequence = e.sequence,
        prevHash = e.prevHash,
        recordHash = e.recordHash,
    )

    fun toEntity(e: DisputeTimelineEvent) = DisputeTimelineEntity().also {
        it.id = e.id
        it.disputeId = e.disputeId
        it.eventType = e.eventType
        it.description = e.description
        it.actor = e.actor
        it.createdAt = e.createdAt
    }

    fun toDomain(e: DisputeTimelineEntity) = DisputeTimelineEvent(
        id = e.id,
        disputeId = e.disputeId,
        eventType = e.eventType,
        description = e.description,
        actor = e.actor,
        createdAt = e.createdAt,
    )
}
