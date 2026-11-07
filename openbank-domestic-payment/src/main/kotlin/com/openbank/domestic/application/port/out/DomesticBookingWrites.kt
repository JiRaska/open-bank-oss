// SPDX-License-Identifier: Apache-2.0

package com.openbank.domestic.application.port.out

import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.util.UUID

/** Internal booking lifecycle, never an operator API or a new financial retry. */
interface DomesticBookingWrites {
    suspend fun acceptForBooking(payment: DomesticPayment, outbox: OutboxMessage): DomesticPayment
    suspend fun completeBooking(payment: DomesticPayment, transactionId: UUID, outbox: OutboxMessage): DomesticPayment
}
