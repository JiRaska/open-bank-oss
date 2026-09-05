// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.persistence.entity

import com.openbank.standingorder.domain.model.Frequency
import com.openbank.standingorder.domain.model.PaymentType
import com.openbank.standingorder.domain.model.StandingOrderStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "standing_orders")
class StandingOrderEntity {
    @Id var id: UUID = UUID.randomUUID()

    @field:Column(name = "idempotency_key", unique = true)
    var idempotencyKey: String = ""

    @field:Column(name = "party_id")
    var partyId: UUID = UUID.randomUUID()

    @field:Column(name = "debit_account_id")
    var debitAccountId: UUID = UUID.randomUUID()

    @field:Column(name = "debtor_iban")
    var debtorIban: String? = null

    @field:Column(name = "debtor_name")
    var debtorName: String? = null

    @field:Column(name = "creditor_iban")
    var creditorIban: String = ""

    @field:Column(name = "creditor_name")
    var creditorName: String = ""

    @field:Column(name = "creditor_bic")
    var creditorBic: String? = null

    @field:Column(name = "amount_minor_units")
    var amountMinorUnits: Long = 0
    var currency: String = "EUR"

    @Enumerated(EnumType.STRING)
    var frequency: Frequency = Frequency.MONTHLY

    @Enumerated(EnumType.STRING)
    @field:Column(name = "payment_type")
    var paymentType: PaymentType = PaymentType.SEPA_CREDIT

    @field:Column(name = "remittance_info")
    var remittanceInfo: String? = null

    @field:Column(name = "start_date")
    var startDate: LocalDate = LocalDate.EPOCH

    @field:Column(name = "end_date")
    var endDate: LocalDate? = null

    @field:Column(name = "next_execution_date")
    var nextExecutionDate: LocalDate = LocalDate.EPOCH

    @field:Column(name = "last_execution_date")
    var lastExecutionDate: LocalDate? = null

    @field:Column(name = "execution_count")
    var executionCount: Int = 0

    @field:Column(name = "failure_count")
    var failureCount: Int = 0

    @Enumerated(EnumType.STRING)
    var status: StandingOrderStatus = StandingOrderStatus.ACTIVE

    @field:Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @field:Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
}
