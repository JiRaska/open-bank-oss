// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "domestic_payments")
class DomesticPaymentEntity : PanacheEntity() {
    @Column(name = "payment_id", nullable = false, unique = true)
    lateinit var paymentId: UUID

    @Column(name = "idempotency_key", nullable = false, unique = true)
    lateinit var idempotencyKey: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "debtor_account_id", nullable = false)
    lateinit var debtorAccountId: UUID

    @Column(name = "debtor_account_number", nullable = false)
    lateinit var debtorAccountNumber: String

    @Column(name = "debtor_bank_code", nullable = false)
    lateinit var debtorBankCode: String

    @Column(name = "debtor_name", nullable = false)
    lateinit var debtorName: String

    @Column(name = "creditor_account_number", nullable = false)
    lateinit var creditorAccountNumber: String

    @Column(name = "creditor_bank_code", nullable = false)
    lateinit var creditorBankCode: String

    @Column(name = "creditor_name", nullable = false)
    lateinit var creditorName: String

    @Column(name = "amount", nullable = false, precision = 20, scale = 6)
    lateinit var amount: BigDecimal

    @Column(name = "currency", nullable = false, length = 3)
    lateinit var currency: String

    @Column(name = "variable_symbol")
    var variableSymbol: String? = null

    @Column(name = "specific_symbol")
    var specificSymbol: String? = null

    @Column(name = "constant_symbol")
    var constantSymbol: String? = null

    @Column(name = "message_for_payee")
    var messageForPayee: String? = null

    @Column(name = "priority", nullable = false)
    lateinit var priority: String

    @Column(name = "transfer_scope", nullable = false)
    lateinit var transferScope: String

    @Column(name = "technical_account_code")
    var technicalAccountCode: String? = null

    @Column(name = "statement_label")
    var statementLabel: String? = null

    @Column(name = "end_to_end_id", nullable = false)
    lateinit var endToEndId: String

    @Column(name = "reject_reason")
    var rejectReason: String? = null

    // V1 declares this TEXT; without columnDefinition Hibernate maps a String to varchar(255) and
    // schema validation reports "wrong column type encountered in column [reject_detail]" (#3081).
    // The migration is applied, so its checksum is frozen — the entity is what moves.
    @Column(name = "reject_detail", columnDefinition = "text")
    var rejectDetail: String? = null

    @Column(name = "submitted_at")
    var submittedAt: Instant? = null

    // #4218. Set before the pacs.008 leaves for the scheme, so it outlives any failure of the
    // bookkeeping that follows. Distinct from submittedAt above, which is already non-null by the
    // time a payment reaches the scheme hop and so cannot record it.
    @Column(name = "scheme_dispatched_at")
    var schemeDispatchedAt: Instant? = null

    @Column(name = "settled_at")
    var settledAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @Column(name = "initiated_by_party_id")
    var initiatedByPartyId: UUID? = null

    @Column(name = "request_fingerprint", length = 64)
    var requestFingerprint: String? = null

    @Column(name = "delegation_id")
    var delegationId: UUID? = null

    @Column(name = "reservation_id", unique = true)
    var reservationId: UUID? = null

    /**
     * How many times the #3266 sweep has re-screened this payment. Persistence-only — deliberately
     * absent from the domain model, which describes the payment, not the recovery machinery.
     */
    @Column(name = "redrive_attempts", nullable = false)
    var redriveAttempts: Int = 0
}
