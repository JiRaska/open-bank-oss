// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.application.port.`in`

import com.openbank.sepainstant.domain.model.SctInstPayment
import io.smallrye.mutiny.Uni
import java.util.UUID

interface SubmitSctInstPaymentUseCase {
    fun submit(command: SubmitSctInstCommand): Uni<SctInstPayment>
}

interface GetSctInstPaymentUseCase {
    fun getById(paymentId: UUID): Uni<SctInstPayment>
    fun listAll(): Uni<List<SctInstPayment>>
    fun listByDebtor(debtorAccountId: UUID, page: Int, size: Int): Uni<List<SctInstPayment>>
}

interface RecallSctInstPaymentUseCase {
    fun recall(paymentId: UUID, reason: String): Uni<SctInstPayment>
}

data class SubmitSctInstCommand(
    val idempotencyKey: String,
    val debtorAccountId: UUID,
    val debtorIban: String,
    val debtorName: String,
    val creditorIban: String,
    val creditorName: String,
    val creditorBic: String?,
    val amount: java.math.BigDecimal,
    val currency: String = "EUR",
    val remittanceInfo: String?,
    val endToEndId: String
)
