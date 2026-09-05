// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.persistence.entity

import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "swift_messages")
class SwiftMessageEntity {
    @Id var id: UUID = UUID.randomUUID()

    @field:Column(name = "idempotency_key", unique = true)
    var idempotencyKey: String = ""

    @Enumerated(EnumType.STRING)
    @field:Column(name = "message_type")
    var messageType: SwiftMessageType = SwiftMessageType.MT103

    @field:Column(name = "sender_bic")
    var senderBic: String = ""

    @field:Column(name = "receiver_bic")
    var receiverBic: String = ""

    @field:Column(name = "transaction_reference")
    var transactionReference: String = ""

    @field:Column(name = "related_reference")
    var relatedReference: String? = null

    @field:Column(name = "value_date")
    var valueDate: LocalDate = LocalDate.EPOCH
    var currency: String = "EUR"

    @field:Column(name = "amount_minor_units")
    var amountMinorUnits: Long = 0

    @field:Column(name = "ordering_customer_account")
    var orderingCustomerAccount: String? = null

    @field:Column(name = "ordering_customer_account_id")
    var orderingCustomerAccountId: UUID? = null

    @field:Column(name = "ordering_customer_name")
    var orderingCustomerName: String? = null

    @field:Column(name = "beneficiary_account")
    var beneficiaryAccount: String = ""

    @field:Column(name = "beneficiary_name")
    var beneficiaryName: String = ""

    @field:Column(name = "remittance_info")
    var remittanceInfo: String? = null

    @field:Column(name = "charge_code")
    var chargeCode: String = "SHA"

    @Enumerated(EnumType.STRING)
    var priority: SwiftPriority = SwiftPriority.NORMAL

    @Enumerated(EnumType.STRING)
    var status: SwiftStatus = SwiftStatus.PENDING

    @field:Column(name = "raw_mt", columnDefinition = "TEXT")
    var rawMt: String? = null

    @field:Column(name = "ack_received_at")
    var ackReceivedAt: Instant? = null

    @field:Column(name = "rejection_reason")
    var rejectionReason: String? = null

    @field:Version
    var version: Long = 0

    @field:Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @field:Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
}
