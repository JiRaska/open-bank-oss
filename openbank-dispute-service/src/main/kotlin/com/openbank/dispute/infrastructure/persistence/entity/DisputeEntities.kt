// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.infrastructure.persistence.entity

import com.openbank.dispute.domain.model.*
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Entity @Table(name = "disputes")
class DisputeEntity : PanacheEntityBase() {
    @Id @Column(columnDefinition = "uuid") var id: UUID = UUID.randomUUID()
    @Column(name = "reference", unique = true) var reference: String = ""
    @Column(name = "transaction_id", columnDefinition = "uuid") var transactionId: UUID = UUID.randomUUID()
    @Column(name = "account_id", columnDefinition = "uuid") var accountId: UUID = UUID.randomUUID()
    @Column(name = "party_id", columnDefinition = "uuid") var partyId: UUID = UUID.randomUUID()
    @Column(name = "dispute_type") @Enumerated(EnumType.STRING) var disputeType: DisputeType = DisputeType.OTHER
    @Column(name = "status") @Enumerated(EnumType.STRING) var status: DisputeStatus = DisputeStatus.OPEN
    @Column(name = "resolution") @Enumerated(EnumType.STRING) var resolution: DisputeResolution = DisputeResolution.PENDING
    @Column(name = "amount", precision = 20, scale = 4) var amount: BigDecimal = BigDecimal.ZERO
    @Column(name = "currency", length = 3) var currency: String = "EUR"
    @Column(name = "description", columnDefinition = "TEXT") var description: String? = null
    @Column(name = "merchant_name") var merchantName: String? = null
    @Column(name = "merchant_id") var merchantId: String? = null
    @Column(name = "transaction_date") var transactionDate: LocalDate = LocalDate.EPOCH
    @Column(name = "filing_date") var filingDate: LocalDate = LocalDate.EPOCH
    @Column(name = "resolution_deadline") var resolutionDeadline: LocalDate? = null
    @Column(name = "resolved_at") var resolvedAt: OffsetDateTime? = null
    @Column(name = "resolved_by") var resolvedBy: String? = null
    @Column(name = "chargeback_amount", precision = 20, scale = 4) var chargebackAmount: BigDecimal? = null
    @Column(name = "created_at") var createdAt: OffsetDateTime = OffsetDateTime.MIN
    @Column(name = "updated_at") var updatedAt: OffsetDateTime = OffsetDateTime.MIN
}

@Entity @Table(name = "dispute_evidence")
class DisputeEvidenceEntity : PanacheEntityBase() {
    @Id @Column(columnDefinition = "uuid") var id: UUID = UUID.randomUUID()
    @Column(name = "dispute_id", columnDefinition = "uuid") var disputeId: UUID = UUID.randomUUID()
    @Column(name = "submitted_by") var submittedBy: String = ""
    @Column(name = "evidence_type") var evidenceType: String = ""
    @Column(name = "description", columnDefinition = "TEXT") var description: String? = null
    @Column(name = "file_reference") var fileReference: String? = null
    @Column(name = "submitted_at") var submittedAt: OffsetDateTime = OffsetDateTime.MIN
}

@Entity @Table(name = "dispute_timeline")
class DisputeTimelineEntity : PanacheEntityBase() {
    @Id @Column(columnDefinition = "uuid") var id: UUID = UUID.randomUUID()
    @Column(name = "dispute_id", columnDefinition = "uuid") var disputeId: UUID = UUID.randomUUID()
    @Column(name = "event_type") var eventType: String = ""
    @Column(name = "description", columnDefinition = "TEXT") var description: String = ""
    @Column(name = "actor") var actor: String? = null
    @Column(name = "created_at") var createdAt: OffsetDateTime = OffsetDateTime.MIN
}
