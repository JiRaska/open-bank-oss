// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.dispute.domain.model

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

enum class DisputeType { UNAUTHORIZED, DUPLICATE, GOODS_NOT_RECEIVED, NOT_AS_DESCRIBED, CREDIT_NOT_PROCESSED, TECHNICAL_ERROR, OTHER }
enum class DisputeStatus { OPEN, UNDER_REVIEW, PENDING_CUSTOMER, PENDING_MERCHANT, RESOLVED_CUSTOMER, RESOLVED_MERCHANT, WITHDRAWN, ESCALATED }
enum class DisputeResolution { CHARGEBACK, REPRESENTMENT, ARBITRATION, WITHDRAWN, PENDING }

data class Dispute(
    val id: UUID = UUID.randomUUID(),
    val reference: String,
    val transactionId: UUID,
    val accountId: UUID,
    val partyId: UUID,
    val disputeType: DisputeType,
    val status: DisputeStatus = DisputeStatus.OPEN,
    val resolution: DisputeResolution = DisputeResolution.PENDING,
    val amount: BigDecimal,
    val currency: String = "EUR",
    val description: String? = null,
    val merchantName: String? = null,
    val merchantId: String? = null,
    val transactionDate: LocalDate,
    val filingDate: LocalDate,
    val resolutionDeadline: LocalDate? = null,
    val resolvedAt: OffsetDateTime? = null,
    val resolvedBy: String? = null,
    val chargebackAmount: BigDecimal? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class DisputeEvidence(
    val id: UUID = UUID.randomUUID(),
    val disputeId: UUID,
    val submittedBy: String,
    val evidenceType: String,
    val description: String? = null,
    val fileReference: String? = null,
    /** Stamped by the application layer (DisputeService) using the injected Clock. */
    val submittedAt: OffsetDateTime? = null,
)

data class DisputeTimelineEvent(
    val id: UUID = UUID.randomUUID(),
    val disputeId: UUID,
    val eventType: String,
    val description: String,
    val actor: String? = null,
    val createdAt: OffsetDateTime,
)

data class OpenDisputeRequest(
    val transactionId: UUID,
    val accountId: UUID,
    val partyId: UUID,
    val disputeType: DisputeType,
    val amount: BigDecimal,
    val currency: String = "EUR",
    val description: String? = null,
    val merchantName: String? = null,
    val merchantId: String? = null,
    val transactionDate: LocalDate,
)

data class UpdateDisputeRequest(
    val status: DisputeStatus? = null,
    val resolution: DisputeResolution? = null,
    val chargebackAmount: BigDecimal? = null,
    val resolvedBy: String? = null,
)
