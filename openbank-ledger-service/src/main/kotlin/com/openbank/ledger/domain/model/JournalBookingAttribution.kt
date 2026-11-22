// SPDX-License-Identifier: Apache-2.0
package com.openbank.ledger.domain.model

import com.openbank.libs.domain.booking.BookingOperatingMode
import java.util.UUID

/** Immutable reference to the approval version applicable when the journal was booked. */
data class JournalBookingAttribution(
    val companyId: UUID,
    val contextVersionId: UUID,
    val mode: JournalBookingMode,
    val branchId: UUID? = null,
) {
    init {
        require((mode == JournalBookingMode.BRANCH) == (branchId != null)) {
            "Only branch booking requires an establishment identifier"
        }
    }
}

typealias JournalBookingMode = BookingOperatingMode
