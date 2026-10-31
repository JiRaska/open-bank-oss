// SPDX-License-Identifier: Apache-2.0

package com.openbank.domestic.infrastructure.persistence.repository

import com.openbank.domestic.domain.model.DomesticBookingClock
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.infrastructure.persistence.entity.DomesticPaymentEntity
import java.util.UUID

/** Called only under the existing aggregate row lock and after its revision check. */
internal fun DomesticPaymentEntity.applyBookingTransition(
    payment: DomesticPayment,
    acceptedBooking: Boolean,
    provenTransactionId: UUID?,
) {
    val current = DomesticBookingClock(bookingFirstDueAt, bookingProvenAt, bookingTransactionId)
    val next = when {
        acceptedBooking -> current.accepted(DomesticPaymentStatus.valueOf(status), payment.status, payment.updatedAt)
        provenTransactionId != null -> current.completed(
            DomesticPaymentStatus.valueOf(status),
            payment.status,
            provenTransactionId,
            checkNotNull(payment.settledAt),
        )
        else -> current
    }
    if (acceptedBooking || provenTransactionId != null) {
        bookingClockCovered = true
    } else if (next.firstDueAt == null &&
        next.provenAt == null &&
        payment.status in
        setOf(DomesticPaymentStatus.SENT_TO_CLEARING, DomesticPaymentStatus.SETTLED, DomesticPaymentStatus.RETURNED)
    ) {
        // A generic lifecycle write cannot certify booking coverage. Retain this unknown
        // provenance even if a later generic write moves the row to another terminal state.
        bookingClockCovered = false
    }
    bookingFirstDueAt = next.firstDueAt
    bookingProvenAt = next.provenAt
    bookingTransactionId = next.transactionId
}
