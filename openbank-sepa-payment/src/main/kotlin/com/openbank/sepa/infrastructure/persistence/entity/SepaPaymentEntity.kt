// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "sepa_payments")
class SepaPaymentEntity : PanacheEntity() {
    @Column(name = "payment_id", nullable = false, unique = true)
    lateinit var paymentId: UUID

    @Column(name = "idempotency_key", nullable = false, unique = true)
    lateinit var idempotencyKey: String

    @Column(name = "payment_type", nullable = false)
    lateinit var paymentType: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "debtor_account_id", nullable = false)
    lateinit var debtorAccountId: UUID

    @Column(name = "debtor_iban", nullable = false)
    lateinit var debtorIban: String

    @Column(name = "debtor_name", nullable = false)
    lateinit var debtorName: String

    @Column(name = "creditor_iban", nullable = false)
    lateinit var creditorIban: String

    @Column(name = "creditor_name", nullable = false)
    lateinit var creditorName: String

    @Column(name = "creditor_bic")
    var creditorBic: String? = null

    @Column(name = "amount", nullable = false, precision = 20, scale = 6)
    lateinit var amount: BigDecimal

    @Column(name = "currency", nullable = false, length = 3)
    lateinit var currency: String

    @Column(name = "remittance_info")
    var remittanceInfo: String? = null

    @Column(name = "end_to_end_id", nullable = false)
    lateinit var endToEndId: String

    @Column(name = "reject_reason")
    var rejectReason: String? = null

    @Column(name = "reject_detail")
    var rejectDetail: String? = null

    @Column(name = "submitted_at")
    var submittedAt: Instant? = null

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    @Column(name = "transaction_id")
    var transactionId: java.util.UUID? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
