// SPDX-License-Identifier: Apache-2.0

package com.openbank.sepa.application.port.out

import java.time.Instant
import java.util.UUID

/** One snapshot: tracked pending oldest plus unknown historical/bypassed coverage, not proven unsettled count. */
data class SepaBookingAgeSnapshot(val oldestDueAt: Instant?, val unknownLegacyCount: Long)

interface SepaBookingQuery {
    suspend fun bookingAgeSnapshot(): SepaBookingAgeSnapshot

    /**
     * The booking-due instant of [paymentId] while it carries a tracked, not-yet-proven booking obligation
     * (set only by the accepted VALIDATED -> PROCESSING write); null otherwise.
     */
    suspend fun pendingBookingDueAt(paymentId: UUID): Instant?
}
