// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.port.`in`

import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.domain.model.SepaRejectReason
import java.math.BigDecimal
import java.util.UUID

data class CreateSepaPaymentCommand(
    val idempotencyKey: String,
    val type: SepaPaymentType,
    val debtorAccountId: UUID,
    val debtorIban: String,
    val debtorName: String,
    val creditorIban: String,
    val creditorName: String,
    val creditorBic: String?,
    val amount: BigDecimal,
    val currency: String,
    val remittanceInfo: String?,
    val endToEndId: String?,
)

data class ListSepaPaymentsQuery(
    val status: SepaPaymentStatus? = null,
    val debtorAccountId: UUID? = null,
    val limit: Int = 50,
    val offset: Int = 0,
)

data class TransitionSepaPaymentStatusCommand(
    val paymentId: UUID,
    val targetStatus: SepaPaymentStatus,
    val rejectReason: SepaRejectReason? = null,
    val rejectDetail: String? = null,
)

/**
 * An inbound pacs.004 return, together with the **server-derived** identity of the caller that
 * presented it (issue #6056).
 *
 * [actorId], [actorType] and [correlationId] are resolved in the REST adapter from the
 * `SecurityContext` and the request context that `CorrelationIdRequestFilter` populated — never
 * from the request body. A repudiation control whose actor is supplied by the party whose action
 * is in dispute records nothing (the shape #4754 found elsewhere in this fleet), so the pacs.004
 * XML deliberately carries no actor field and none is read from it.
 */
data class HandlePaymentReturnCommand(
    val pacs004Xml: String,
    val actorId: String,
    val actorType: String,
    val correlationId: String?,
)

interface SepaPaymentUseCase {
    suspend fun createPayment(command: CreateSepaPaymentCommand): SepaPayment
    suspend fun getPayment(paymentId: UUID): SepaPayment
    suspend fun listPayments(query: ListSepaPaymentsQuery): List<SepaPayment>
    suspend fun transitionStatus(command: TransitionSepaPaymentStatusCommand): SepaPayment
    suspend fun handlePaymentReturn(command: HandlePaymentReturnCommand): SepaPayment
}

/**
 * A rendered, never-persisted payment confirmation document (ADR-0248 #3) — rendered synchronously
 * at the moment the customer requests it and streamed straight back; nothing here is cached or
 * written to disk anywhere in this service or in document-service.
 */
class PaymentConfirmationDocument(val contentType: String, val fileName: String, val bytes: ByteArray)

interface PaymentConfirmationUseCase {
    /** Only meaningful for a `COMPLETED` payment; throws otherwise (`PaymentNotCompletedException`). */
    suspend fun getConfirmation(paymentId: UUID, locale: String): PaymentConfirmationDocument
}
