// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.persistence.entity

import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "transactions")
@IdClass(TransactionEntityId::class)
class TransactionEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: UUID = UUID.randomUUID()

    @Id
    @Column(name = "booking_date")
    var bookingDate: LocalDate = LocalDate.EPOCH

    @Column(name = "reference_number", nullable = false)
    var referenceNumber: String = ""

    @Column(name = "type", nullable = false)
    var type: String = ""

    @Column(name = "source_account_id")
    var sourceAccountId: UUID? = null

    @Column(name = "target_account_id")
    var targetAccountId: UUID? = null

    @Column(name = "amount", nullable = false)
    var amount: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency_code", nullable = false)
    var currencyCode: String = "CZK"

    @Column(name = "fx_rate")
    var fxRate: BigDecimal? = null

    @Column(name = "base_amount", nullable = false)
    var baseAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "base_currency_code", nullable = false)
    var baseCurrencyCode: String = "CZK"

    @Column(name = "status", nullable = false)
    var status: String = "PENDING"

    @Column(name = "description")
    var description: String? = null

    @Column(name = "value_date", nullable = false)
    var valueDate: LocalDate = LocalDate.EPOCH

    @Column(name = "initiated_at", nullable = false)
    var initiatedAt: Instant = Instant.now()

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    @Column(name = "failed_at")
    var failedAt: Instant? = null

    @Column(name = "failure_reason")
    var failureReason: String? = null

    @Column(name = "idempotency_key", nullable = false)
    var idempotencyKey: String = ""

    // @Version (#465): without it Hibernate's flush UPDATE matches by PK only and two racing
    // read-modify-write transactions both commit (last write wins) — e.g. two reversals with
    // distinct idempotency keys both flipped COMPLETED and BOTH initiated a refund. With it the
    // loser's flush matches 0 rows and surfaces as an optimistic-lock failure -> 409.
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    // V2 compliance fields
    @Column(name = "actor_id")
    var actorId: String? = null

    @Column(name = "actor_type")
    var actorType: String? = "SYSTEM"

    @Column(name = "ip_address")
    var ipAddress: String? = null

    @Column(name = "correlation_id")
    var correlationId: String? = null

    @Column(name = "purpose_code")
    var purposeCode: String? = null

    @Column(name = "aml_screened")
    var amlScreened: Boolean = false

    @Column(name = "reversal_of")
    var reversalOf: UUID? = null

    // V3 BIAN/IBAN/BBAN fields
    @Column(name = "source_iban")
    var sourceIban: String? = null

    @Column(name = "source_bban")
    var sourceBban: String? = null

    @Column(name = "target_iban")
    var targetIban: String? = null

    @Column(name = "target_bban")
    var targetBban: String? = null

    @Column(name = "counterparty_name")
    var counterpartyName: String? = null

    @Column(name = "counterparty_bank_bic")
    var counterpartyBankBic: String? = null

    @Column(name = "remittance_info")
    var remittanceInfo: String? = null

    @Column(name = "end_to_end_id")
    var endToEndId: String? = null

    @Column(name = "bank_transaction_code")
    var bankTransactionCode: String? = null

    @Column(name = "fee_amount")
    var feeAmount: BigDecimal? = null

    @Column(name = "fee_currency")
    var feeCurrency: String? = null

    @Column(name = "is_reversal")
    var isReversal: Boolean = false

    @Column(name = "is_fee_transaction")
    var isFeeTransaction: Boolean = false

    @Column(name = "technical_account_id")
    var technicalAccountId: UUID? = null

    // ── SCA linkage (ADR-0021 settlement gate / non-repudiation) ──
    @Column(name = "sca_challenge_id")
    var scaChallengeId: UUID? = null

    @Column(name = "sca_exemption")
    var scaExemption: String? = null

    // ── Payment rail + instruction type (ADR-0103) — stored as the enum name; null until
    // an originating service stamps it (D2) / a backfill fills it (D4). ──
    @Column(name = "rail")
    var rail: String? = null

    @Column(name = "instruction_type")
    var instructionType: String? = null

    @Column(name = "merchant_category")
    var merchantCategory: String? = null

    // ── ADR-0108: rail payment that triggered this transaction (null for operator/system postings) ──
    @Column(name = "originating_payment_id")
    var originatingPaymentId: UUID? = null
}

data class TransactionEntityId(val id: UUID = Ids.newId(), val bookingDate: LocalDate = LocalDate.EPOCH) :
    java.io.Serializable
