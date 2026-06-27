// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.application.port.out

import com.openbank.sepainstant.domain.event.SctInstEvent
import com.openbank.sepainstant.domain.model.SctInstPayment
import com.openbank.sepainstant.domain.model.SctInstStatus
import io.smallrye.mutiny.Uni
import java.util.UUID

/** Outbound persistence port for the SEPA-instant (SCT Inst) payment aggregate. */
interface SctInstPaymentRepository {

    fun save(payment: SctInstPayment): Uni<SctInstPayment>

    fun findByPaymentId(paymentId: UUID): Uni<SctInstPayment?>

    fun findByIdempotencyKey(key: String): Uni<SctInstPayment?>

    fun findAll(): Uni<List<SctInstPayment>>

    fun findByDebtorAccountId(debtorAccountId: UUID, page: Int, size: Int): Uni<List<SctInstPayment>>

    fun updateStatus(paymentId: UUID, status: SctInstStatus): Uni<Int>

    fun findTimedOut(): Uni<List<SctInstPayment>>
}

/** Outbound port for publishing SCT Inst domain events directly to the transport. */
interface SctInstEventPublisher {

    fun publish(event: SctInstEvent): Uni<Void>
}
