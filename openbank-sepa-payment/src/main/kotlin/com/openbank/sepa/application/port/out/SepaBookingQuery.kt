// SPDX-License-Identifier: Apache-2.0

package com.openbank.sepa.application.port.out

import java.time.Instant

/** One snapshot: tracked pending oldest plus unknown historical/bypassed coverage, not proven unsettled count. */
data class SepaBookingAgeSnapshot(val oldestDueAt: Instant?, val unknownLegacyCount: Long)

interface SepaBookingQuery {
    suspend fun bookingAgeSnapshot(): SepaBookingAgeSnapshot
}
