// SPDX-License-Identifier: Apache-2.0

package com.openbank.sepa.domain.model

import java.time.Instant
import java.util.UUID

/** Internal persistence facts, not fields of the public payment aggregate. */
data class SepaBookingClock(
    val firstDueAt: Instant? = null,
    val provenAt: Instant? = null,
    val transactionId: UUID? = null,
) {
    fun accepted(from: SepaPaymentStatus, to: SepaPaymentStatus, at: Instant): SepaBookingClock {
        require(from == SepaPaymentStatus.VALIDATED && to == SepaPaymentStatus.PROCESSING)
        check(provenAt == null) { "Cannot reopen a proven booking" }
        return if (firstDueAt == null) copy(firstDueAt = at) else this
    }

    fun proven(id: UUID, at: Instant): SepaBookingClock {
        check(transactionId == null || transactionId == id) { "Cannot replace a proven booking identity" }
        // A proven replay may settle a legacy row without inventing its historical start time.
        return copy(provenAt = provenAt ?: at, transactionId = id)
    }
}
