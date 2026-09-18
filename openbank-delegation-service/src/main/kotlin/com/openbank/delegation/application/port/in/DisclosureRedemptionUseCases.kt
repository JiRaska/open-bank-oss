@file:Suppress("PackageName")

// SPDX-License-Identifier: Apache-2.0

package com.openbank.delegation.application.port.`in`

import java.time.Instant
import java.util.UUID

data class RedeemedDisclosureContent(
    val content: ByteArray,
    val sha256: String,
    val viewNumber: Int,
    val maxViews: Int,
)

data class IssueDisclosureRedemptionCommand(
    val disclosureId: UUID,
    val grantorPartyId: UUID?,
    val recipient: String,
    val expiresAt: Instant,
    val maxViews: Int,
    val idempotencyKey: String,
)

data class IssuedDisclosureRedemption(
    val redemptionId: UUID,
    val magicToken: String,
    val expiresAt: Instant,
    val maxViews: Int,
)

interface IssueDisclosureRedemptionUseCase {
    suspend fun issue(command: IssueDisclosureRedemptionCommand): IssuedDisclosureRedemption
    suspend fun revoke(disclosureId: UUID, grantorPartyId: UUID?)
}

interface PublicDisclosureRedemptionUseCase {
    suspend fun verify(magicToken: String, otp: String, idempotencyKey: String): String
    suspend fun download(accessTicket: String, idempotencyKey: String): RedeemedDisclosureContent
}
