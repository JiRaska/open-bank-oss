// SPDX-License-Identifier: Apache-2.0

package com.openbank.account.domain.model

import java.time.Instant
import java.util.UUID

data class AccountBookingReceipt(
    val operationId: UUID,
    val legalEntityId: UUID,
    val bookingBookId: UUID,
    val operatingMode: OperatingMode,
    val establishmentId: UUID?,
    val approvalDigest: String,
    val consumedAt: Instant,
) {
    init {
        require((operatingMode == OperatingMode.BRANCH) == (establishmentId != null))
        require(APPROVAL_DIGEST.matches(approvalDigest))
    }

    enum class OperatingMode { DOMESTIC, FOS, BRANCH }

    private companion object {
        val APPROVAL_DIGEST = Regex("^[0-9a-f]{64}$")
    }
}
