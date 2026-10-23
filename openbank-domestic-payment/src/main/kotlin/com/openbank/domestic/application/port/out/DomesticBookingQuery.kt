// SPDX-License-Identifier: Apache-2.0

package com.openbank.domestic.application.port.out

import java.time.Instant

/** One snapshot: tracked pending oldest plus unknown historical/bypassed coverage, not proven unsettled count. */
data class DomesticBookingAgeSnapshot(val oldestDueAt: Instant?, val unknownLegacyCount: Long)

interface DomesticBookingQuery {
    suspend fun bookingAgeSnapshot(): DomesticBookingAgeSnapshot
}
