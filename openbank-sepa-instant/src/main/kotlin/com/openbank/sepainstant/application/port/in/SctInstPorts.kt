// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.application.port.`in`

import com.openbank.libs.domain.money.Money
import com.openbank.sepainstant.domain.model.SctInstPayment
import io.smallrye.mutiny.Uni
import java.util.UUID

interface SubmitSctInstPaymentUseCase {
    fun submit(command: SubmitSctInstCommand): Uni<SctInstPayment>
    fun findReceipt(
        key: String,
        accountId: UUID,
        principal: String,
        partyId: UUID?,
        actorId: UUID?,
    ): Uni<SctInstPayment?>
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
    /** Built by [Money.parseInbound] at the REST boundary, before the idempotency key is looked up. */
    val amount: Money,
    val remittanceInfo: String?,
    val endToEndId: String,
    val requestHash: String? = null,
    val initiatingPrincipal: String? = null,
    val initiatingPartyId: UUID? = null,
    val initiatingActorPartyId: UUID? = null,
)
