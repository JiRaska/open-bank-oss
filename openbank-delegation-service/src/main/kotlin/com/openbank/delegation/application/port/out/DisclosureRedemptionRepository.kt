// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.RedeemableSnapshot
import com.openbank.delegation.domain.model.RedemptionStatus
import java.time.Instant
import java.util.UUID

data class RedemptionChallenge(
    val id: UUID,
    val disclosureId: UUID,
    val status: RedemptionStatus,
    val otpSalt: String,
    val otpHash: String,
    val expiresAt: Instant,
    val failedAttempts: Int,
)

data class IssueRedemptionRecord(
    val id: UUID,
    val disclosureId: UUID,
    val grantorPartyId: UUID,
    val recipientHint: String,
    val magicTokenHash: String,
    val otpSalt: String,
    val otpHash: String,
    val expiresAt: Instant,
    val maxViews: Int,
    val issuanceIdempotencyKeyHash: String,
    val now: Instant,
)

interface DisclosureRedemptionRepository {
    /** Creates or rotates an unverified redemption; terminal/verified rows cannot be replaced. */
    suspend fun issue(record: IssueRedemptionRecord): Boolean
    suspend fun findChallenge(magicTokenHash: String): RedemptionChallenge?
    suspend fun recordFailedAttempt(id: UUID, idempotencyKeyHash: String, now: Instant): Boolean
    suspend fun verify(id: UUID, accessTicketHash: String, idempotencyKeyHash: String, now: Instant): Boolean
    suspend fun peek(accessTicketHash: String, now: Instant): RedeemableSnapshot?
    suspend fun consume(accessTicketHash: String, idempotencyKeyHash: String, now: Instant): RedeemableSnapshot?
    suspend fun revoke(disclosureId: UUID, grantorPartyId: UUID, now: Instant): Boolean
}

interface DisclosureOtpSender {
    suspend fun send(partyId: UUID, recipient: String, otp: String, correlationId: UUID)
}

interface DisclosureSnapshotContentReader {
    suspend fun read(snapshotId: UUID, expectedSha256: String): ByteArray
}
