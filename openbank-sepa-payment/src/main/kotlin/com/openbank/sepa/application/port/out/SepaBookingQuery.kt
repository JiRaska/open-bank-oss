// SPDX-License-Identifier: Apache-2.0

package com.openbank.sepa.application.port.out

import java.time.Instant
import java.util.UUID

/** One snapshot: tracked pending oldest plus unknown historical/bypassed coverage, not proven unsettled count. */
data class SepaBookingAgeSnapshot(val oldestDueAt: Instant?, val unknownLegacyCount: Long)

interface SepaBookingQuery {
    suspend fun bookingAgeSnapshot(): SepaBookingAgeSnapshot

    /** True only while [paymentId] carries a tracked booking obligation (accepted) that is not yet proven. */
    suspend fun isBookingPending(paymentId: UUID): Boolean
}
