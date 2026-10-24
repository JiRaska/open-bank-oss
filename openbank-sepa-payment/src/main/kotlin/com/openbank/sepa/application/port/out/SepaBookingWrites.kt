// SPDX-License-Identifier: Apache-2.0
package com.openbank.sepa.application.port.out

import com.openbank.sepa.domain.model.SepaPayment

interface SepaBookingWrites {
    suspend fun acceptForBooking(payment: SepaPayment, outbox: SepaPaymentOutboxMessage): SepaPayment
}
