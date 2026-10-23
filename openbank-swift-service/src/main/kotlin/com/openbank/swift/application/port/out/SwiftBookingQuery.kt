// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.application.port.out

import java.time.Instant

/** One snapshot: tracked pending oldest plus unknown historical/bypassed coverage, not proven unsettled count. */
data class SwiftBookingAgeSnapshot(val oldestDueAt: Instant?, val unknownLegacyCount: Long)

interface SwiftBookingQuery {
    suspend fun bookingAgeSnapshot(): SwiftBookingAgeSnapshot
}
