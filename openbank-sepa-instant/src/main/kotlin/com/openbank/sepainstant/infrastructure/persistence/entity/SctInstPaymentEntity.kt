// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.persistence.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "sct_inst_payments")
class SctInstPaymentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0
    @Column(name = "payment_id", nullable = false, unique = true) var paymentId: UUID = UUID.randomUUID()
    @Column(name = "idempotency_key", nullable = false, unique = true) var idempotencyKey: String = ""
    @Column(nullable = false) var status: String = "PENDING"
    @Column(name = "debtor_account_id", nullable = false) var debtorAccountId: UUID = UUID.randomUUID()
    @Column(name = "debtor_iban", nullable = false) var debtorIban: String = ""
    @Column(name = "debtor_name", nullable = false) var debtorName: String = ""
    @Column(name = "creditor_iban", nullable = false) var creditorIban: String = ""
    @Column(name = "creditor_name", nullable = false) var creditorName: String = ""
    @Column(name = "creditor_bic") var creditorBic: String? = null
    @Column(nullable = false, precision = 20, scale = 6) var amount: BigDecimal = BigDecimal.ZERO
    @Column(nullable = false) var currency: String = "EUR"
    @Column(name = "remittance_info") var remittanceInfo: String? = null
    @Column(name = "end_to_end_id", nullable = false) var endToEndId: String = ""
    @Column(name = "execution_timeout_at") var executionTimeoutAt: OffsetDateTime? = null
    @Column(name = "settled_at") var settledAt: OffsetDateTime? = null
    @Column(name = "recalled_at") var recalledAt: OffsetDateTime? = null
    @Column(name = "recall_reason") var recallReason: String? = null
    @Column(name = "reject_reason") var rejectReason: String? = null
    @Column(name = "reject_detail") var rejectDetail: String? = null
    @Column(name = "submitted_at") var submittedAt: OffsetDateTime? = null
    @Column(name = "created_at", nullable = false) var createdAt: OffsetDateTime = OffsetDateTime.MIN
    @Column(name = "updated_at", nullable = false) var updatedAt: OffsetDateTime = OffsetDateTime.MIN
}
