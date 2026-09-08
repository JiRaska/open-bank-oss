// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.domain.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ADR-0232 D7b's external-recipient boundary. This is a sealed document emission, never a grant
 * to a live product API: only opaque-secret hashes persist and every view is bounded and revocable.
 */
data class ExternalDisclosure(
    val id: UUID,
    val delegationId: UUID,
    val documentId: UUID,
    val recipientLabel: String,
    val linkSecretHash: String,
    val otpHash: String,
    val expiresAt: OffsetDateTime,
    val maxViews: Int,
    val createdAt: OffsetDateTime,
    val verifiedAt: OffsetDateTime? = null,
    val viewedAt: List<OffsetDateTime> = emptyList(),
    val revokedAt: OffsetDateTime? = null,
) {
    init {
        require(recipientLabel.isNotBlank()) { "recipient label is required" }
        require(maxViews >= 1) { "external disclosure must permit at least one view" }
        require(expiresAt.isAfter(createdAt)) { "external disclosure expiry must be after issuance" }
    }

    fun verifyOtp(rawOtp: String, now: OffsetDateTime): ExternalDisclosure {
        require(isLinkLive(now)) { "external disclosure link is unavailable" }
        require(secretHash(id, rawOtp) == otpHash) { "external disclosure OTP is invalid" }
        return if (verifiedAt == null) copy(verifiedAt = now) else this
    }

    /** Calling code must persist this compare-and-set transition atomically with its audit outbox. */
    fun consumeView(rawLinkSecret: String, now: OffsetDateTime): ExternalDisclosure {
        require(isLinkLive(now)) { "external disclosure link is unavailable" }
        require(verifiedAt != null) { "external disclosure OTP has not been verified" }
        require(secretHash(id, rawLinkSecret) == linkSecretHash) { "external disclosure link is invalid" }
        require(viewedAt.size < maxViews) { "external disclosure view limit reached" }
        return copy(viewedAt = viewedAt + now)
    }

    fun revoke(now: OffsetDateTime): ExternalDisclosure =
        if (revokedAt == null) copy(revokedAt = now) else this

    private fun isLinkLive(now: OffsetDateTime): Boolean = revokedAt == null && now.isBefore(expiresAt) && viewedAt.size < maxViews

    companion object {
        fun secretHash(disclosureId: UUID, rawSecret: String): String {
            require(rawSecret.isNotBlank()) { "disclosure secret must not be blank" }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("openbank.delegation.external-disclosure.v1".toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(disclosureId.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            return digest.digest(rawSecret.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
