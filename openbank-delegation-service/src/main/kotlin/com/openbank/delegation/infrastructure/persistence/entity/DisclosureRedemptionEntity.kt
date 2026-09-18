// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.persistence.entity

import com.openbank.delegation.application.port.out.RedemptionChallenge
import com.openbank.delegation.domain.model.RedemptionStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "disclosure_redemptions")
class DisclosureRedemptionEntity {
    @Id lateinit var id: UUID

    @Column(name = "disclosure_id")
    lateinit var disclosureId: UUID

    @Column(name = "status")
    lateinit var status: String

    @Column(name = "recipient_hint")
    lateinit var recipientHint: String

    @Column(name = "magic_token_hash")
    var magicTokenHash: String? = null

    @Column(name = "otp_salt")
    var otpSalt: String? = null

    @Column(name = "otp_hash")
    var otpHash: String? = null

    @Column(name = "access_ticket_hash")
    var accessTicketHash: String? = null

    @Column(name = "issuance_idempotency_key_hash")
    lateinit var issuanceIdempotencyKeyHash: String

    @Column(name = "verification_idempotency_key_hash")
    var verificationIdempotencyKeyHash: String? = null

    @Column(name = "expires_at")
    lateinit var expiresAt: Instant

    @Column(name = "max_views")
    var maxViews: Int = 0

    @Column(name = "views")
    var views: Int = 0

    @Column(name = "failed_attempts")
    var failedAttempts: Int = 0

    @Column(name = "verified_at")
    var verifiedAt: Instant? = null

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    @Column(name = "created_at")
    lateinit var createdAt: Instant

    @Column(name = "updated_at")
    lateinit var updatedAt: Instant

    fun toChallenge() = RedemptionChallenge(
        id,
        disclosureId,
        RedemptionStatus.valueOf(status),
        requireNotNull(otpSalt),
        requireNotNull(otpHash),
        expiresAt,
        failedAttempts,
    )
}
