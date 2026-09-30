// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.infrastructure.persistence.entity

import com.openbank.delegation.domain.model.ExternalDisclosure
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

/** Persistence contains no raw link or OTP; views are CAS-counted by the repository. */
@Entity
@Table(name = "delegation_external_disclosures")
class ExternalDisclosureEntity : PanacheEntityBase() {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "delegation_id", nullable = false, updatable = false)
    lateinit var delegationId: UUID

    @Column(name = "document_id", nullable = false, updatable = false)
    lateinit var documentId: UUID

    @Column(name = "recipient_label", nullable = false, length = 256)
    lateinit var recipientLabel: String

    @Column(name = "link_secret_hash", nullable = false, length = 64)
    lateinit var linkSecretHash: String

    @Column(name = "otp_hash", nullable = false, length = 64)
    lateinit var otpHash: String

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: OffsetDateTime

    @Column(name = "max_views", nullable = false)
    var maxViews: Int = 1

    @Column(name = "verified_at")
    var verifiedAt: OffsetDateTime? = null

    @Column(name = "view_count", nullable = false)
    var viewCount: Int = 0

    @Column(name = "failed_otp_attempts", nullable = false)
    var failedOtpAttempts: Int = 0

    @Column(name = "locked_at")
    var lockedAt: OffsetDateTime? = null

    @Column(name = "revoked_at")
    var revokedAt: OffsetDateTime? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: OffsetDateTime

    fun toDomain(viewedAt: List<OffsetDateTime>): ExternalDisclosure = ExternalDisclosure(
        id = id,
        delegationId = delegationId,
        documentId = documentId,
        recipientLabel = recipientLabel,
        linkSecretHash = linkSecretHash,
        otpHash = otpHash,
        expiresAt = expiresAt,
        maxViews = maxViews,
        createdAt = createdAt,
        verifiedAt = verifiedAt,
        viewedAt = viewedAt,
        failedOtpAttempts = failedOtpAttempts,
        lockedAt = lockedAt,
        revokedAt = revokedAt,
    )

    fun apply(disclosure: ExternalDisclosure) {
        verifiedAt = disclosure.verifiedAt
        viewCount = disclosure.viewedAt.size
        failedOtpAttempts = disclosure.failedOtpAttempts
        lockedAt = disclosure.lockedAt
        revokedAt = disclosure.revokedAt
    }

    companion object {
        fun fromDomain(disclosure: ExternalDisclosure): ExternalDisclosureEntity = ExternalDisclosureEntity().apply {
            id = disclosure.id
            delegationId = disclosure.delegationId
            documentId = disclosure.documentId
            recipientLabel = disclosure.recipientLabel
            linkSecretHash = disclosure.linkSecretHash
            otpHash = disclosure.otpHash
            expiresAt = disclosure.expiresAt
            maxViews = disclosure.maxViews
            verifiedAt = disclosure.verifiedAt
            viewCount = disclosure.viewedAt.size
            failedOtpAttempts = disclosure.failedOtpAttempts
            lockedAt = disclosure.lockedAt
            revokedAt = disclosure.revokedAt
            createdAt = disclosure.createdAt
        }
    }
}
