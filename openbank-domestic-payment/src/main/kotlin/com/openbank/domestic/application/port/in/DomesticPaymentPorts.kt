// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.port.`in`

import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.domestic.domain.model.DomesticTransferScope
import java.math.BigDecimal
import java.util.UUID

data class CreateDomesticPaymentCommand(
    val idempotencyKey: String,
    val debtorAccountId: UUID,
    val debtorAccountNumber: String,
    val debtorBankCode: String,
    val debtorName: String,
    val creditorAccountNumber: String,
    val creditorBankCode: String,
    val creditorName: String,
    val amount: BigDecimal,
    val currency: String,
    val variableSymbol: String?,
    val specificSymbol: String?,
    val constantSymbol: String?,
    val messageForPayee: String?,
    val priority: DomesticPaymentPriority,
    val transferScope: DomesticTransferScope? = null,
    val technicalAccountCode: String? = null,
    val statementLabel: String?,
    val endToEndId: String?,
    val actorId: UUID? = null,
    /** Trusted inbound synthetic taint, copied into the durable outbox boundary. */
    val synthetic: Boolean = false,
)

data class ListDomesticPaymentsQuery(
    val status: DomesticPaymentStatus? = null,
    val debtorAccountId: UUID? = null,
    val limit: Int = 50,
    val offset: Int = 0,
)

data class TransitionDomesticPaymentStatusCommand(
    val paymentId: UUID,
    val targetStatus: DomesticPaymentStatus,
    val rejectReason: DomesticRejectReason? = null,
    val rejectDetail: String? = null,
)

interface DomesticPaymentUseCase {
    suspend fun createPayment(command: CreateDomesticPaymentCommand): DomesticPayment
    suspend fun getPayment(paymentId: UUID): DomesticPayment
    suspend fun listPayments(query: ListDomesticPaymentsQuery): List<DomesticPayment>
    suspend fun transitionStatus(command: TransitionDomesticPaymentStatusCommand): DomesticPayment
}
