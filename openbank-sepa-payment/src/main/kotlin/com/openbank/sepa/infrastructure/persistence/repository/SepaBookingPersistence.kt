// SPDX-License-Identifier: Apache-2.0
package com.openbank.sepa.infrastructure.persistence.repository

import com.openbank.sepa.domain.model.SepaBookingClock
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.infrastructure.persistence.entity.SepaPaymentEntity
import java.time.Instant
import java.util.UUID

/** Only invoked under the lifecycle write's row lock and revision check. */
internal fun SepaPaymentEntity.applyBookingAcceptance(payment: SepaPayment, accepted: Boolean) {
    if (accepted) {
        val next = SepaBookingClock(bookingFirstDueAt, bookingProvenAt, bookingTransactionId)
            .accepted(SepaPaymentStatus.valueOf(status), payment.status, payment.updatedAt)
        bookingFirstDueAt = next.firstDueAt
        bookingClockCovered = true
    } else if (bookingFirstDueAt == null &&
        bookingProvenAt == null &&
        payment.status in setOf(SepaPaymentStatus.PROCESSING, SepaPaymentStatus.COMPLETED, SepaPaymentStatus.RETURNED)
    ) {
        bookingClockCovered = false
    }
}

/** Caller already validated the strict settlement response and original-identity binding. */
internal fun SepaPaymentEntity.proveBooking(id: UUID, at: Instant) {
    val next = SepaBookingClock(bookingFirstDueAt, bookingProvenAt, bookingTransactionId).proven(id, at)
    bookingProvenAt = next.provenAt
    bookingTransactionId = next.transactionId
    bookingClockCovered = true
}
