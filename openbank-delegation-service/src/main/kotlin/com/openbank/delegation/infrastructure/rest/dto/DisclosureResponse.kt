// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.rest.dto

import com.openbank.delegation.domain.model.Disclosure
import com.openbank.delegation.domain.model.DisclosureStatus
import java.time.Instant
import java.util.UUID

data class DisclosureResponse(
    val id: UUID,
    val requestId: UUID,
    val delegationId: UUID,
    val status: DisclosureStatus,
    val snapshotId: UUID?,
    val sha256: String?,
    val sizeBytes: Long?,
    val rejectionReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(value: Disclosure) = DisclosureResponse(
            value.id,
            value.requestId,
            value.delegationId,
            value.status,
            value.snapshotId,
            value.snapshotSha256,
            value.sizeBytes,
            value.rejectionReason,
            value.createdAt,
            value.updatedAt,
        )
    }
}
