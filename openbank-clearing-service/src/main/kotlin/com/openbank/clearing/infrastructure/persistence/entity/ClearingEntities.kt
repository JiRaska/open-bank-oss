// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "clearing_batches")
class ClearingBatchEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = UUID.randomUUID()

    @Column(name = "batch_reference", unique = true, nullable = false)
    var batchReference: String = ""

    @Column(name = "rail", nullable = false)
    @Enumerated(EnumType.STRING)
    var rail:
        com.openbank.clearing.domain.model.PaymentRail = com.openbank.clearing.domain.model.PaymentRail.SEPA_SCT

    @Column(name = "settlement_type")
    @Enumerated(EnumType.STRING)
    var settlementType:
        com.openbank.clearing.domain.model.SettlementType = com.openbank.clearing.domain.model.SettlementType.NET

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    var status: com.openbank.clearing.domain.model.ClearingStatus =
        com.openbank.clearing.domain.model.ClearingStatus.PENDING

    @Column(name = "total_debit", precision = 20, scale = 4)
    var totalDebit: BigDecimal = BigDecimal.ZERO

    @Column(name = "total_credit", precision = 20, scale = 4)
    var totalCredit: BigDecimal = BigDecimal.ZERO

    @Column(name = "net_position", precision = 20, scale = 4)
    var netPosition: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency", length = 3)
    var currency: String = "EUR"

    @Column(name = "item_count")
    var itemCount: Int = 0

    @Column(name = "cycle_id")
    var cycleId: String? = null

    @Column(name = "settlement_date")
    var settlementDate: LocalDate? = null

    @Column(name = "settled_at")
    var settledAt: OffsetDateTime? = null

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN

    @Column(name = "updated_at")
    var updatedAt: OffsetDateTime = OffsetDateTime.MIN
}

@Entity
@Table(name = "clearing_items")
class ClearingItemEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = UUID.randomUUID()

    @Column(name = "batch_id", columnDefinition = "uuid")
    var batchId: UUID = UUID.randomUUID()

    @Column(name = "payment_id", columnDefinition = "uuid")
    var paymentId: UUID = UUID.randomUUID()

    @Column(name = "payment_reference")
    var paymentReference: String = ""

    @Column(name = "debtor_iban")
    var debtorIban: String = ""

    @Column(name = "creditor_iban")
    var creditorIban: String = ""

    @Column(name = "debtor_bic")
    var debtorBic: String? = null

    @Column(name = "creditor_bic")
    var creditorBic: String? = null

    @Column(name = "amount", precision = 20, scale = 4)
    var amount: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency", length = 3)
    var currency: String = "EUR"

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    var status: com.openbank.clearing.domain.model.ClearingStatus =
        com.openbank.clearing.domain.model.ClearingStatus.PENDING

    @Column(name = "value_date")
    var valueDate: LocalDate? = null

    @Column(name = "end_to_end_id")
    var endToEndId: String? = null

    @Column(name = "remittance_info")
    var remittanceInfo: String? = null

    @Column(name = "error_code")
    var errorCode: String? = null

    @Column(name = "error_message")
    var errorMessage: String? = null

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN

    @Column(name = "updated_at")
    var updatedAt: OffsetDateTime = OffsetDateTime.MIN
}

@Entity
@Table(name = "settlement_positions")
class SettlementPositionEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = UUID.randomUUID()

    @Column(name = "participant_bic")
    var participantBic: String = ""

    @Column(name = "currency", length = 3)
    var currency: String = "EUR"

    @Column(name = "cycle_id")
    var cycleId: String = ""

    @Column(name = "gross_debit", precision = 20, scale = 4)
    var grossDebit: BigDecimal = BigDecimal.ZERO

    @Column(name = "gross_credit", precision = 20, scale = 4)
    var grossCredit: BigDecimal = BigDecimal.ZERO

    @Column(name = "net_position", precision = 20, scale = 4)
    var netPosition: BigDecimal = BigDecimal.ZERO

    @Column(name = "settled")
    var settled: Boolean = false

    @Column(name = "settled_at")
    var settledAt: OffsetDateTime? = null

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN
}
