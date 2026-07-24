// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.persistence.entity

import com.openbank.sca.domain.model.*
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "sca_challenges")
class ScaChallengeEntity : PanacheEntityBase() {

    @Id
    lateinit var id: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var purpose: ScaPurpose

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var method: ScaMethod

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var status: ScaStatus

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: OffsetDateTime

    @Column(name = "completed_at")
    var completedAt: OffsetDateTime? = null

    @Column(name = "failed_at")
    var failedAt: OffsetDateTime? = null

    @Column(name = "failure_reason")
    var failureReason: String? = null

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0

    @Column(name = "max_attempts", nullable = false)
    var maxAttempts: Int = 3

    @Column(name = "dynamic_amount")
    var dynamicAmount: String? = null

    @Column(name = "dynamic_currency")
    var dynamicCurrency: String? = null

    @Column(name = "dynamic_creditor_iban")
    var dynamicCreditorIban: String? = null

    @Column(name = "dynamic_creditor_name")
    var dynamicCreditorName: String? = null

    @Column(name = "dynamic_reference")
    var dynamicReference: String? = null

    @Column(name = "dynamic_document_sha256")
    var dynamicDocumentSha256: String? = null

    @Column(name = "dynamic_ceremony_id")
    var dynamicCeremonyId: String? = null

    @Column(name = "dynamic_card_id")
    var dynamicCardId: String? = null

    @Column(name = "dynamic_card_action")
    var dynamicCardAction: String? = null

    @Column(name = "redirect_url")
    var redirectUrl: String? = null

    @Column(name = "consumed_at")
    var consumedAt: OffsetDateTime? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: OffsetDateTime

    fun toDomain(): ScaChallenge = ScaChallenge(
        id = id,
        partyId = partyId,
        purpose = purpose,
        method = method,
        status = status,
        expiresAt = expiresAt,
        completedAt = completedAt,
        failedAt = failedAt,
        failureReason = failureReason,
        attemptCount = attemptCount,
        maxAttempts = maxAttempts,
        dynamicLinkingData = if (hasDynamicLinkingData()) {
            DynamicLinkingData(
                dynamicAmount,
                dynamicCurrency,
                dynamicCreditorIban,
                dynamicCreditorName,
                dynamicReference,
                dynamicDocumentSha256,
                dynamicCeremonyId,
                dynamicCardId,
                dynamicCardAction,
            )
        } else {
            null
        },
        redirectUrl = redirectUrl,
        consumedAt = consumedAt,
        createdAt = createdAt,
    )

    private fun hasDynamicLinkingData(): Boolean = dynamicAmount != null ||
        dynamicCreditorIban != null ||
        dynamicDocumentSha256 != null ||
        dynamicCeremonyId != null ||
        dynamicCardId != null ||
        dynamicCardAction != null

    companion object {
        fun fromDomain(c: ScaChallenge): ScaChallengeEntity = ScaChallengeEntity().apply {
            id = c.id
            partyId = c.partyId
            purpose = c.purpose
            method = c.method
            status = c.status
            expiresAt = c.expiresAt
            completedAt = c.completedAt
            failedAt = c.failedAt
            failureReason = c.failureReason
            attemptCount = c.attemptCount
            maxAttempts = c.maxAttempts
            dynamicAmount = c.dynamicLinkingData?.amount
            dynamicCurrency = c.dynamicLinkingData?.currency
            dynamicCreditorIban = c.dynamicLinkingData?.creditorIban
            dynamicCreditorName = c.dynamicLinkingData?.creditorName
            dynamicReference = c.dynamicLinkingData?.reference
            dynamicDocumentSha256 = c.dynamicLinkingData?.documentSha256
            dynamicCeremonyId = c.dynamicLinkingData?.ceremonyId
            dynamicCardId = c.dynamicLinkingData?.cardId
            dynamicCardAction = c.dynamicLinkingData?.cardAction
            redirectUrl = c.redirectUrl
            consumedAt = c.consumedAt
            createdAt = c.createdAt
        }
    }
}
