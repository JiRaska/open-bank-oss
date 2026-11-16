// SPDX-License-Identifier: Apache-2.0

package com.openbank.account.application.port.out

import com.openbank.account.domain.model.AccountBookingReceipt
import com.openbank.account.domain.model.ProductRevisionPin
import java.util.UUID

/** A pending decision committed by the trusted market admission workflow. */
data class PendingAccountBooking(
    val operationId: UUID,
    val idempotencyKey: String,
    val requestHash: String,
    val partyId: UUID,
    val productId: UUID,
    val grantRevisionId: UUID,
    val pin: ProductRevisionPin,
)

interface AccountBookingPort {
    suspend fun findPending(operationId: UUID): PendingAccountBooking?
    suspend fun consume(pending: PendingAccountBooking): AccountBookingReceipt
}
