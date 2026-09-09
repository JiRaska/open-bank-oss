// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.domain.model

import java.time.Instant
import java.util.UUID

enum class RedemptionStatus { ISSUED, VERIFIED, LOCKED, REVOKED, EXHAUSTED }

data class DisclosureRedemption(
    val id: UUID,
    val disclosureId: UUID,
    val status: RedemptionStatus,
    val recipientHint: String,
    val expiresAt: Instant,
    val maxViews: Int,
    val views: Int,
    val failedAttempts: Int,
    val verifiedAt: Instant? = null,
    val revokedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class RedeemableSnapshot(
    val redemptionId: UUID,
    val snapshotId: UUID,
    val snapshotSha256: String,
    val viewNumber: Int,
    val maxViews: Int,
)
