// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.domain.model

import java.time.Instant
import java.util.UUID

enum class DisclosureStatus { REQUESTED, READY, REJECTED }

data class Disclosure(
    val id: UUID,
    val requestId: UUID,
    val delegationId: UUID,
    val grantorPartyId: UUID,
    val sourceDocumentId: UUID,
    val status: DisclosureStatus,
    val snapshotId: UUID? = null,
    val sourceSha256: String? = null,
    val snapshotSha256: String? = null,
    val sizeBytes: Long? = null,
    val rejectionReason: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)
