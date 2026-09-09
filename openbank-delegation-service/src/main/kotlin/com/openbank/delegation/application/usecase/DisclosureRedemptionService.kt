// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.IssueDisclosureRedemptionCommand
import com.openbank.delegation.application.port.`in`.IssueDisclosureRedemptionUseCase
import com.openbank.delegation.application.port.`in`.IssuedDisclosureRedemption
import com.openbank.delegation.application.port.`in`.PublicDisclosureRedemptionUseCase
import com.openbank.delegation.application.port.`in`.RedeemedDisclosureContent
import com.openbank.delegation.application.port.out.DisclosureOtpSender
import com.openbank.delegation.application.port.out.DisclosureRedemptionRepository
import com.openbank.delegation.application.port.out.DisclosureRepository
import com.openbank.delegation.application.port.out.DisclosureSecretCodec
import com.openbank.delegation.application.port.out.DisclosureSnapshotContentReader
import com.openbank.delegation.application.port.out.IssueRedemptionRecord
import com.openbank.delegation.domain.model.DisclosureStatus
import com.openbank.delegation.domain.model.RedemptionStatus
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class DisclosureRedemptionUnavailableException : RuntimeException("Disclosure redemption is unavailable")
class DisclosureRedemptionInvalidException : RuntimeException("Disclosure redemption credentials are invalid")

@ApplicationScoped
class DisclosureRedemptionService(
    private val disclosures: DisclosureRepository,
    private val redemptions: DisclosureRedemptionRepository,
    private val secrets: DisclosureSecretCodec,
    private val otpSender: DisclosureOtpSender,
    private val snapshotContent: DisclosureSnapshotContentReader,
    private val clock: Clock,
) : IssueDisclosureRedemptionUseCase,
    PublicDisclosureRedemptionUseCase {
    override suspend fun issue(command: IssueDisclosureRedemptionCommand): IssuedDisclosureRedemption {
        val now = Instant.now(clock)
        require(command.expiresAt.isAfter(now)) { "expiresAt must be in the future" }
        require(!command.expiresAt.isAfter(now.plus(MAX_VALIDITY))) { "validity must not exceed seven days" }
        require(command.maxViews in 1..MAX_VIEWS) { "maxViews must be between 1 and 10" }
        require(EMAIL.matches(command.recipient)) { "recipient must be a well-formed email address" }
        val disclosure = disclosures.findById(command.disclosureId) ?: throw DisclosureRedemptionUnavailableException()
        if (command.grantorPartyId == null ||
            disclosure.grantorPartyId != command.grantorPartyId ||
            disclosure.status != DisclosureStatus.READY
        ) {
            throw DisclosureRedemptionUnavailableException()
        }
        val magicToken = secrets.newOpaqueToken()
        val otp = secrets.newOtp()
        val otpDigest = secrets.hashOtp(otp)
        val redemptionId = UUID.randomUUID()
        val issued = redemptions.issue(
            IssueRedemptionRecord(
                redemptionId,
                disclosure.id,
                command.grantorPartyId,
                recipientHint(command.recipient),
                secrets.hashOpaqueToken(magicToken),
                otpDigest.salt,
                otpDigest.digest,
                command.expiresAt,
                command.maxViews,
                now,
            ),
        )
        if (!issued) throw DisclosureRedemptionUnavailableException()
        otpSender.send(command.grantorPartyId, command.recipient, otp, redemptionId)
        return IssuedDisclosureRedemption(redemptionId, magicToken, command.expiresAt, command.maxViews)
    }

    override suspend fun verify(magicToken: String, otp: String): String {
        val now = Instant.now(clock)
        val challenge = redemptions.findChallenge(secrets.hashOpaqueToken(magicToken))
            ?: throw DisclosureRedemptionInvalidException()
        if (challenge.status != RedemptionStatus.ISSUED ||
            !now.isBefore(challenge.expiresAt) ||
            challenge.failedAttempts >= MAX_ATTEMPTS
        ) {
            throw DisclosureRedemptionInvalidException()
        }
        if (!secrets.verifyOtp(otp, challenge.otpSalt, challenge.otpHash)) {
            redemptions.recordFailedAttempt(challenge.id, now)
            throw DisclosureRedemptionInvalidException()
        }
        val ticket = secrets.newOpaqueToken()
        if (!redemptions.verify(challenge.id, secrets.hashOpaqueToken(ticket), now)) {
            throw DisclosureRedemptionInvalidException()
        }
        return ticket
    }

    override suspend fun download(accessTicket: String): RedeemedDisclosureContent {
        val ticketHash = secrets.hashOpaqueToken(accessTicket)
        val candidate = redemptions.peek(ticketHash, Instant.now(clock))
            ?: throw DisclosureRedemptionInvalidException()
        val content = snapshotContent.read(candidate.snapshotId, candidate.snapshotSha256)
        if (!MessageDigest.isEqual(sha256(content).toByteArray(), candidate.snapshotSha256.toByteArray())) {
            throw DisclosureRedemptionUnavailableException()
        }
        val consumed = redemptions.consume(ticketHash, Instant.now(clock))
            ?: throw DisclosureRedemptionInvalidException()
        return RedeemedDisclosureContent(content, consumed.snapshotSha256, consumed.viewNumber, consumed.maxViews)
    }

    override suspend fun revoke(disclosureId: UUID, grantorPartyId: UUID?) {
        if (grantorPartyId == null || !redemptions.revoke(disclosureId, grantorPartyId, Instant.now(clock))) {
            throw DisclosureRedemptionUnavailableException()
        }
    }

    private fun recipientHint(email: String): String {
        val normalized = email.trim().lowercase()
        val (local, domain) = normalized.split('@', limit = 2)
        return "${local.first()}***@$domain"
    }

    private companion object {
        const val MAX_VIEWS = 10
        const val MAX_ATTEMPTS = 5
        val MAX_VALIDITY: Duration = Duration.ofDays(7)
        val EMAIL = Regex("^[^@\\s]{1,64}@[^@\\s]{1,190}$")

        fun sha256(content: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    }
}
