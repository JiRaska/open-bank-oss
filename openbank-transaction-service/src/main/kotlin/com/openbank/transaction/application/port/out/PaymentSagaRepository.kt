// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.application.port.out

import com.openbank.transaction.domain.saga.PaymentSaga
import java.util.UUID

/** Outbound persistence port for the payment saga aggregate (state-machine projection). */
interface PaymentSagaRepository {

    suspend fun save(saga: PaymentSaga): PaymentSaga

    suspend fun findById(sagaId: UUID): PaymentSaga?

    suspend fun findByTransactionId(transactionId: UUID): PaymentSaga?

    suspend fun findByIdempotencyKey(idempotencyKey: String): PaymentSaga?

    suspend fun update(saga: PaymentSaga): PaymentSaga
}
