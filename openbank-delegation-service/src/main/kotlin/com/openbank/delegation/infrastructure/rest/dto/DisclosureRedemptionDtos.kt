// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.rest.dto

import java.time.Instant
import java.util.UUID

data class IssueDisclosureRedemptionRequest(val recipient: String, val expiresAt: Instant, val maxViews: Int)

data class IssueDisclosureRedemptionResponse(
    val redemptionId: UUID,
    val magicToken: String,
    val expiresAt: Instant,
    val maxViews: Int,
)

data class VerifyDisclosureRedemptionRequest(val magicToken: String, val otp: String)
data class VerifyDisclosureRedemptionResponse(val accessTicket: String)
data class DownloadDisclosureRequest(val accessTicket: String)
