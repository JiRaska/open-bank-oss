// SPDX-License-Identifier: Apache-2.0

package com.openbank.domestic.domain.model

import java.time.Instant
import java.util.UUID

/** Internal persistence facts, not fields of the public payment aggregate. */
data class DomesticBookingClock(
    val firstDueAt: Instant? = null,
    val provenAt: Instant? = null,
    val transactionId: UUID? = null,
) {
    fun accepted(from: DomesticPaymentStatus, to: DomesticPaymentStatus, at: Instant): DomesticBookingClock {
        require(from == DomesticPaymentStatus.VALIDATED && to == DomesticPaymentStatus.SENT_TO_CLEARING)
        check(provenAt == null) { "Cannot reopen a proven booking" }
        return if (firstDueAt == null) copy(firstDueAt = at) else this
    }

    fun completed(from: DomesticPaymentStatus, to: DomesticPaymentStatus, id: UUID, at: Instant): DomesticBookingClock {
        require(from == DomesticPaymentStatus.SENT_TO_CLEARING && to == DomesticPaymentStatus.SETTLED)
        check(transactionId == null || transactionId == id) { "Cannot replace a proven booking identity" }
        // A proven replay may settle a legacy row without inventing its historical start time.
        return copy(provenAt = provenAt ?: at, transactionId = id)
    }
}
